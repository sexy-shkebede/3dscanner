package com.scanner3d.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class ReconstructionTest {

    private fun sphereCloud(n: Int, r: Float, noise: Float, voxel: Float, flipNormals: Boolean = false): PointCloud {
        val rnd = Random(42)
        val p = FloatArray(n * 3)
        val nr = FloatArray(n * 3)
        val c = ByteArray(n * 3)
        val o = IntArray(n) { 3 }
        val golden = Math.PI * (3 - sqrt(5.0))
        for (i in 0 until n) {
            val y = 1 - (i + 0.5) / n * 2
            val rad = sqrt(1 - y * y)
            val th = golden * i
            val x = cos(th) * rad
            val z = sin(th) * rad
            val e = 1f + (rnd.nextGaussian() * noise).toFloat() / r
            p[3 * i] = (x * r * e).toFloat(); p[3 * i + 1] = (y * r * e).toFloat(); p[3 * i + 2] = (z * r * e).toFloat()
            // Hint normals: roughly outward with jitter, as the depth integrator would produce.
            val s = if (flipNormals) -1f else 1f
            var hx = x.toFloat() + 0.2f * rnd.nextGaussian().toFloat()
            var hy = y.toFloat() + 0.2f * rnd.nextGaussian().toFloat()
            var hz = z.toFloat() + 0.2f * rnd.nextGaussian().toFloat()
            val l = sqrt(hx * hx + hy * hy + hz * hz)
            hx /= l; hy /= l; hz /= l
            nr[3 * i] = s * hx; nr[3 * i + 1] = s * hy; nr[3 * i + 2] = s * hz
            c[3 * i] = (if (y > 0) 220 else 30).toByte(); c[3 * i + 1] = 90; c[3 * i + 2] = 40
        }
        return PointCloud(p, nr, c, o, voxel)
    }

    @Test
    fun sphereProducesClosedOutwardMesh() {
        val r = 0.2f
        val cloud = sphereCloud(30000, r, 0.001f, 0.005f)
        val mesh = SurfaceReconstructor().reconstruct(cloud, ReconstructionParams(cellSize = 0.008f, smoothingIterations = 3))
        assertTrue("mesh should have triangles", mesh.triangleCount > 1000)

        // Vertices lie on the sphere.
        var maxErr = 0f
        var outward = 0
        for (v in 0 until mesh.vertexCount) {
            val x = mesh.positions[3 * v]; val y = mesh.positions[3 * v + 1]; val z = mesh.positions[3 * v + 2]
            val d = sqrt(x * x + y * y + z * z)
            maxErr = maxOf(maxErr, abs(d - r))
            val dot = (x * mesh.normals[3 * v] + y * mesh.normals[3 * v + 1] + z * mesh.normals[3 * v + 2]) / d
            if (dot > 0.5f) outward++
        }
        assertTrue("max radial error $maxErr", maxErr < 0.012f)
        assertTrue("normals should point outward: $outward / ${mesh.vertexCount}", outward > mesh.vertexCount * 0.97)

        // Triangle winding agrees with normals (outward).
        var agree = 0
        for (t in 0 until mesh.triangleCount) {
            val a = mesh.indices[3 * t]; val b = mesh.indices[3 * t + 1]; val c = mesh.indices[3 * t + 2]
            val p = mesh.positions
            val ux = p[3 * b] - p[3 * a]; val uy = p[3 * b + 1] - p[3 * a + 1]; val uz = p[3 * b + 2] - p[3 * a + 2]
            val vx = p[3 * c] - p[3 * a]; val vy = p[3 * c + 1] - p[3 * a + 1]; val vz = p[3 * c + 2] - p[3 * a + 2]
            val nx = uy * vz - uz * vy; val ny = uz * vx - ux * vz; val nz = ux * vy - uy * vx
            val cx = p[3 * a] + p[3 * b] + p[3 * c]; val cy = p[3 * a + 1] + p[3 * b + 1] + p[3 * c + 1]; val cz = p[3 * a + 2] + p[3 * b + 2] + p[3 * c + 2]
            if (nx * cx + ny * cy + nz * cz > 0) agree++
        }
        assertTrue("winding outward: $agree / ${mesh.triangleCount}", agree > mesh.triangleCount * 0.98)

        // Closed surface: every edge shared by exactly two triangles (allow tiny non-manifold count).
        val edges = HashMap<Long, Int>()
        for (t in 0 until mesh.triangleCount) for (k in 0 until 3) {
            val a = mesh.indices[3 * t + k]; val b = mesh.indices[3 * t + (k + 1) % 3]
            val key = minOf(a, b).toLong() shl 32 or maxOf(a, b).toLong()
            edges[key] = (edges[key] ?: 0) + 1
        }
        val boundary = edges.values.count { it == 1 }
        assertTrue("boundary edges: $boundary of ${edges.size}", boundary < edges.size * 0.002)

        // Colours transferred: top red, bottom dark.
        var topRed = 0; var top = 0
        for (v in 0 until mesh.vertexCount) if (mesh.positions[3 * v + 1] > 0.1f) {
            top++
            if ((mesh.colors[3 * v].toInt() and 0xFF) > 180) topRed++
        }
        assertTrue(topRed > top * 0.9)
    }

    @Test
    fun noisyOpenPlaneStaysFlat() {
        val rnd = Random(7)
        val n = 40000
        val p = FloatArray(n * 3)
        val nr = FloatArray(n * 3)
        for (i in 0 until n) {
            p[3 * i] = rnd.nextFloat() - 0.5f
            p[3 * i + 1] = (rnd.nextGaussian() * 0.002).toFloat()
            p[3 * i + 2] = rnd.nextFloat() - 0.5f
            nr[3 * i + 1] = 1f
        }
        val cloud = PointCloud(p, nr, ByteArray(n * 3) { 100 }, IntArray(n) { 2 }, 0.01f)
        val mesh = SurfaceReconstructor().reconstruct(cloud, ReconstructionParams(cellSize = 0.015f))
        assertTrue(mesh.triangleCount > 1000)
        var up = 0
        for (v in 0 until mesh.vertexCount) {
            assertTrue(abs(mesh.positions[3 * v + 1]) < 0.01f)
            if (mesh.normals[3 * v + 1] > 0.9f) up++
        }
        assertTrue(up > mesh.vertexCount * 0.95)
        val b = mesh.bounds()
        assertTrue(b.sizeX > 0.85f && b.sizeZ > 0.85f)
    }

    @Test
    fun sparseCloudWithoutNormalsStillMeshes() {
        // Feature-point style: few points, normals come from viewing direction only (towards +z).
        val rnd = Random(3)
        val n = 3000
        val p = FloatArray(n * 3)
        val nr = FloatArray(n * 3)
        for (i in 0 until n) {
            p[3 * i] = rnd.nextFloat() - 0.5f
            p[3 * i + 1] = rnd.nextFloat() - 0.5f
            p[3 * i + 2] = (rnd.nextGaussian() * 0.003).toFloat()
            val vx = -p[3 * i]; val vy = -p[3 * i + 1]; val vz = 1.5f - p[3 * i + 2]
            val l = sqrt(vx * vx + vy * vy + vz * vz)
            nr[3 * i] = vx / l; nr[3 * i + 1] = vy / l; nr[3 * i + 2] = vz / l
        }
        val cloud = PointCloud(p, nr, ByteArray(n * 3) { 50 }, IntArray(n) { 1 }, 0.01f)
        val mesh = SurfaceReconstructor().reconstruct(cloud, ReconstructionParams(cellSize = 0.01f, minObservations = 1))
        assertTrue("triangles: ${mesh.triangleCount}", mesh.triangleCount > 100)
        var facing = 0
        for (v in 0 until mesh.vertexCount) if (mesh.normals[3 * v + 2] > 0.8f) facing++
        assertTrue(facing > mesh.vertexCount * 0.9)
    }

    @Test
    fun tooFewPointsGiveEmptyMesh() {
        val cloud = sphereCloud(10, 0.1f, 0f, 0.01f)
        assertEquals(0, SurfaceReconstructor().reconstruct(cloud, ReconstructionParams(cellSize = 0.01f)).triangleCount)
    }

    @Test(expected = java.util.concurrent.CancellationException::class)
    fun cancellationStopsWork() {
        val cloud = sphereCloud(20000, 0.2f, 0.001f, 0.005f)
        SurfaceReconstructor().reconstruct(cloud, ReconstructionParams(cellSize = 0.006f), object : ProgressSink {
            override fun onProgress(stage: ProgressSink.Stage, fraction: Float) = Unit
            override fun isCancelled() = true
        })
    }

    @Test
    fun eigenvectorOfSmallestEigenvalue() {
        // Covariance of points spread in x and y: normal must be z.
        val out = DoubleArray(3)
        NormalEstimator.smallestEigenvector(doubleArrayOf(4.0, 1.0, 0.0, 3.0, 0.0, 0.01), out)
        assertEquals(1.0, abs(out[2]), 1e-6)
        // Rotated plane with normal (1,1,1)/sqrt(3).
        val nx = 1 / sqrt(3.0)
        // C = I - n n^T has eigenvalue 0 along n.
        NormalEstimator.smallestEigenvector(
            doubleArrayOf(1 - nx * nx, -nx * nx, -nx * nx, 1 - nx * nx, -nx * nx, 1 - nx * nx), out,
        )
        val angle = acos(minOf(1.0, abs(out[0] * nx + out[1] * nx + out[2] * nx)))
        assertTrue(angle < 1e-4)
    }
}
