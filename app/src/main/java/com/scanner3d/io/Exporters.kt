package com.scanner3d.io

import com.scanner3d.core.Bounds
import com.scanner3d.core.Mesh
import com.scanner3d.core.PointCloud
import java.io.BufferedOutputStream
import java.io.OutputStream
import java.io.Writer
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.pow

enum class ExportFormat(val extension: String, val mimeType: String) {
    GLB("glb", "model/gltf-binary"),
    PLY("ply", "application/octet-stream"),
    OBJ("obj", "text/plain"),
}

object Exporters {

    fun export(format: ExportFormat, mesh: Mesh?, cloud: PointCloud?, out: OutputStream) {
        val stream = BufferedOutputStream(out, 1 shl 16)
        when (format) {
            ExportFormat.PLY -> if (mesh != null) writePlyMesh(mesh, stream) else writePlyCloud(cloud!!, stream)
            ExportFormat.OBJ -> if (mesh != null) writeObjMesh(mesh, stream) else writeObjCloud(cloud!!, stream)
            ExportFormat.GLB -> if (mesh != null) writeGlb(mesh.positions, mesh.normals, mesh.colors, mesh.indices, stream)
            else writeGlb(cloud!!.positions, null, cloud.colors, null, stream)
        }
        stream.flush()
    }

    // ---------------------------------------------------------------- PLY

