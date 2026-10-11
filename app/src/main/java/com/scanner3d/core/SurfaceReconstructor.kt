package com.scanner3d.core

import java.util.Arrays
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Parameters for [SurfaceReconstructor]. Lengths are in metres. */
data class ReconstructionParams(
    /** Grid spacing of the implicit field; the resulting mesh resolution. */
    val cellSize: Float,
    /** Radius of the point kernel used to evaluate the implicit surface. */
    val supportRadius: Float = cellSize * 2.5f,
    /** Ignore points seen in fewer frames than this. */
    val minObservations: Int = 1,
    /** Remove isolated points before meshing. */
    val removeOutliers: Boolean = true,
    /** Keep only the largest connected piece of the mesh. */
    val keepLargestOnly: Boolean = false,
    /** Taubin smoothing passes applied to the mesh. */
    val smoothingIterations: Int = 4,
    /** Pieces smaller than this fraction of all triangles are dropped. */
    val minComponentFraction: Float = 0.01f,
    /** Upper bound on evaluated grid vertices; the cell size grows automatically to fit. */
    val maxGridVertices: Int = 6_000_000,
)

/**
 * Turns an oriented point cloud into a triangle mesh:
 * 1. filters noise and re-estimates normals by PCA,
 * 2. evaluates an implicit moving-least-squares signed distance on a sparse grid near the points,
 * 3. extracts the zero level set with naive Surface Nets,
 * 4. drops small pieces, applies Taubin smoothing and transfers point colours.
 */
class SurfaceReconstructor {

    fun reconstruct(
        input: PointCloud,
        params: ReconstructionParams,
        progress: ProgressSink = ProgressSink.NONE,
    ): Mesh {
        progress.onProgress(ProgressSink.Stage.PREPARE, 0f)
        var cloud = input
        if (params.minObservations > 1) {
            val filtered = cloud.filter { cloud.observations[it] >= params.minObservations }
            // Do not let the observation filter wipe out sparse clouds entirely.
            if (filtered.size >= max(64, cloud.size / 10)) cloud = filtered
        }
        if (cloud.size < 16) return Mesh.EMPTY
        progress.checkCancelled()

        val spacing = estimateSpacing(cloud)
        var h = max(params.cellSize, spacing * 0.75f)
        var radius = max(params.supportRadius, spacing * 2.5f)
        radius = max(radius, h * 2f)

        if (params.removeOutliers) {
            cloud = removeOutliers(cloud, max(radius, spacing * 3f), progress)
            if (cloud.size < 16) return Mesh.EMPTY
        }
        progress.onProgress(ProgressSink.Stage.PREPARE, 1f)

        // Normals.
        val positions = cloud.positions
        val normals = cloud.normals.copyOf()
        val n = cloud.size
        val normalGrid = SpatialGrid(positions, n, max(spacing * 2.5f, cloud.voxelSize * 2f))
        NormalEstimator.estimate(
            positions, normals, n, max(spacing * 2.5f, cloud.voxelSize * 2f), normalGrid,
            progress = progress,
        )
        progress.checkCancelled()

        // Grid sizing: grow the cell size until the active band fits the vertex budget.
        val bounds = cloud.bounds()
        var occupied: LongIntHashMap
        while (true) {
            occupied = occupiedCells(positions, n, bounds, h)
            if (occupied.size.toLong() * 12 <= params.maxGridVertices) break
            h *= 1.3f
            radius = max(radius, h * 2f)
        }
        progress.checkCancelled()

        val pointGrid = SpatialGrid(positions, n, radius)
        val weights = FloatArray(n) { sqrt(min(cloud.observations[it], 16).toFloat()) }
        val field = buildField(occupied, bounds, h, radius, positions, normals, weights, pointGrid, progress)
        progress.checkCancelled()

        val raw = extractSurfaceNets(field, progress)
        progress.checkCancelled()
        if (raw.indices.isEmpty()) return Mesh.EMPTY

        progress.onProgress(ProgressSink.Stage.CLEANUP, 0f)
        var mesh = removeSmallComponents(raw, params.minComponentFraction, params.keepLargestOnly)
        progress.onProgress(ProgressSink.Stage.CLEANUP, 0.3f)
        if (params.smoothingIterations > 0) {
            MeshOps.taubinSmooth(mesh.positions, mesh.indices, params.smoothingIterations)
        }
        progress.onProgress(ProgressSink.Stage.CLEANUP, 0.8f)
        val meshNormals = MeshOps.computeNormals(mesh.positions, mesh.indices)
        progress.onProgress(ProgressSink.Stage.CLEANUP, 1f)
        progress.checkCancelled()

        val colors = transferColors(mesh.positions, cloud, pointGrid, radius, progress)
        mesh = Mesh(mesh.positions, meshNormals, colors, mesh.indices)
        return mesh
    }

