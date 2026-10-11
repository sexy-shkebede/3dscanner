package com.scanner3d.core

import org.junit.Test
import java.util.Random

/** Manual benchmark: run with PERF=1 ./gradlew testDebugUnitTest --tests "*PerfProbe*" -i */
class PerfProbe {
    @Test
    fun largeRoomCloud() {
        if (System.getenv("PERF") == null) return
        // Box-shaped room 4 x 2.5 x 3 m sampled at ~1 cm, seen from inside.
        val rnd = Random(1)
        val n = 400_000
        val p = FloatArray(n * 3); val nr = FloatArray(n * 3)
        for (i in 0 until n) {
            val face = rnd.nextInt(6)
            val u = rnd.nextFloat(); val v = rnd.nextFloat()
            var x = u * 4f; var y = v * 2.5f; var z = rnd.nextFloat() * 3f
            var nx = 0f; var ny = 0f; var nz = 0f
            when (face) {
                0 -> { x = 0f; nx = 1f } 1 -> { x = 4f; nx = -1f }
                2 -> { y = 0f; ny = 1f } 3 -> { y = 2.5f; ny = -1f }
                4 -> { z = 0f; nz = 1f } else -> { z = 3f; nz = -1f }
            }
            p[3 * i] = x + (rnd.nextGaussian() * 0.003).toFloat()
            p[3 * i + 1] = y + (rnd.nextGaussian() * 0.003).toFloat()
            p[3 * i + 2] = z + (rnd.nextGaussian() * 0.003).toFloat()
            nr[3 * i] = nx; nr[3 * i + 1] = ny; nr[3 * i + 2] = nz
        }
        val cloud = PointCloud(p, nr, ByteArray(n * 3) { 90 }, IntArray(n) { 3 }, 0.01f)
        val t0 = System.nanoTime()
        var last = ""
        val mesh = SurfaceReconstructor().reconstruct(cloud, ReconstructionParams(cellSize = 0.015f), object : ProgressSink {
            override fun onProgress(stage: ProgressSink.Stage, fraction: Float) {
                if (stage.name != last) { last = stage.name; println("  ${stage} at ${(System.nanoTime() - t0) / 1e6} ms") }
            }
        })
        println("PERF: ${mesh.vertexCount} verts, ${mesh.triangleCount} tris in ${(System.nanoTime() - t0) / 1e6} ms")
    }
}
