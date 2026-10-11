package com.scanner3d.io

import com.scanner3d.core.Mesh
import com.scanner3d.core.PointCloud
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class ExportersTest {

    private val tetra = Mesh(
        positions = floatArrayOf(0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f),
        normals = floatArrayOf(0f, 0f, -1f, 1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f),
        colors = byteArrayOf(-1, 0, 0, 0, -1, 0, 0, 0, -1, 10, 20, 30),
        indices = intArrayOf(0, 2, 1, 0, 1, 3, 0, 3, 2, 1, 2, 3),
    )

    private val cloud = PointCloud(
        positions = floatArrayOf(0.1f, 0.2f, 0.3f, -1f, -2f, -3f),
        normals = floatArrayOf(0f, 1f, 0f, 1f, 0f, 0f),
        colors = byteArrayOf(1, 2, 3, 4, 5, 6),
        observations = intArrayOf(2, 5),
        voxelSize = 0.01f,
    )

    @Test
    fun glbIsWellFormed() {
        val out = ByteArrayOutputStream()
        Exporters.export(ExportFormat.GLB, tetra, null, out)
        val bytes = out.toByteArray()
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(0x46546C67, b.getInt(0))
        assertEquals(2, b.getInt(4))
        assertEquals(bytes.size, b.getInt(8))
        val jsonLen = b.getInt(12)
        assertEquals(0x4E4F534A, b.getInt(16))
        assertEquals(0, jsonLen % 4)
        val json = String(bytes, 20, jsonLen, Charsets.UTF_8)
        assertTrue(json.contains("\"POSITION\":0"))
        assertTrue(json.contains("\"indices\""))
        val binLen = b.getInt(20 + jsonLen)
        assertEquals(0x004E4942, b.getInt(24 + jsonLen))
        assertEquals(bytes.size, 28 + jsonLen + binLen)
        // pos(48) + nrm(48) + col(48) + idx(48)
        assertEquals(192, binLen)
        // First index value is at the end of the bin chunk.
        assertEquals(0, b.getInt(28 + jsonLen + 144))
        assertEquals(2, b.getInt(28 + jsonLen + 148))
    }

    @Test
    fun glbPointCloudUsesPointsMode() {
        val out = ByteArrayOutputStream()
        Exporters.export(ExportFormat.GLB, null, cloud, out)
        val bytes = out.toByteArray()
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val json = String(bytes, 20, b.getInt(12), Charsets.UTF_8)
        assertTrue(json.contains("\"mode\":0"))
        assertTrue(!json.contains("\"indices\""))
    }

    @Test
    fun plyMeshHeaderAndSize() {
        val out = ByteArrayOutputStream()
        Exporters.export(ExportFormat.PLY, tetra, null, out)
        val bytes = out.toByteArray()
        val text = String(bytes, Charsets.ISO_8859_1)
        val headerEnd = text.indexOf("end_header\n") + "end_header\n".length
        assertTrue(text.contains("element vertex 4"))
        assertTrue(text.contains("element face 4"))
        assertEquals(headerEnd + 4 * 27 + 4 * 13, bytes.size)
    }

    @Test
    fun objHasAllElements() {
        val out = ByteArrayOutputStream()
        Exporters.export(ExportFormat.OBJ, tetra, null, out)
        val lines = out.toString(Charsets.US_ASCII.name()).lines()
        assertEquals(4, lines.count { it.startsWith("v ") })
        assertEquals(4, lines.count { it.startsWith("vn ") })
        assertEquals(4, lines.count { it.startsWith("f ") })
        assertTrue(lines.contains("f 1//1 3//3 2//2"))
        assertTrue(lines.first { it.startsWith("v ") }.endsWith("1.0000 0.0000 0.0000"))
    }

    @Test
    fun fixedFormatting() {
        fun f(v: Float, d: Int) = Exporters.fixed(StringBuilder(), v, d).toString()
        assertEquals("0.00000", f(0f, 5))
        assertEquals("-1.25000", f(-1.25f, 5))
        assertEquals("0.0001", f(0.0001f, 4))
        assertEquals("-0.0500", f(-0.05f, 4))
        assertEquals("12.346", f(12.3456f, 3))
    }

    @Test
    fun binaryStoreRoundTrip() {
        val dir = File(System.getProperty("java.io.tmpdir"), "s3d-test-${System.nanoTime()}").apply { mkdirs() }
        try {
            val cf = File(dir, "c.bin")
            BinaryStore.writeCloud(cf, cloud)
            val c2 = BinaryStore.readCloud(cf)
            assertArrayEquals(cloud.positions, c2.positions, 0f)
            assertArrayEquals(cloud.normals, c2.normals, 0f)
            assertArrayEquals(cloud.colors, c2.colors)
            assertArrayEquals(cloud.observations, c2.observations)
            assertEquals(cloud.voxelSize, c2.voxelSize, 0f)

            val mf = File(dir, "m.bin")
            BinaryStore.writeMesh(mf, tetra)
            val m2 = BinaryStore.readMesh(mf)
            assertArrayEquals(tetra.positions, m2.positions, 0f)
            assertArrayEquals(tetra.normals, m2.normals, 0f)
            assertArrayEquals(tetra.colors, m2.colors)
            assertArrayEquals(tetra.indices, m2.indices)
        } finally {
            dir.deleteRecursively()
        }
    }
}