    /** Median distance to the nearest neighbour, over a sample of points. */
    internal fun estimateSpacing(cloud: PointCloud): Float {
        val n = cloud.size
        val searchCell = cloud.voxelSize * 4f
        val grid = SpatialGrid(cloud.positions, n, searchCell)
        val stride = max(1, n / 4000)
        val dists = ArrayList<Float>()
        var i = 0
        while (i < n) {
            val p = cloud.positions
            val j = grid.nearest(p[3 * i], p[3 * i + 1], p[3 * i + 2], searchCell * 2f, exclude = i)
            if (j >= 0) {
                val dx = p[3 * j] - p[3 * i]; val dy = p[3 * j + 1] - p[3 * i + 1]; val dz = p[3 * j + 2] - p[3 * i + 2]
                dists.add(sqrt(dx * dx + dy * dy + dz * dz))
            } else {
                dists.add(searchCell * 2f)
            }
            i += stride
        }
        if (dists.isEmpty()) return cloud.voxelSize
        dists.sort()
        return max(dists[dists.size / 2], cloud.voxelSize * 0.5f)
    }

    private fun removeOutliers(cloud: PointCloud, radius: Float, progress: ProgressSink): PointCloud {
        val n = cloud.size
        val grid = SpatialGrid(cloud.positions, n, radius)
        val counts = IntArray(n)
        parallelFor(n, 4096) { s, e ->
            progress.checkCancelled()
            for (i in s until e) {
                var c = 0
                val p = cloud.positions
                grid.forEachWithin(p[3 * i], p[3 * i + 1], p[3 * i + 2], radius) { _, _ -> c++ }
                counts[i] = c
            }
        }
        val sorted = counts.copyOf()
        Arrays.sort(sorted)
        val median = sorted[n / 2]
        val threshold = max(3, (median * 0.12f).toInt())
        val result = cloud.filter { counts[it] >= threshold }
        return if (result.size >= 16) result else cloud
    }

    private fun occupiedCells(positions: FloatArray, n: Int, b: Bounds, h: Float): LongIntHashMap {
        val inv = 1f / h
        val set = LongIntHashMap(max(1024, n / 2))
        for (i in 0 until n) {
            val key = GridKey.pack(
                floor((positions[3 * i] - b.minX) * inv).toInt(),
                floor((positions[3 * i + 1] - b.minY) * inv).toInt(),
                floor((positions[3 * i + 2] - b.minZ) * inv).toInt(),
            )
            set.putIfAbsent(key, 0)
        }
        return set
    }

    /** Sparse implicit field sampled at grid vertices. */
    internal class Field(
        val originX: Float, val originY: Float, val originZ: Float,
        val h: Float,
        val keys: LongArray,
        val values: FloatArray,
        val slots: LongIntHashMap,
    ) {
        fun value(x: Int, y: Int, z: Int): Float {
            val s = slots.get(GridKey.pack(x, y, z))
            return if (s < 0) Float.NaN else values[s]
        }
    }