    fun writePlyCloud(cloud: PointCloud, out: OutputStream) {
        val n = cloud.size
        val header = buildString {
            append("ply\nformat binary_little_endian 1.0\n")
            append("comment Created by 3D Scanner\n")
            append("element vertex $n\n")
            append("property float x\nproperty float y\nproperty float z\n")
            append("property float nx\nproperty float ny\nproperty float nz\n")
            append("property uchar red\nproperty uchar green\nproperty uchar blue\n")
            append("end_header\n")
        }
        out.write(header.toByteArray(Charsets.US_ASCII))
        val rec = 27
        val buf = ByteBuffer.allocate(rec * 4096).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until n) {
            if (buf.remaining() < rec) flush(buf, out)
            buf.putFloat(cloud.positions[3 * i]).putFloat(cloud.positions[3 * i + 1]).putFloat(cloud.positions[3 * i + 2])
            buf.putFloat(cloud.normals[3 * i]).putFloat(cloud.normals[3 * i + 1]).putFloat(cloud.normals[3 * i + 2])
            buf.put(cloud.colors[3 * i]).put(cloud.colors[3 * i + 1]).put(cloud.colors[3 * i + 2])
        }
        flush(buf, out)
    }

    fun writePlyMesh(mesh: Mesh, out: OutputStream) {
        val vc = mesh.vertexCount
        val tc = mesh.triangleCount
        val header = buildString {
            append("ply\nformat binary_little_endian 1.0\n")
            append("comment Created by 3D Scanner\n")
            append("element vertex $vc\n")
            append("property float x\nproperty float y\nproperty float z\n")
            append("property float nx\nproperty float ny\nproperty float nz\n")
            append("property uchar red\nproperty uchar green\nproperty uchar blue\n")
            append("element face $tc\n")
            append("property list uchar int vertex_indices\n")
            append("end_header\n")
        }
        out.write(header.toByteArray(Charsets.US_ASCII))
        val buf = ByteBuffer.allocate(1 shl 16).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until vc) {
            if (buf.remaining() < 27) flush(buf, out)
            buf.putFloat(mesh.positions[3 * i]).putFloat(mesh.positions[3 * i + 1]).putFloat(mesh.positions[3 * i + 2])
            buf.putFloat(mesh.normals[3 * i]).putFloat(mesh.normals[3 * i + 1]).putFloat(mesh.normals[3 * i + 2])
            buf.put(mesh.colors[3 * i]).put(mesh.colors[3 * i + 1]).put(mesh.colors[3 * i + 2])
        }
        for (t in 0 until tc) {
            if (buf.remaining() < 13) flush(buf, out)
            buf.put(3.toByte())
            buf.putInt(mesh.indices[3 * t]).putInt(mesh.indices[3 * t + 1]).putInt(mesh.indices[3 * t + 2])
        }
        flush(buf, out)
    }

    private fun flush(buf: ByteBuffer, out: OutputStream) {
        out.write(buf.array(), 0, buf.position())
        buf.clear()
    }

    // ---------------------------------------------------------------- OBJ

    fun writeObjMesh(mesh: Mesh, out: OutputStream) {
        val w = out.bufferedWriter(Charsets.US_ASCII)
        w.write("# 3D Scanner mesh: ${mesh.vertexCount} vertices, ${mesh.triangleCount} triangles\n")
        val sb = StringBuilder(128)
        for (i in 0 until mesh.vertexCount) {
            sb.setLength(0)
            sb.append("v ")
            fixed(sb, mesh.positions[3 * i], 5).append(' ')
            fixed(sb, mesh.positions[3 * i + 1], 5).append(' ')
            fixed(sb, mesh.positions[3 * i + 2], 5).append(' ')
            fixed(sb, (mesh.colors[3 * i].toInt() and 0xFF) / 255f, 4).append(' ')
            fixed(sb, (mesh.colors[3 * i + 1].toInt() and 0xFF) / 255f, 4).append(' ')
            fixed(sb, (mesh.colors[3 * i + 2].toInt() and 0xFF) / 255f, 4).append('\n')
            w.append(sb)
        }
        for (i in 0 until mesh.vertexCount) {
            sb.setLength(0)
            sb.append("vn ")
            fixed(sb, mesh.normals[3 * i], 4).append(' ')
            fixed(sb, mesh.normals[3 * i + 1], 4).append(' ')
            fixed(sb, mesh.normals[3 * i + 2], 4).append('\n')
            w.append(sb)
        }
        for (t in 0 until mesh.triangleCount) {
            val a = mesh.indices[3 * t] + 1
            val b = mesh.indices[3 * t + 1] + 1
            val c = mesh.indices[3 * t + 2] + 1
            sb.setLength(0)
            sb.append("f ").append(a).append("//").append(a).append(' ')
                .append(b).append("//").append(b).append(' ')
                .append(c).append("//").append(c).append('\n')
            w.append(sb)
        }
        w.flush()
    }

    fun writeObjCloud(cloud: PointCloud, out: OutputStream) {
        val w: Writer = out.bufferedWriter(Charsets.US_ASCII)
        w.write("# 3D Scanner point cloud: ${cloud.size} points\n")
        val sb = StringBuilder(128)
        for (i in 0 until cloud.size) {
            sb.setLength(0)
            sb.append("v ")
            fixed(sb, cloud.positions[3 * i], 5).append(' ')
            fixed(sb, cloud.positions[3 * i + 1], 5).append(' ')
            fixed(sb, cloud.positions[3 * i + 2], 5).append(' ')
            fixed(sb, (cloud.colors[3 * i].toInt() and 0xFF) / 255f, 4).append(' ')
            fixed(sb, (cloud.colors[3 * i + 1].toInt() and 0xFF) / 255f, 4).append(' ')
            fixed(sb, (cloud.colors[3 * i + 2].toInt() and 0xFF) / 255f, 4).append('\n')
            w.append(sb)
        }
        w.flush()
    }

    /** Appends [value] with exactly [decimals] fractional digits, without scientific notation. */
    internal fun fixed(sb: StringBuilder, value: Float, decimals: Int): StringBuilder {
        var scale = 1L
        repeat(decimals) { scale *= 10 }
        var v = Math.round(value.toDouble() * scale)
        if (v < 0) { sb.append('-'); v = -v }
        val intPart = v / scale
        val frac = v % scale
        sb.append(intPart).append('.')
        val fracStr = frac.toString()
        repeat(decimals - fracStr.length) { sb.append('0') }
        sb.append(fracStr)
        return sb
    }

    // ---------------------------------------------------------------- glTF binary

    /**
     * Writes a GLB with POSITION, optional NORMAL, COLOR_0 (linear float RGB) and optional
     * triangle indices. Without indices the primitive is a point list.
     */
    fun writeGlb(
        positions: FloatArray,
        normals: FloatArray?,
        colors: ByteArray,
        indices: IntArray?,
        out: OutputStream,
    ) {
        val vc = positions.size / 3
        val bounds = Bounds.of(positions, vc)
        val posBytes = vc * 12
        val nrmBytes = if (normals != null) vc * 12 else 0
        val colBytes = vc * 12
        val idxBytes = (indices?.size ?: 0) * 4
        val binLength = posBytes + nrmBytes + colBytes + idxBytes

        val bin = ByteBuffer.allocate(pad4(binLength)).order(ByteOrder.LITTLE_ENDIAN)
        for (f in positions) bin.putFloat(f)
        normals?.forEach { bin.putFloat(it) }
        val lut = FloatArray(256) { srgbToLinear(it / 255f) }
        for (i in 0 until vc * 3) bin.putFloat(lut[colors[i].toInt() and 0xFF])
        indices?.forEach { bin.putInt(it) }
        while (bin.hasRemaining()) bin.put(0)

        val views = ArrayList<String>()
        val accessors = ArrayList<String>()
        var offset = 0
        fun view(length: Int, target: Int): Int {
            views.add("""{"buffer":0,"byteOffset":$offset,"byteLength":$length,"target":$target}""")
            offset += length
            return views.size - 1
        }
        val posView = view(posBytes, 34962)
        accessors.add(
            """{"bufferView":$posView,"componentType":5126,"count":$vc,"type":"VEC3",""" +
                """"min":[${num(bounds.minX)},${num(bounds.minY)},${num(bounds.minZ)}],""" +
                """"max":[${num(bounds.maxX)},${num(bounds.maxY)},${num(bounds.maxZ)}]}""",
        )
        val posAcc = 0
        var nrmAcc = -1
        if (normals != null) {
            val v = view(nrmBytes, 34962)
            accessors.add("""{"bufferView":$v,"componentType":5126,"count":$vc,"type":"VEC3"}""")
            nrmAcc = accessors.size - 1
        }
        val colView = view(colBytes, 34962)
        accessors.add("""{"bufferView":$colView,"componentType":5126,"count":$vc,"type":"VEC3"}""")
        val colAcc = accessors.size - 1
        var idxAcc = -1
        if (indices != null) {
            val v = view(idxBytes, 34963)
            accessors.add("""{"bufferView":$v,"componentType":5125,"count":${indices.size},"type":"SCALAR"}""")
            idxAcc = accessors.size - 1
        }
        val attributes = buildString {
            append(""""POSITION":$posAcc""")
            if (nrmAcc >= 0) append(""","NORMAL":$nrmAcc""")
            append(""","COLOR_0":$colAcc""")
        }
        val primitive = buildString {
            append("""{"attributes":{$attributes}""")
            if (idxAcc >= 0) append(""","indices":$idxAcc,"mode":4""") else append(""","mode":0""")
            append(""","material":0}""")
        }
        val json = """{"asset":{"version":"2.0","generator":"3D Scanner"},""" +
            """"scene":0,"scenes":[{"nodes":[0]}],"nodes":[{"mesh":0,"name":"scan"}],""" +
            """"meshes":[{"name":"scan","primitives":[$primitive]}],""" +
            """"materials":[{"name":"scan","doubleSided":true,"pbrMetallicRoughness":""" +
            """{"baseColorFactor":[1,1,1,1],"metallicFactor":0,"roughnessFactor":1}}],""" +
            """"buffers":[{"byteLength":${bin.capacity()}}],""" +
            """"bufferViews":[${views.joinToString(",")}],""" +
            """"accessors":[${accessors.joinToString(",")}]}"""
        val jsonBytes = json.toByteArray(Charsets.UTF_8)
        val jsonLen = pad4(jsonBytes.size)
        val total = 12 + 8 + jsonLen + 8 + bin.capacity()

        val head = ByteBuffer.allocate(12 + 8).order(ByteOrder.LITTLE_ENDIAN)
        head.putInt(0x46546C67) // "glTF"
        head.putInt(2)
        head.putInt(total)
        head.putInt(jsonLen)
        head.putInt(0x4E4F534A) // "JSON"
        out.write(head.array())
        out.write(jsonBytes)
        repeat(jsonLen - jsonBytes.size) { out.write(' '.code) }
        val binHead = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
        binHead.putInt(bin.capacity())
        binHead.putInt(0x004E4942) // "BIN\0"
        out.write(binHead.array())
        out.write(bin.array())
    }

    private fun pad4(n: Int) = (n + 3) and 3.inv()

    private fun num(f: Float): String = if (f.isFinite()) f.toString() else "0"

    private fun srgbToLinear(c: Float): Float =
        if (c <= 0.04045f) c / 12.92f else ((c + 0.055f) / 1.055f).toDouble().pow(2.4).toFloat()
}
