package com.scanner3d.core

import java.util.concurrent.CancellationException
import java.util.stream.IntStream
import kotlin.math.ceil
import kotlin.math.floor

/** Uniform grid over a set of points for fixed-radius neighbour queries. */
class SpatialGrid(
    private val positions: FloatArray,
    private val count: Int,
    val cellSize: Float,
) {
    private val inv = 1f / cellSize
    private val cellOf = LongIntHashMap(maxOf(16, count / 4))
    private val starts: IntArray
    private val sorted: IntArray

    init {
        val cellIdx = IntArray(count)
        var cells = 0
        var counts = IntArray(1024)
        for (i in 0 until count) {
            val key = keyOf(positions[3 * i], positions[3 * i + 1], positions[3 * i + 2])
            var c = cellOf.get(key)
            if (c < 0) {
                c = cells++
                cellOf.put(key, c)
                if (c >= counts.size) counts = counts.copyOf(counts.size * 2)
            }
            counts[c]++
            cellIdx[i] = c
        }
        starts = IntArray(cells + 1)
        for (c in 0 until cells) starts[c + 1] = starts[c] + counts[c]
        val fill = starts.copyOf(cells)
        sorted = IntArray(count)
        for (i in 0 until count) sorted[fill[cellIdx[i]]++] = i
    }

    private fun keyOf(x: Float, y: Float, z: Float): Long =
        GridKey.pack(floor(x * inv).toInt(), floor(y * inv).toInt(), floor(z * inv).toInt())

    /** Calls [action] with every point index within [radius] of (x, y, z) and its squared distance. */
    inline fun forEachWithin(x: Float, y: Float, z: Float, radius: Float, action: (Int, Float) -> Unit) {
        val r2 = radius * radius
        val reach = ceil(radius / cellSize).toInt()
        val cx = floor(x / cellSize).toInt()
        val cy = floor(y / cellSize).toInt()
        val cz = floor(z / cellSize).toInt()
        val pos = positionsRef()
        val st = startsRef()
        val so = sortedRef()
        for (dz in -reach..reach) for (dy in -reach..reach) for (dx in -reach..reach) {
            val c = cellIndex(GridKey.pack(cx + dx, cy + dy, cz + dz))
            if (c < 0) continue
            for (k in st[c] until st[c + 1]) {
                val i = so[k]
                val ex = pos[3 * i] - x
                val ey = pos[3 * i + 1] - y
                val ez = pos[3 * i + 2] - z
                val d2 = ex * ex + ey * ey + ez * ez
                if (d2 <= r2) action(i, d2)
            }
        }
    }

    /** Index of the nearest point within [maxRadius], or -1. */
    fun nearest(x: Float, y: Float, z: Float, maxRadius: Float, exclude: Int = -1): Int {
        var best = -1
        var bestD = Float.MAX_VALUE
        forEachWithin(x, y, z, maxRadius) { i, d2 ->
            if (i != exclude && d2 < bestD) { bestD = d2; best = i }
        }
        return best
    }

    @PublishedApi internal fun cellIndex(key: Long): Int = cellOf.get(key)
    @PublishedApi internal fun positionsRef(): FloatArray = positions
    @PublishedApi internal fun startsRef(): IntArray = starts
    @PublishedApi internal fun sortedRef(): IntArray = sorted
}

/** Cooperative cancellation + progress reporting for long computations. */
interface ProgressSink {
    fun onProgress(stage: Stage, fraction: Float)
    fun isCancelled(): Boolean = false

    enum class Stage { PREPARE, NORMALS, FIELD, MESH, CLEANUP, COLOR }

    companion object {
        val NONE = object : ProgressSink {
            override fun onProgress(stage: Stage, fraction: Float) = Unit
        }
    }
}

internal fun ProgressSink.checkCancelled() {
    if (isCancelled()) throw CancellationException("Cancelled")
}

/** Runs [body] over [0, n) in parallel chunks on the common fork-join pool. */
internal inline fun parallelFor(
    n: Int,
    chunk: Int = 2048,
    crossinline body: (start: Int, end: Int) -> Unit,
) {
    if (n <= 0) return
    val chunks = (n + chunk - 1) / chunk
    if (chunks == 1) {
        body(0, n)
        return
    }
    IntStream.range(0, chunks).parallel().forEach { c ->
        val s = c * chunk
        body(s, minOf(n, s + chunk))
    }
}