    private fun buildField(
        occupied: LongIntHashMap,
        b: Bounds,
        h: Float,
        radius: Float,
        positions: FloatArray,
        normals: FloatArray,
        weights: FloatArray,
        grid: SpatialGrid,
        progress: ProgressSink,
    ): Field {
        progress.onProgress(ProgressSink.Stage.FIELD, 0f)
        // Active vertices: corners of cells within one cell of an occupied cell.
        val slots = LongIntHashMap(occupied.size * 8)
        var keys = LongArray(occupied.size * 8)
        var count = 0
        occupied.forEach { key, _ ->
            val cx = GridKey.x(key); val cy = GridKey.y(key); val cz = GridKey.z(key)
            for (dz in -1..2) for (dy in -1..2) for (dx in -1..2) {
                val vk = GridKey.pack(cx + dx, cy + dy, cz + dz)
                if (slots.putIfAbsent(vk, count) < 0) {
                    if (count == keys.size) keys = keys.copyOf(keys.size * 2)
                    keys[count++] = vk
                }
            }
        }
        progress.checkCancelled()
        val values = FloatArray(count)
        val r2 = radius * radius
        val minWeight = 0.35f
        val done = AtomicInteger()
        parallelFor(count, 4096) { s, e ->
            progress.checkCancelled()
            for (k in s until e) {
                val key = keys[k]
                val x = b.minX + GridKey.x(key) * h
                val y = b.minY + GridKey.y(key) * h
                val z = b.minZ + GridKey.z(key) * h
                var sumW = 0f
                var sumKernel = 0f
                var sumF = 0f
                grid.forEachWithin(x, y, z, radius) { i, d2 ->
                    val t = 1f - d2 / r2
                    val kernel = t * t * t
                    val w = kernel * weights[i]
                    val dx = x - positions[3 * i]
                    val dy = y - positions[3 * i + 1]
                    val dz = z - positions[3 * i + 2]
                    sumF += w * (dx * normals[3 * i] + dy * normals[3 * i + 1] + dz * normals[3 * i + 2])
                    sumW += w
                    sumKernel += kernel
                }
                values[k] = if (sumKernel >= minWeight && sumW > 0f) sumF / sumW else Float.NaN
            }
            val d = done.addAndGet(e - s)
            progress.onProgress(ProgressSink.Stage.FIELD, d.toFloat() / count)
        }
        return Field(b.minX, b.minY, b.minZ, h, keys.copyOf(count), values, slots)
    }

