package com.scanner3d.core

import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.math.sqrt

/** Normal estimation by local PCA with orientation taken from the existing (camera-facing) hint. */
object NormalEstimator {

    /**
     * Re-estimates normals in place. Points with fewer than [minNeighbors] neighbours inside
     * [radius] keep their current normal.
     */
    fun estimate(
        positions: FloatArray,
        normals: FloatArray,
        count: Int,
        radius: Float,
        grid: SpatialGrid,
        minNeighbors: Int = 6,
        progress: ProgressSink = ProgressSink.NONE,
    ) {
        val done = AtomicInteger()
        parallelFor(count, 4096) { start, end ->
            progress.checkCancelled()
            val cov = DoubleArray(6)
            val evec = DoubleArray(3)
            for (i in start until end) {
                val px = positions[3 * i]; val py = positions[3 * i + 1]; val pz = positions[3 * i + 2]
                var n = 0
                var mx = 0.0; var my = 0.0; var mz = 0.0
                grid.forEachWithin(px, py, pz, radius) { j, _ ->
                    mx += positions[3 * j]; my += positions[3 * j + 1]; mz += positions[3 * j + 2]
                    n++
                }
                if (n < minNeighbors) continue
                mx /= n; my /= n; mz /= n
                cov.fill(0.0)
                grid.forEachWithin(px, py, pz, radius) { j, _ ->
                    val dx = positions[3 * j] - mx
                    val dy = positions[3 * j + 1] - my
                    val dz = positions[3 * j + 2] - mz
                    cov[0] += dx * dx; cov[1] += dx * dy; cov[2] += dx * dz
                    cov[3] += dy * dy; cov[4] += dy * dz; cov[5] += dz * dz
                }
                smallestEigenvector(cov, evec)
                var nx = evec[0].toFloat(); var ny = evec[1].toFloat(); var nz = evec[2].toFloat()
                val l = sqrt(nx * nx + ny * ny + nz * nz)
                if (l < 1e-6f) continue
                nx /= l; ny /= l; nz /= l
                val hx = normals[3 * i]; val hy = normals[3 * i + 1]; val hz = normals[3 * i + 2]
                if (nx * hx + ny * hy + nz * hz < 0f) { nx = -nx; ny = -ny; nz = -nz }
                normals[3 * i] = nx; normals[3 * i + 1] = ny; normals[3 * i + 2] = nz
            }
            val d = done.addAndGet(end - start)
            progress.onProgress(ProgressSink.Stage.NORMALS, d.toFloat() / count)
        }
    }

    /**
     * Eigenvector of the smallest eigenvalue of a symmetric 3x3 matrix given as
     * (a00, a01, a02, a11, a12, a22), computed with cyclic Jacobi rotations.
     */
    fun smallestEigenvector(m: DoubleArray, out: DoubleArray) {
        val a = arrayOf(
            doubleArrayOf(m[0], m[1], m[2]),
            doubleArrayOf(m[1], m[3], m[4]),
            doubleArrayOf(m[2], m[4], m[5]),
        )
        val v = arrayOf(
            doubleArrayOf(1.0, 0.0, 0.0),
            doubleArrayOf(0.0, 1.0, 0.0),
            doubleArrayOf(0.0, 0.0, 1.0),
        )
        for (sweep in 0 until 12) {
            val off = abs(a[0][1]) + abs(a[0][2]) + abs(a[1][2])
            if (off < 1e-15) break
            for (p in 0 until 2) for (q in p + 1 until 3) {
                if (abs(a[p][q]) < 1e-18) continue
                val theta = (a[q][q] - a[p][p]) / (2.0 * a[p][q])
                val t = (if (theta >= 0) 1.0 else -1.0) / (abs(theta) + sqrt(theta * theta + 1.0))
                val c = 1.0 / sqrt(t * t + 1.0)
                val s = t * c
                for (k in 0 until 3) {
                    val akp = a[k][p]; val akq = a[k][q]
                    a[k][p] = c * akp - s * akq
                    a[k][q] = s * akp + c * akq
                }
                for (k in 0 until 3) {
                    val apk = a[p][k]; val aqk = a[q][k]
                    a[p][k] = c * apk - s * aqk
                    a[q][k] = s * apk + c * aqk
                }
                for (k in 0 until 3) {
                    val vkp = v[k][p]; val vkq = v[k][q]
                    v[k][p] = c * vkp - s * vkq
                    v[k][q] = s * vkp + c * vkq
                }
            }
        }
        var best = 0
        if (a[1][1] < a[best][best]) best = 1
        if (a[2][2] < a[best][best]) best = 2
        out[0] = v[0][best]; out[1] = v[1][best]; out[2] = v[2][best]
    }
}
