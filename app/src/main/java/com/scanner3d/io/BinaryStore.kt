package com.scanner3d.io

import com.scanner3d.core.Mesh
import com.scanner3d.core.PointCloud
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/** Compact internal binary formats for point clouds and meshes. */
object BinaryStore {
    private const val CLOUD_MAGIC = 0x43443353 // "S3DC"
    private const val MESH_MAGIC = 0x4D443353 // "S3DM"
    private const val VERSION = 1

    fun writeCloud(file: File, cloud: PointCloud) {
        val n = cloud.size
        val size = 16 + n * (12 + 12 + 3 + 4)
        val buf = ByteBuffer.allocateDirect(size).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(CLOUD_MAGIC).putInt(VERSION).putInt(n).putFloat(cloud.voxelSize)
        buf.asFloatBuffer().put(cloud.positions); buf.position(buf.position() + n * 12)
        buf.asFloatBuffer().put(cloud.normals); buf.position(buf.position() + n * 12)
        buf.put(cloud.colors)
        buf.asIntBuffer().put(cloud.observations); buf.position(buf.position() + n * 4)
        buf.flip()
        writeAtomically(file, buf)
    }

    fun readCloud(file: File): PointCloud {
        val buf = map(file)
        if (buf.int != CLOUD_MAGIC) throw IOException("Not a point cloud file")
        buf.int // version
        val n = buf.int
        val voxel = buf.float
        val p = FloatArray(n * 3); buf.asFloatBuffer().get(p); buf.position(buf.position() + n * 12)
        val nr = FloatArray(n * 3); buf.asFloatBuffer().get(nr); buf.position(buf.position() + n * 12)
        val c = ByteArray(n * 3); buf.get(c)
        val o = IntArray(n); buf.asIntBuffer().get(o)
        return PointCloud(p, nr, c, o, voxel)
    }

    fun writeMesh(file: File, mesh: Mesh) {
        val vc = mesh.vertexCount
        val ic = mesh.indices.size
        val size = 16 + vc * (12 + 12 + 3) + ic * 4
        val buf = ByteBuffer.allocateDirect(size).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(MESH_MAGIC).putInt(VERSION).putInt(vc).putInt(ic)
        buf.asFloatBuffer().put(mesh.positions); buf.position(buf.position() + vc * 12)
        buf.asFloatBuffer().put(mesh.normals); buf.position(buf.position() + vc * 12)
        buf.put(mesh.colors)
        buf.asIntBuffer().put(mesh.indices); buf.position(buf.position() + ic * 4)
        buf.flip()
        writeAtomically(file, buf)
    }

    fun readMesh(file: File): Mesh {
        val buf = map(file)
        if (buf.int != MESH_MAGIC) throw IOException("Not a mesh file")
        buf.int
        val vc = buf.int
        val ic = buf.int
        val p = FloatArray(vc * 3); buf.asFloatBuffer().get(p); buf.position(buf.position() + vc * 12)
        val nr = FloatArray(vc * 3); buf.asFloatBuffer().get(nr); buf.position(buf.position() + vc * 12)
        val c = ByteArray(vc * 3); buf.get(c)
        val idx = IntArray(ic); buf.asIntBuffer().get(idx)
        return Mesh(p, nr, c, idx)
    }

    private fun map(file: File): ByteBuffer {
        RandomAccessFile(file, "r").use { raf ->
            val ch = raf.channel
            val buf = ByteBuffer.allocateDirect(ch.size().toInt()).order(ByteOrder.LITTLE_ENDIAN)
            while (buf.hasRemaining()) if (ch.read(buf) < 0) break
            buf.flip()
            return buf
        }
    }

    private fun writeAtomically(file: File, buf: ByteBuffer) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        RandomAccessFile(tmp, "rw").use { raf ->
            raf.setLength(0)
            val ch: FileChannel = raf.channel
            while (buf.hasRemaining()) ch.write(buf)
            ch.force(false)
        }
        if (!tmp.renameTo(file)) {
            file.delete()
            if (!tmp.renameTo(file)) throw IOException("Cannot write ${file.name}")
        }
    }
}
