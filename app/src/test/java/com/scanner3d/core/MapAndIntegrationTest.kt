package com.scanner3d.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class MapAndIntegrationTest {

    @Test
    fun hashMapGrowsAndKeepsValues() {
        val m = LongIntHashMap(4)
        for (i in 0 until 100_000) m.put(i * 7919L - 50_000, i)
        assertEquals(100_000, m.size)
        for (i in 0 until 100_000) assertEquals(i, m.get(i * 7919L - 50_000))
        assertEquals(-1, m.get(123456789L))
        assertEquals(-1, m.putIfAbsent(5L * 1_000_003, 9))
        assertEquals(9, m.putIfAbsent(5L * 1_000_003, 11))
        var count = 0
        m.forEach { _, _ -> count++ }
        assertEquals(m.size, count)
    }

    @Test
    fun gridKeyRoundTrip() {
        for ((x, y, z) in listOf(Triple(0, 0, 0), Triple(-1, 2, -3), Triple(1_000_000, -1_000_000, 524_287))) {
            val k = GridKey.pack(x, y, z)
            assertEquals(x, GridKey.x(k)); assertEquals(y, GridKey.y(k)); assertEquals(z, GridKey.z(k))
        }
        assertFalse(GridKey.pack(1, 0, 0) == GridKey.pack(0, 1, 0))
    }

    @Test
    fun pointMapFusesVoxelsAndCountsFrames() {
        val display = DisplayBuffer(100)
        val map = PointMap(0.01f, 100, display)
        repeat(3) {
            map.beginFrame()
            map.add(0.001f, 0.002f, 0.003f, 0f, 1f, 0f, 0f, 1f, 0f, 255f, 0f, 0f, 1f)
            map.add(0.004f, 0.002f, 0.003f, 0f, 1f, 0f, 0f, 1f, 0f, 255f, 0f, 0f, 1f) // same voxel
        }
        map.beginFrame()
        map.add(0.5f, 0.5f, 0.5f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 255f, 0f, 1f)
        assertEquals(2, map.count)
        assertEquals(2, display.published)
        val cloud = map.toPointCloud()
        assertEquals(2, cloud.size)
        assertEquals(3, cloud.observations[0])
        assertEquals(0.0025f, cloud.positions[0], 1e-6f)
        assertEquals(1f, cloud.normals[1], 1e-6f)
        // Second point had no normal: falls back to view direction.
        assertEquals(1f, cloud.normals[3], 1e-6f)
        assertEquals(1, map.toPointCloud(minObservations = 2).size)
    }

    @Test
    fun pointMapStopsAtCapacity() {
        val map = PointMap(0.01f, 3)
        map.beginFrame()
        for (i in 0 until 10) map.add(i * 0.1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f, 1f, 1f)
        assertEquals(3, map.count)
        assertTrue(map.isFull)
        map.clear()
        assertEquals(0, map.count)
        assertFalse(map.isFull)
    }

    @Test
    fun depthFrameOfWallLandsOnPlane() {
        val map = PointMap(0.01f, 100_000)
        val integrator = DepthIntegrator(map, IntegrationSettings(maxDepth = 3f, minConfidence = 0.5f))
        val f = FrameData()
        val w = 160; val h = 120
        f.kind = FrameData.Kind.DEPTH
        f.depthWidth = w; f.depthHeight = h; f.depthStride = w
        f.ensureDepth(w * h)
        for (i in 0 until w * h) f.depth[i] = 1500 // 1.5 m wall facing the camera
        f.hasConfidence = true
        f.confidenceStride = w
        f.ensureConfidence(w * h)
        for (i in 0 until w * h) f.confidence[i] = 255.toByte()
        f.dfx = 120f; f.dfy = 120f; f.dcx = 80f; f.dcy = 60f
        // Camera translated to (1, 2, 3), no rotation.
        for (i in 0 until 16) f.pose[i] = 0f
        f.pose[0] = 1f; f.pose[5] = 1f; f.pose[10] = 1f; f.pose[15] = 1f
        f.pose[12] = 1f; f.pose[13] = 2f; f.pose[14] = 3f
        // Grey image.
        f.hasImage = true
        f.imageWidth = 64; f.imageHeight = 48; f.yRowStride = 64; f.uvRowStride = 64; f.uvPixelStride = 2
        f.ensureY(64 * 48); f.ensureU(64 * 24); f.ensureV(64 * 24)
        f.y.fill(128.toByte()); f.u.fill(128.toByte()); f.v.fill(128.toByte())
        f.ifx = 50f; f.ify = 50f; f.icx = 32f; f.icy = 24f

        val added = integrator.integrate(f)
        assertTrue(added > 10_000)
        val cloud = map.toPointCloud()
        assertTrue(cloud.size > 1000)
        for (i in 0 until cloud.size) {
            assertEquals(3f - 1.5f, cloud.positions[3 * i + 2], 0.002f)
            assertTrue(cloud.normals[3 * i + 2] > 0.99f)
            assertEquals(128, cloud.colors[3 * i].toInt() and 0xFF)
        }
        // Lateral extent follows the intrinsics: half width = 80 px * 1.5 m / 120 px = 1 m.
        val b = cloud.bounds()
        assertTrue("bounds $b", abs(b.centerX - 1f) < 0.05f && abs(b.sizeX - 2f) < 0.1f)
        assertTrue(abs(b.centerY - 2f) < 0.05f)
    }

    @Test
    fun depthEdgesAndLowConfidenceAreRejected() {
        val map = PointMap(0.005f, 100_000)
        val integrator = DepthIntegrator(map, IntegrationSettings(minConfidence = 0.5f))
        val f = FrameData()
        val w = 40; val h = 40
        f.depthWidth = w; f.depthHeight = h; f.depthStride = w
        f.ensureDepth(w * h)
        // Left half at 1 m, right half at 2 m: a step edge.
        for (y in 0 until h) for (x in 0 until w) f.depth[y * w + x] = if (x < 20) 1000 else 2000
        f.hasConfidence = true; f.confidenceStride = w
        f.ensureConfidence(w * h)
        for (y in 0 until h) for (x in 0 until w) f.confidence[y * w + x] = (if (y < 20) 255 else 10).toByte()
        f.dfx = 40f; f.dfy = 40f; f.dcx = 20f; f.dcy = 20f
        f.pose[0] = 1f; f.pose[5] = 1f; f.pose[10] = 1f; f.pose[15] = 1f
        integrator.integrate(f)
        val cloud = map.toPointCloud()
        for (i in 0 until cloud.size) {
            val z = -cloud.positions[3 * i + 2]
            // No flying pixels between the two depth layers.
            assertTrue(abs(z - 1f) < 0.01f || abs(z - 2f) < 0.01f)
            // Only the confident upper half survives (camera y up => image top has y > 0).
            assertTrue(cloud.positions[3 * i + 1] > 0f)
        }
    }

    @Test
    fun featurePointsAreProjectedAndColoured() {
        val map = PointMap(0.01f, 1000)
        val integrator = DepthIntegrator(map, IntegrationSettings(maxDepth = 5f))
        val f = FrameData()
        f.kind = FrameData.Kind.FEATURES
        f.pose[0] = 1f; f.pose[5] = 1f; f.pose[10] = 1f; f.pose[15] = 1f
        f.ensureFeatures(3)
        f.features[0] = 0f; f.features[1] = 0f; f.features[2] = -1f; f.features[3] = 0.9f // in front
        f.features[4] = 0f; f.features[5] = 0f; f.features[6] = 1f; f.features[7] = 0.9f // behind camera
        f.features[8] = 0.2f; f.features[9] = 0f; f.features[10] = -2f; f.features[11] = 0.01f // low confidence
        f.featureCount = 3
        assertEquals(1, integrator.integrate(f))
        val cloud = map.toPointCloud()
        assertEquals(1, cloud.size)
        // Normal hint points back to the camera.
        assertTrue(cloud.normals[2] > 0.99f)
    }

    @Test
    fun thumbnailHasPixels() {
        val n = 2000
        val p = FloatArray(n * 3) { (it % 7) * 0.01f }
        val c = ByteArray(n * 3) { 100 }
        val px = ThumbnailRenderer.render(p, c, null, 64)
        assertTrue(px.count { it != 0 } > 10)
    }
}
