package com.scanner3d.core

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Axis-aligned bounding box. */
data class Bounds(
    val minX: Float, val minY: Float, val minZ: Float,
    val maxX: Float, val maxY: Float, val maxZ: Float,
) {
    val sizeX get() = maxX - minX
    val sizeY get() = maxY - minY
    val sizeZ get() = maxZ - minZ
    val centerX get() = (minX + maxX) * 0.5f
    val centerY get() = (minY + maxY) * 0.5f
    val centerZ get() = (minZ + maxZ) * 0.5f
    val radius get() = 0.5f * sqrt(sizeX * sizeX + sizeY * sizeY + sizeZ * sizeZ)
    val isEmpty get() = minX > maxX

    companion object {
        val EMPTY = Bounds(
            Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE,
            -Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE,
        )

        fun of(positions: FloatArray, count: Int = positions.size / 3): Bounds {
            if (count == 0) return EMPTY
            var x0 = Float.MAX_VALUE; var y0 = Float.MAX_VALUE; var z0 = Float.MAX_VALUE
            var x1 = -Float.MAX_VALUE; var y1 = -Float.MAX_VALUE; var z1 = -Float.MAX_VALUE
            for (i in 0 until count) {
                val x = positions[3 * i]; val y = positions[3 * i + 1]; val z = positions[3 * i + 2]
                x0 = min(x0, x); y0 = min(y0, y); z0 = min(z0, z)
                x1 = max(x1, x); y1 = max(y1, y); z1 = max(z1, z)
            }
            return Bounds(x0, y0, z0, x1, y1, z1)
        }
    }
}

/**
 * A finalized point cloud. Normals are unit length and oriented towards the cameras that observed
 * the points (or zero if unknown). Colors are packed RGB bytes (3 per point).
 */
class PointCloud(
    val positions: FloatArray,
    val normals: FloatArray,
    val colors: ByteArray,
    val observations: IntArray,
    val voxelSize: Float,
) {
    val size: Int get() = observations.size

    init {
        require(positions.size == size * 3 && normals.size == size * 3 && colors.size == size * 3)
    }

    fun bounds(): Bounds = Bounds.of(positions, size)

    /** Returns a new cloud containing only the points for which [keep] returns true. */
    inline fun filter(keep: (Int) -> Boolean): PointCloud {
        var n = 0
        val flags = BooleanArray(size)
        for (i in 0 until size) if (keep(i)) { flags[i] = true; n++ }
        if (n == size) return this
        val p = FloatArray(n * 3)
        val nr = FloatArray(n * 3)
        val c = ByteArray(n * 3)
        val o = IntArray(n)
        var j = 0
        for (i in 0 until size) {
            if (!flags[i]) continue
            System.arraycopy(positions, 3 * i, p, 3 * j, 3)
            System.arraycopy(normals, 3 * i, nr, 3 * j, 3)
            System.arraycopy(colors, 3 * i, c, 3 * j, 3)
            o[j] = observations[i]
            j++
        }
        return PointCloud(p, nr, c, o, voxelSize)
    }

    companion object {
        fun empty(voxelSize: Float) =
            PointCloud(FloatArray(0), FloatArray(0), ByteArray(0), IntArray(0), voxelSize)
    }
}

/** A triangle mesh with per-vertex normals and RGB colors. */
class Mesh(
    val positions: FloatArray,
    val normals: FloatArray,
    val colors: ByteArray,
    val indices: IntArray,
) {
    val vertexCount: Int get() = positions.size / 3
    val triangleCount: Int get() = indices.size / 3

    fun bounds(): Bounds = Bounds.of(positions, vertexCount)

    companion object {
        val EMPTY = Mesh(FloatArray(0), FloatArray(0), ByteArray(0), IntArray(0))
    }
}
