package com.scanner3d.core

import kotlin.math.sqrt

object MeshOps {

    /** Area-weighted vertex normals. */
    fun computeNormals(positions: FloatArray, indices: IntArray): FloatArray {
        val normals = FloatArray(positions.size)
        val tc = indices.size / 3
        for (t in 0 until tc) {
            val a = indices[3 * t]; val b = indices[3 * t + 1]; val c = indices[3 * t + 2]
            val ux = positions[3 * b] - positions[3 * a]
            val uy = positions[3 * b + 1] - positions[3 * a + 1]
            val uz = positions[3 * b + 2] - positions[3 * a + 2]
            val vx = positions[3 * c] - positions[3 * a]
            val vy = positions[3 * c + 1] - positions[3 * a + 1]
            val vz = positions[3 * c + 2] - positions[3 * a + 2]
            val nx = uy * vz - uz * vy
            val ny = uz * vx - ux * vz
            val nz = ux * vy - uy * vx
            normals[3 * a] += nx; normals[3 * a + 1] += ny; normals[3 * a + 2] += nz
            normals[3 * b] += nx; normals[3 * b + 1] += ny; normals[3 * b + 2] += nz
            normals[3 * c] += nx; normals[3 * c + 1] += ny; normals[3 * c + 2] += nz
        }
        for (v in 0 until positions.size / 3) {
            val x = normals[3 * v]; val y = normals[3 * v + 1]; val z = normals[3 * v + 2]
            val l = sqrt(x * x + y * y + z * z)
            if (l > 1e-12f) {
                normals[3 * v] = x / l; normals[3 * v + 1] = y / l; normals[3 * v + 2] = z / l
            } else {
                normals[3 * v + 1] = 1f
            }
        }
        return normals
    }

    /** Vertex adjacency in CSR form: neighbours of v are adj[start[v] until start[v+1]]. */
    class Adjacency(val start: IntArray, val adj: IntArray)

    fun adjacency(vertexCount: Int, indices: IntArray): Adjacency {
        val tc = indices.size / 3
        val deg = IntArray(vertexCount + 1)
        for (t in 0 until tc) for (c in 0 until 3) deg[indices[3 * t + c]] += 2
        val start = IntArray(vertexCount + 1)
        for (v in 0 until vertexCount) start[v + 1] = start[v] + deg[v]
        val fill = start.copyOf(vertexCount)
        val adj = IntArray(start[vertexCount])
        for (t in 0 until tc) {
            val a = indices[3 * t]; val b = indices[3 * t + 1]; val c = indices[3 * t + 2]
            adj[fill[a]++] = b; adj[fill[a]++] = c
            adj[fill[b]++] = a; adj[fill[b]++] = c
            adj[fill[c]++] = a; adj[fill[c]++] = b
        }
        // Neighbours appear twice for interior edges, which simply doubles their weight evenly.
        return Adjacency(start, adj)
    }

    /** Taubin lambda/mu smoothing, which reduces noise without shrinking the surface. */
    fun taubinSmooth(positions: FloatArray, indices: IntArray, iterations: Int, lambda: Float = 0.5f, mu: Float = -0.53f) {
        val vc = positions.size / 3
        if (vc == 0) return
        val a = adjacency(vc, indices)
        val tmp = FloatArray(positions.size)
        repeat(iterations) {
            laplacianStep(positions, tmp, a, lambda)
            laplacianStep(positions, tmp, a, mu)
        }
    }

    private fun laplacianStep(p: FloatArray, tmp: FloatArray, a: Adjacency, factor: Float) {
        val vc = p.size / 3
        parallelFor(vc, 8192) { s, e ->
            for (v in s until e) {
                val st = a.start[v]; val en = a.start[v + 1]
                if (en == st) {
                    tmp[3 * v] = p[3 * v]; tmp[3 * v + 1] = p[3 * v + 1]; tmp[3 * v + 2] = p[3 * v + 2]
                    continue
                }
                var sx = 0f; var sy = 0f; var sz = 0f
                for (k in st until en) {
                    val u = a.adj[k]
                    sx += p[3 * u]; sy += p[3 * u + 1]; sz += p[3 * u + 2]
                }
                val inv = 1f / (en - st)
                tmp[3 * v] = p[3 * v] + factor * (sx * inv - p[3 * v])
                tmp[3 * v + 1] = p[3 * v + 1] + factor * (sy * inv - p[3 * v + 1])
                tmp[3 * v + 2] = p[3 * v + 2] + factor * (sz * inv - p[3 * v + 2])
            }
        }
        System.arraycopy(tmp, 0, p, 0, p.size)
    }
}