    /** Naive Surface Nets over the sparse field. Inside is f < 0. */
    internal fun extractSurfaceNets(field: Field, progress: ProgressSink = ProgressSink.NONE): Mesh {
        progress.onProgress(ProgressSink.Stage.MESH, 0f)
        val keys = field.keys
        val values = field.values
        val h = field.h
        val cellVertex = LongIntHashMap(keys.size / 4 + 16)
        var pos = FloatArray(3 * 4096)
        var vCount = 0
        val corner = FloatArray(8)

        for (k in keys.indices) {
            if (k and 0xFFFF == 0) {
                progress.checkCancelled()
                progress.onProgress(ProgressSink.Stage.MESH, 0.5f * k / keys.size)
            }
            val key = keys[k]
            val x = GridKey.x(key); val y = GridKey.y(key); val z = GridKey.z(key)
            var mask = 0
            var valid = true
            for (c in 0 until 8) {
                val v = field.value(x + (c and 1), y + ((c shr 1) and 1), z + ((c shr 2) and 1))
                if (v.isNaN()) { valid = false; break }
                corner[c] = v
                if (v < 0f) mask = mask or (1 shl c)
            }
            if (!valid || mask == 0 || mask == 0xFF) continue
            var sx = 0f; var sy = 0f; var sz = 0f
            var crossings = 0
            for (e in 0 until 12) {
                val a = EDGES[2 * e]; val bIdx = EDGES[2 * e + 1]
                val fa = corner[a]; val fb = corner[bIdx]
                if ((fa < 0f) == (fb < 0f)) continue
                val t = fa / (fa - fb)
                val ax = (a and 1).toFloat(); val ay = ((a shr 1) and 1).toFloat(); val az = ((a shr 2) and 1).toFloat()
                val bx = (bIdx and 1).toFloat(); val by = ((bIdx shr 1) and 1).toFloat(); val bz = ((bIdx shr 2) and 1).toFloat()
                sx += ax + t * (bx - ax)
                sy += ay + t * (by - ay)
                sz += az + t * (bz - az)
                crossings++
            }
            if (vCount * 3 + 3 > pos.size) pos = pos.copyOf(pos.size * 2)
            pos[3 * vCount] = field.originX + (x + sx / crossings) * h
            pos[3 * vCount + 1] = field.originY + (y + sy / crossings) * h
            pos[3 * vCount + 2] = field.originZ + (z + sz / crossings) * h
            cellVertex.put(key, vCount)
            vCount++
        }

        var idx = IntArray(3 * 8192)
        var iCount = 0
        val quad = IntArray(4)
        for (k in keys.indices) {
            if (k and 0xFFFF == 0) {
                progress.checkCancelled()
                progress.onProgress(ProgressSink.Stage.MESH, 0.5f + 0.5f * k / keys.size)
            }
            val f0 = values[k]
            if (f0.isNaN()) continue
            val key = keys[k]
            val x = GridKey.x(key); val y = GridKey.y(key); val z = GridKey.z(key)
            for (axis in 0 until 3) {
                val f1 = when (axis) {
                    0 -> field.value(x + 1, y, z)
                    1 -> field.value(x, y + 1, z)
                    else -> field.value(x, y, z + 1)
                }
                if (f1.isNaN() || (f0 < 0f) == (f1 < 0f)) continue
                // Cells around the edge, counter-clockwise when looking down the edge axis.
                val ok = when (axis) {
                    0 -> collect(cellVertex, quad, x, y - 1, z - 1, x, y, z - 1, x, y, z, x, y - 1, z)
                    1 -> collect(cellVertex, quad, x - 1, y, z - 1, x - 1, y, z, x, y, z, x, y, z - 1)
                    else -> collect(cellVertex, quad, x - 1, y - 1, z, x, y - 1, z, x, y, z, x - 1, y, z)
                }
                if (!ok) continue
                if (iCount + 6 > idx.size) idx = idx.copyOf(idx.size * 2)
                // Normal of the CCW quad points along +axis; flip if the inside is at +axis.
                val flip = f0 >= 0f
                val q0 = quad[0]; val q1 = if (flip) quad[3] else quad[1]
                val q2 = quad[2]; val q3 = if (flip) quad[1] else quad[3]
                // Split along the shorter diagonal.
                if (dist2(pos, q0, q2) <= dist2(pos, q1, q3)) {
                    idx[iCount++] = q0; idx[iCount++] = q1; idx[iCount++] = q2
                    idx[iCount++] = q0; idx[iCount++] = q2; idx[iCount++] = q3
                } else {
                    idx[iCount++] = q0; idx[iCount++] = q1; idx[iCount++] = q3
                    idx[iCount++] = q1; idx[iCount++] = q2; idx[iCount++] = q3
                }
            }
        }
        progress.onProgress(ProgressSink.Stage.MESH, 1f)
        val positions = pos.copyOf(vCount * 3)
        return Mesh(positions, FloatArray(vCount * 3), ByteArray(vCount * 3), idx.copyOf(iCount))
    }

    private fun dist2(p: FloatArray, a: Int, b: Int): Float {
        val dx = p[3 * a] - p[3 * b]; val dy = p[3 * a + 1] - p[3 * b + 1]; val dz = p[3 * a + 2] - p[3 * b + 2]
        return dx * dx + dy * dy + dz * dz
    }

    private fun collect(
        map: LongIntHashMap, out: IntArray,
        x0: Int, y0: Int, z0: Int, x1: Int, y1: Int, z1: Int,
        x2: Int, y2: Int, z2: Int, x3: Int, y3: Int, z3: Int,
    ): Boolean {
        out[0] = map.get(GridKey.pack(x0, y0, z0)); if (out[0] < 0) return false
        out[1] = map.get(GridKey.pack(x1, y1, z1)); if (out[1] < 0) return false
        out[2] = map.get(GridKey.pack(x2, y2, z2)); if (out[2] < 0) return false
        out[3] = map.get(GridKey.pack(x3, y3, z3)); if (out[3] < 0) return false
        return true
    }

