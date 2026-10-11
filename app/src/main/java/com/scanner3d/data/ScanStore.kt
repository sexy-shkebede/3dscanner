package com.scanner3d.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.scanner3d.core.Mesh
import com.scanner3d.core.PointCloud
import com.scanner3d.core.ThumbnailRenderer
import com.scanner3d.io.BinaryStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class ScanMeta(
    val id: String,
    val name: String,
    val createdAt: Long,
    val durationMs: Long = 0,
    val pointCount: Int = 0,
    val triangleCount: Int = 0,
    val hasModel: Boolean = false,
    val hasVideo: Boolean = false,
    val voxelSize: Float = 0.01f,
    val source: String = "depth",
    val sizeX: Float = 0f,
    val sizeY: Float = 0f,
    val sizeZ: Float = 0f,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("name", name); put("createdAt", createdAt); put("durationMs", durationMs)
        put("pointCount", pointCount); put("triangleCount", triangleCount); put("hasModel", hasModel)
        put("hasVideo", hasVideo); put("voxelSize", voxelSize.toDouble()); put("source", source)
        put("sizeX", sizeX.toDouble()); put("sizeY", sizeY.toDouble()); put("sizeZ", sizeZ.toDouble())
    }

    companion object {
        fun fromJson(o: JSONObject) = ScanMeta(
            id = o.getString("id"),
            name = o.optString("name", "Scan"),
            createdAt = o.optLong("createdAt"),
            durationMs = o.optLong("durationMs"),
            pointCount = o.optInt("pointCount"),
            triangleCount = o.optInt("triangleCount"),
            hasModel = o.optBoolean("hasModel"),
            hasVideo = o.optBoolean("hasVideo"),
            voxelSize = o.optDouble("voxelSize", 0.01).toFloat(),
            source = o.optString("source", "depth"),
            sizeX = o.optDouble("sizeX", 0.0).toFloat(),
            sizeY = o.optDouble("sizeY", 0.0).toFloat(),
            sizeZ = o.optDouble("sizeZ", 0.0).toFloat(),
        )
    }
}

/** File-based storage: one directory per scan with metadata, cloud, mesh, video and thumbnail. */
class ScanStore(context: Context) {
    private val root = File(context.filesDir, "scans").apply { mkdirs() }

    private val _version = MutableStateFlow(0)
    /** Bumped on every change so observers can reload. */
    val version: StateFlow<Int> = _version

    private fun changed() { _version.value++ }

    fun newId(): String =
        SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())

    fun dir(id: String) = File(root, id).apply { mkdirs() }
    fun videoFile(id: String) = File(dir(id), "video.mp4")
    fun cloudFile(id: String) = File(dir(id), "cloud.bin")
    fun meshFile(id: String) = File(dir(id), "mesh.bin")
    fun thumbFile(id: String) = File(dir(id), "thumb.png")
    private fun metaFile(id: String) = File(dir(id), "meta.json")

    fun list(): List<ScanMeta> =
        (root.listFiles() ?: emptyArray())
            .filter { it.isDirectory }
            .mapNotNull { runCatching { load(it.name) }.getOrNull() }
            .sortedByDescending { it.createdAt }

    fun load(id: String): ScanMeta? {
        val f = File(File(root, id), "meta.json")
        if (!f.exists()) return null
        return ScanMeta.fromJson(JSONObject(f.readText()))
    }

    fun save(meta: ScanMeta) {
        val f = metaFile(meta.id)
        val tmp = File(f.parentFile, "meta.json.tmp")
        tmp.writeText(meta.toJson().toString(2))
        if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
        changed()
    }

    fun saveCloud(id: String, cloud: PointCloud) = BinaryStore.writeCloud(cloudFile(id), cloud)
    fun loadCloud(id: String): PointCloud? = cloudFile(id).takeIf { it.exists() }?.let { BinaryStore.readCloud(it) }

    fun saveMesh(id: String, mesh: Mesh) = BinaryStore.writeMesh(meshFile(id), mesh)
    fun loadMesh(id: String): Mesh? = meshFile(id).takeIf { it.exists() }?.let { BinaryStore.readMesh(it) }

    fun deleteMesh(id: String) {
        meshFile(id).delete()
    }

    fun saveThumbnail(id: String, positions: FloatArray, colors: ByteArray, normals: FloatArray?) {
        val size = 320
        val pixels = ThumbnailRenderer.render(positions, colors, normals, size)
        val bmp = Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
        thumbFile(id).outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bmp.recycle()
        changed()
    }

    fun loadThumbnail(id: String): Bitmap? =
        thumbFile(id).takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.absolutePath) }

    fun rename(id: String, name: String) {
        load(id)?.let { save(it.copy(name = name)) }
    }

    fun delete(id: String) {
        File(root, id).deleteRecursively()
        changed()
    }
}
