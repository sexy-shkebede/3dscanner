package com.scanner3d.core

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Append-only buffer of points for live rendering: 16 bytes per point (xyz floats + RGBA bytes).
 * The writer thread fills slots and then advances [published]; a reader may consume any slot
 * below [published] without locking.
 */
class DisplayBuffer(val capacity: Int) {
    val buffer: ByteBuffer = ByteBuffer.allocateDirect(capacity * STRIDE).order(ByteOrder.nativeOrder())

    @Volatile
    var published: Int = 0
        private set

    /** Incremented every time the buffer is reset so readers know to re-upload. */
    @Volatile
    var generation: Int = 0
        private set

    fun write(index: Int, x: Float, y: Float, z: Float, r: Int, g: Int, b: Int) {
        val o = index * STRIDE
        buffer.putFloat(o, x)
        buffer.putFloat(o + 4, y)
        buffer.putFloat(o + 8, z)
        buffer.put(o + 12, r.toByte())
        buffer.put(o + 13, g.toByte())
        buffer.put(o + 14, b.toByte())
        buffer.put(o + 15, 255.toByte())
    }

    fun publish(count: Int) {
        published = count
    }

    fun reset() {
        published = 0
        generation++
    }

    companion object {
        const val STRIDE = 16
    }
}

/**
 * Sparse voxel map that fuses observations into one averaged point per voxel. Not thread-safe:
 * all mutating calls must come from a single integration thread.
 */
class PointMap(
    val voxelSize: Float,
    val capacity: Int,
    val display: DisplayBuffer? = null,
) {
    private val invVoxel = 1f / voxelSize
    private val index = LongIntHashMap(1 shl 16)
    private var data = FloatArray(STRIDE * INITIAL)
    private var obs = IntArray(INITIAL)
    private var lastFrame = IntArray(INITIAL)
    private var frameId = 0

    @Volatile
    var count: Int = 0
        private set

    @Volatile
    var isFull: Boolean = false
        private set

    /** Marks the start of a new frame, so repeated hits in one frame count as one observation. */
    fun beginFrame() {
        frameId++
    }

    /**
     * Adds one observation.
     * @param nx normal (unit, oriented to camera) or zeros if unknown.
     * @param vx unit direction from point to camera.
     * @param r color 0..255.
     */
    fun add(
        x: Float, y: Float, z: Float,
        nx: Float, ny: Float, nz: Float,
        vx: Float, vy: Float, vz: Float,
        r: Float, g: Float, b: Float,
        weight: Float,
    ) {
        val key = GridKey.pack(
            floor(x * invVoxel).toInt(),
            floor(y * invVoxel).toInt(),
            floor(z * invVoxel).toInt(),
        )
        var i = index.get(key)
        if (i < 0) {
            if (count >= capacity) {
                isFull = true
                return
            }
            i = count
            ensureCapacity(i + 1)
            index.put(key, i)
            val o = i * STRIDE
            for (k in 0 until STRIDE) data[o + k] = 0f
            obs[i] = 0
            lastFrame[i] = -1
            display?.write(i, x, y, z, r.toInt(), g.toInt(), b.toInt())
            count = i + 1
            display?.publish(count)
        }
        val o = i * STRIDE
        val d = data
        d[o] += x * weight; d[o + 1] += y * weight; d[o + 2] += z * weight
        d[o + 3] += nx * weight; d[o + 4] += ny * weight; d[o + 5] += nz * weight
        d[o + 6] += vx; d[o + 7] += vy; d[o + 8] += vz
        d[o + 9] += r * weight; d[o + 10] += g * weight; d[o + 11] += b * weight
        d[o + 12] += weight
        if (lastFrame[i] != frameId) {
            lastFrame[i] = frameId
            obs[i]++
        }
    }

    fun clear() {
        index.clear()
        count = 0
        isFull = false
        display?.reset()
    }

    private fun ensureCapacity(n: Int) {
        if (n <= obs.size) return
        val newSize = min(capacity, max(n, obs.size * 2))
        data = data.copyOf(newSize * STRIDE)
        obs = obs.copyOf(newSize)
        lastFrame = lastFrame.copyOf(newSize)
    }

    /**
     * Produces the averaged point cloud. Normals fall back to the mean viewing direction when the
     * accumulated normal is too weak; the result is always oriented towards the cameras.
     */
    fun toPointCloud(minObservations: Int = 1): PointCloud {
        val n = count
        var kept = 0
        for (i in 0 until n) if (obs[i] >= minObservations && data[i * STRIDE + 12] > 0f) kept++
        val p = FloatArray(kept * 3)
        val nr = FloatArray(kept * 3)
        val c = ByteArray(kept * 3)
        val o = IntArray(kept)
        var j = 0
        for (i in 0 until n) {
            val b = i * STRIDE
            val w = data[b + 12]
            if (obs[i] < minObservations || w <= 0f) continue
            val inv = 1f / w
            p[3 * j] = data[b] * inv
            p[3 * j + 1] = data[b + 1] * inv
            p[3 * j + 2] = data[b + 2] * inv
            // Mean view direction.
            var vx = data[b + 6]; var vy = data[b + 7]; var vz = data[b + 8]
            val vl = sqrt(vx * vx + vy * vy + vz * vz)
            if (vl > 1e-6f) { vx /= vl; vy /= vl; vz /= vl }
            var nx = data[b + 3]; var ny = data[b + 4]; var nz = data[b + 5]
            val nl = sqrt(nx * nx + ny * ny + nz * nz)
            if (nl > 0.3f * w) {
                nx /= nl; ny /= nl; nz /= nl
                if (nx * vx + ny * vy + nz * vz < 0f) { nx = -nx; ny = -ny; nz = -nz }
            } else {
                nx = vx; ny = vy; nz = vz
            }
            nr[3 * j] = nx; nr[3 * j + 1] = ny; nr[3 * j + 2] = nz
            c[3 * j] = (data[b + 9] * inv + 0.5f).toInt().coerceIn(0, 255).toByte()
            c[3 * j + 1] = (data[b + 10] * inv + 0.5f).toInt().coerceIn(0, 255).toByte()
            c[3 * j + 2] = (data[b + 11] * inv + 0.5f).toInt().coerceIn(0, 255).toByte()
            o[j] = obs[i]
            j++
        }
        return PointCloud(p, nr, c, o, voxelSize)
    }

    private companion object {
        // pos(3) normal(3) view(3) color(3) weight(1)
        const val STRIDE = 13
        const val INITIAL = 1 shl 16
    }
}
