package com.scanner3d.ui.viewer

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.scanner3d.core.Bounds
import com.scanner3d.core.Mesh
import com.scanner3d.core.PointCloud
import com.scanner3d.core.ProgressSink
import com.scanner3d.core.ReconstructionParams
import com.scanner3d.core.SurfaceReconstructor
import com.scanner3d.data.ScanMeta
import com.scanner3d.data.ScanStore
import com.scanner3d.gl.ModelSurfaceView
import com.scanner3d.gl.RenderMode
import com.scanner3d.io.ExportFormat
import com.scanner3d.io.Exporters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.atan2

enum class Detail(val factor: Float) { LOW(2.5f), MEDIUM(1.5f), HIGH(1.0f) }

data class BuildOptions(
    val detail: Detail = Detail.MEDIUM,
    val smoothing: Int = 4,
    val minObservations: Int = 2,
    val keepLargestOnly: Boolean = false,
    val removeOutliers: Boolean = true,
)

data class BuildProgress(val stage: ProgressSink.Stage, val overall: Float)

sealed interface ViewerEvent {
    data class Message(val text: Int) : ViewerEvent
    data class Shared(val uri: Uri, val mime: String) : ViewerEvent
}

/** Screen state for one scan: loading, model building and export. */
class ViewerState(
    private val context: Context,
    val scanId: String,
    private val store: ScanStore,
    private val scope: CoroutineScope,
) {
    var meta by mutableStateOf<ScanMeta?>(null)
        private set
    var loading by mutableStateOf(true)
        private set
    var mode by mutableStateOf(RenderMode.POINTS)
        private set
    var hasMesh by mutableStateOf(false)
        private set
    var exporting by mutableStateOf(false)
        private set

    private val _progress = MutableStateFlow<BuildProgress?>(null)
    val progress: StateFlow<BuildProgress?> = _progress

    private val _events = MutableSharedFlow<ViewerEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<ViewerEvent> = _events

    private var cloud: PointCloud? = null
    private var mesh: Mesh? = null
    private var view: ModelSurfaceView? = null
    private var buildJob: Job? = null
    private var framed = false

    fun load() {
        scope.launch {
            val (m, c, me) = withContext(Dispatchers.IO) {
                Triple(store.load(scanId), store.loadCloud(scanId), store.loadMesh(scanId))
            }
            meta = m
            cloud = c
            mesh = me
            hasMesh = me != null && me.triangleCount > 0
            mode = if (hasMesh) RenderMode.COLOR else RenderMode.POINTS
            loading = false
            pushToView()
        }
    }

    fun attach(v: ModelSurfaceView) {
        view = v
        framed = false
        pushToView()
    }

    fun detach() {
        view = null
    }

    fun setBackground(argb: Int) {
        view?.renderer?.clearColor = floatArrayOf(
            ((argb shr 16) and 0xFF) / 255f, ((argb shr 8) and 0xFF) / 255f, (argb and 0xFF) / 255f, 1f,
        )
        view?.requestRender()
    }

    fun selectMode(m: RenderMode) {
        mode = m
        view?.renderer?.mode = m
        view?.requestRender()
    }

    fun resetCamera() {
        view?.camera?.reset()
        view?.requestRender()
    }

    private fun pushToView() {
        val v = view ?: return
        if (loading) return
        v.renderer.setCloud(cloud)
        v.renderer.setMesh(mesh)
        v.renderer.mode = mode
        if (!framed) {
            frameCamera(v)
            framed = true
        }
        v.requestRender()
    }

    private fun frameCamera(v: ModelSurfaceView) {
        val c = cloud
        val positions = mesh?.positions ?: c?.positions ?: return
        val normals = mesh?.normals ?: c?.normals
        val b = robustBounds(positions)
        if (b.isEmpty) return
        var ax = 0f; var az = 0f
        if (normals != null) {
            val n = normals.size / 3
            val stride = maxOf(1, n / 20000)
            var i = 0
            while (i < n) { ax += normals[3 * i]; az += normals[3 * i + 2]; i += stride }
        }
        val yaw = if (ax * ax + az * az > 1e-6f) atan2(ax, az) else 0f
        v.camera.frame(b.centerX, b.centerY, b.centerZ, b.radius, yaw)
    }

    private fun robustBounds(positions: FloatArray): Bounds {
        val n = positions.size / 3
        if (n == 0) return Bounds.EMPTY
        val stride = maxOf(1, n / 50000)
        val m = (n + stride - 1) / stride
        val xs = FloatArray(m); val ys = FloatArray(m); val zs = FloatArray(m)
        var j = 0
        var i = 0
        while (i < n && j < m) {
            xs[j] = positions[3 * i]; ys[j] = positions[3 * i + 1]; zs[j] = positions[3 * i + 2]
            j++; i += stride
        }
        xs.sort(); ys.sort(); zs.sort()
        val lo = (j * 0.02f).toInt()
        val hi = (j - 1 - lo).coerceAtLeast(lo)
        return Bounds(xs[lo], ys[lo], zs[lo], xs[hi], ys[hi], zs[hi])
    }

    // ------------------------------------------------------------------ build

    fun build(options: BuildOptions) {
        val c = cloud ?: return
        if (buildJob?.isActive == true) return
        val m = meta ?: return
        buildJob = scope.launch {
            _progress.value = BuildProgress(ProgressSink.Stage.PREPARE, 0f)
            try {
                val result = withContext(Dispatchers.Default) {
                    val job = coroutineContext.job
                    val sink = object : ProgressSink {
                        override fun onProgress(stage: ProgressSink.Stage, fraction: Float) {
                            val (start, end) = STAGE_RANGES.getValue(stage)
                            val overall = start + (end - start) * fraction.coerceIn(0f, 1f)
                            val cur = _progress.value
                            if (cur == null || overall >= cur.overall || cur.stage != stage) {
                                _progress.value = BuildProgress(stage, overall)
                            }
                        }

                        override fun isCancelled(): Boolean = !job.isActive
                    }
                    val voxel = c.voxelSize
                    val cell = voxel * options.detail.factor
                    val params = ReconstructionParams(
                        cellSize = cell,
                        supportRadius = cell * 2.5f,
                        minObservations = options.minObservations,
                        removeOutliers = options.removeOutliers,
                        keepLargestOnly = options.keepLargestOnly,
                        smoothingIterations = options.smoothing,
                    )
                    SurfaceReconstructor().reconstruct(c, params, sink)
                }
                if (result.triangleCount == 0) {
                    _events.tryEmit(ViewerEvent.Message(com.scanner3d.R.string.build_empty))
                } else {
                    withContext(Dispatchers.IO) {
                        store.saveMesh(scanId, result)
                        store.saveThumbnail(scanId, result.positions, result.colors, result.normals)
                        val updated = m.copy(hasModel = true, triangleCount = result.triangleCount)
                        store.save(updated)
                    }
                    meta = meta?.copy(hasModel = true, triangleCount = result.triangleCount)
                    mesh = result
                    hasMesh = true
                    view?.renderer?.setMesh(result)
                    selectMode(RenderMode.COLOR)
                    _events.tryEmit(ViewerEvent.Message(com.scanner3d.R.string.build_done))
                }
            } catch (e: CancellationException) {
                // Cancelled by the user.
            } catch (e: OutOfMemoryError) {
                _events.tryEmit(ViewerEvent.Message(com.scanner3d.R.string.build_oom))
            } catch (e: Exception) {
                _events.tryEmit(ViewerEvent.Message(com.scanner3d.R.string.build_failed))
            } finally {
                _progress.value = null
            }
        }
    }

    fun cancelBuild() {
        buildJob?.cancel()
    }

    // ------------------------------------------------------------------ export

    val canExportMesh get() = hasMesh

    /** Writes the export into the cache and returns a shareable content URI. */
    fun share(format: ExportFormat, useMesh: Boolean) {
        if (exporting) return
        exporting = true
        scope.launch {
            try {
                val uri = withContext(Dispatchers.IO) {
                    val dir = File(context.cacheDir, "exports").apply { mkdirs() }
                    dir.listFiles()?.forEach { it.delete() }
                    val file = File(dir, fileName(format, useMesh))
                    file.outputStream().use { out -> writeExport(format, useMesh, out) }
                    androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.files", file)
                }
                _events.tryEmit(ViewerEvent.Shared(uri, format.mimeType))
            } catch (e: Exception) {
                _events.tryEmit(ViewerEvent.Message(com.scanner3d.R.string.export_failed))
            } finally {
                exporting = false
            }
        }
    }

    fun saveTo(uri: Uri, format: ExportFormat, useMesh: Boolean) {
        if (exporting) return
        exporting = true
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)?.use { out -> writeExport(format, useMesh, out) }
                        ?: error("Cannot open output")
                }
                _events.tryEmit(ViewerEvent.Message(com.scanner3d.R.string.export_saved))
            } catch (e: Exception) {
                _events.tryEmit(ViewerEvent.Message(com.scanner3d.R.string.export_failed))
            } finally {
                exporting = false
            }
        }
    }

    private fun writeExport(format: ExportFormat, useMesh: Boolean, out: java.io.OutputStream) {
        val me = if (useMesh) mesh else null
        Exporters.export(format, me, if (me == null) cloud else null, out)
    }

    fun fileName(format: ExportFormat, useMesh: Boolean): String {
        val base = (meta?.name ?: scanId).replace(Regex("[^\\p{L}\\p{N}_ -]"), "_").trim().ifEmpty { "scan" }
        val suffix = if (useMesh) "model" else "points"
        return "$base-$suffix.${format.extension}"
    }

    fun videoUri(): Uri? {
        val f = store.videoFile(scanId)
        if (!f.exists()) return null
        return androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.files", f)
    }

    fun rename(name: String) {
        scope.launch(Dispatchers.IO) { store.rename(scanId, name) }
        meta = meta?.copy(name = name)
    }

    fun delete(onDone: () -> Unit) {
        buildJob?.cancel()
        scope.launch {
            withContext(Dispatchers.IO) { store.delete(scanId) }
            onDone()
        }
    }

    private companion object {
        val STAGE_RANGES = mapOf(
            ProgressSink.Stage.PREPARE to (0f to 0.05f),
            ProgressSink.Stage.NORMALS to (0.05f to 0.25f),
            ProgressSink.Stage.FIELD to (0.25f to 0.70f),
            ProgressSink.Stage.MESH to (0.70f to 0.85f),
            ProgressSink.Stage.CLEANUP to (0.85f to 0.92f),
            ProgressSink.Stage.COLOR to (0.92f to 1f),
        )
    }
}