    private fun removeSmallComponents(mesh: Mesh, minFraction: Float, keepLargest: Boolean): Mesh {
        val vc = mesh.vertexCount
        val idx = mesh.indices
        val tc = mesh.triangleCount
        val parent = IntArray(vc) { it }
        fun find(a: Int): Int {
            var r = a
            while (parent[r] != r) r = parent[r]
            var c = a
            while (parent[c] != r) { val nx = parent[c]; parent[c] = r; c = nx }
            return r
        }
        fun union(a: Int, b: Int) {
            val ra = find(a); val rb = find(b)
            if (ra != rb) parent[ra] = rb
        }
        for (t in 0 until tc) {
            union(idx[3 * t], idx[3 * t + 1])
            union(idx[3 * t], idx[3 * t + 2])
        }
        val triPerRoot = IntArray(vc)
        for (t in 0 until tc) triPerRoot[find(idx[3 * t])]++
        var largest = 0
        var largestRoot = -1
        for (v in 0 until vc) if (triPerRoot[v] > largest) { largest = triPerRoot[v]; largestRoot = v }
        val minTris = max(8, (tc * minFraction).toInt())

        val keepTri = BooleanArray(tc)
        var kept = 0
        for (t in 0 until tc) {
            val r = find(idx[3 * t])
            val keep = if (keepLargest) r == largestRoot else triPerRoot[r] >= minTris
            if (keep) { keepTri[t] = true; kept++ }
        }
        if (kept == 0) return mesh
        return compact(mesh.positions, idx, keepTri, kept)
    }

    private fun compact(positions: FloatArray, idx: IntArray, keepTri: BooleanArray, kept: Int): Mesh {
        val vc = positions.size / 3
        val remap = IntArray(vc) { -1 }
        var nv = 0
        val newIdx = IntArray(kept * 3)
        var j = 0
        for (t in keepTri.indices) {
            if (!keepTri[t]) continue
            for (c in 0 until 3) {
                val v = idx[3 * t + c]
                if (remap[v] < 0) remap[v] = nv++
                newIdx[j++] = remap[v]
            }
        }
        val newPos = FloatArray(nv * 3)
        for (v in 0 until vc) {
            val r = remap[v]
            if (r >= 0) System.arraycopy(positions, 3 * v, newPos, 3 * r, 3)
        }
        return Mesh(newPos, FloatArray(nv * 3), ByteArray(nv * 3), newIdx)
    }

    private fun transferColors(
        positions: FloatArray,
        cloud: PointCloud,
        grid: SpatialGrid,
        radius: Float,
        progress: ProgressSink,
    ): ByteArray {
        val vc = positions.size / 3
        val out = ByteArray(vc * 3)
        val r2 = radius * radius
        val searchR = radius * 0.6f
        val done = AtomicInteger()
        parallelFor(vc, 4096) { s, e ->
            progress.checkCancelled()
            for (v in s until e) {
                val x = positions[3 * v]; val y = positions[3 * v + 1]; val z = positions[3 * v + 2]
                var sw = 0f; var sr = 0f; var sg = 0f; var sb = 0f
                grid.forEachWithin(x, y, z, searchR) { i, d2 ->
                    val t = 1f - d2 / r2
                    val w = t * t * t * t + 1e-6f
                    sr += w * (cloud.colors[3 * i].toInt() and 0xFF)
                    sg += w * (cloud.colors[3 * i + 1].toInt() and 0xFF)
                    sb += w * (cloud.colors[3 * i + 2].toInt() and 0xFF)
                    sw += w
                }
                if (sw <= 0f) {
                    val j = grid.nearest(x, y, z, radius)
                    if (j >= 0) {
                        out[3 * v] = cloud.colors[3 * j]
                        out[3 * v + 1] = cloud.colors[3 * j + 1]
                        out[3 * v + 2] = cloud.colors[3 * j + 2]
                    } else {
                        out[3 * v] = 160.toByte(); out[3 * v + 1] = 160.toByte(); out[3 * v + 2] = 160.toByte()
                    }
                } else {
                    out[3 * v] = (sr / sw + 0.5f).toInt().coerceIn(0, 255).toByte()
                    out[3 * v + 1] = (sg / sw + 0.5f).toInt().coerceIn(0, 255).toByte()
                    out[3 * v + 2] = (sb / sw + 0.5f).toInt().coerceIn(0, 255).toByte()
                }
            }
            val d = done.addAndGet(e - s)
            progress.onProgress(ProgressSink.Stage.COLOR, d.toFloat() / vc)
        }
        return out
    }

    private companion object {
        /** Cube edges as pairs of corner indices (corner bit0 = x, bit1 = y, bit2 = z). */
        val EDGES = intArrayOf(
            0, 1, 2, 3, 4, 5, 6, 7, // along x
            0, 2, 1, 3, 4, 6, 5, 7, // along y
            0, 4, 1, 5, 2, 6, 3, 7, // along z
        )
    }
}
