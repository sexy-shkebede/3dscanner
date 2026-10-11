package com.scanner3d.ar

import android.app.Activity
import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.Image
import android.net.Uri
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import android.view.WindowManager
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Camera
import com.google.ar.core.CameraConfig
import com.google.ar.core.CameraConfigFilter
import com.google.ar.core.Config
import com.google.ar.core.Frame
import com.google.ar.core.PlaybackStatus
import com.google.ar.core.RecordingConfig
import com.google.ar.core.RecordingStatus
import com.google.ar.core.Session
import com.google.ar.core.TrackingFailureReason
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.CameraNotAvailableException
import com.google.ar.core.exceptions.NotYetAvailableException
import com.google.ar.core.exceptions.UnavailableApkTooOldException
import com.google.ar.core.exceptions.UnavailableArcoreNotInstalledException
import com.google.ar.core.exceptions.UnavailableDeviceNotCompatibleException
import com.google.ar.core.exceptions.UnavailableSdkTooOldException
import com.google.ar.core.exceptions.UnavailableUserDeclinedInstallationException
import com.scanner3d.R
import com.scanner3d.core.DepthIntegrator
import com.scanner3d.core.DisplayBuffer
import com.scanner3d.core.FrameData
import com.scanner3d.core.IntegrationSettings
import com.scanner3d.core.PointMap
import com.scanner3d.data.DepthSource
import com.scanner3d.data.ScanMeta
import com.scanner3d.data.ScanSettings
import com.scanner3d.data.ScanStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import java.nio.ByteOrder
import java.util.EnumSet
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.min
import kotlin.math.sqrt

enum class CapturePhase { INITIALIZING, READY, RECORDING, SAVING, ERROR }

enum class TrackingHint { NONE, INITIALIZING, LOW_LIGHT, TOO_FAST, FEW_FEATURES, CAMERA_UNAVAILABLE, LOST }

data class CaptureUiState(
    val phase: CapturePhase = CapturePhase.INITIALIZING,
    val tracking: Boolean = false,
    val hint: TrackingHint = TrackingHint.INITIALIZING,
    val pointCount: Int = 0,
    val elapsedMs: Long = 0,
    val source: DepthSource = DepthSource.RAW,
    val mapFull: Boolean = false,
    val videoRecording: Boolean = false,
    val replay: Boolean = false,
    val replayFinished: Boolean = false,
    val errorMessage: String? = null,
    val fps: Int = 0,
)

/**
 * Owns the ARCore session and the GL renderer for the capture screen. Frames are rendered on the GL
 * thread; depth integration runs on a dedicated worker thread.
 */
class ArScanController(
    private val activity: Activity,
    private val settings: ScanSettings,
    private val store: ScanStore,
    /** When set, the scan's recorded video is replayed and re-processed instead of the live camera. */
    private val replayScanId: String? = null,
) : GLSurfaceView.Renderer {

    private val _state = MutableStateFlow(CaptureUiState(replay = replayScanId != null))
    val state: StateFlow<CaptureUiState> = _state

    private val display = DisplayBuffer(settings.maxPoints)
    private val map = PointMap(settings.voxelSize, settings.maxPoints, display)
    private val integrator = DepthIntegrator(
        map,
        IntegrationSettings(maxDepth = settings.maxDepthM, minConfidence = settings.minConfidence),
    )
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "integration").apply { priority = Thread.NORM_PRIORITY } }
    private val freeFrames = ArrayBlockingQueue<FrameData>(2).apply { add(FrameData()); add(FrameData()) }
    private val glTasks = ConcurrentLinkedQueue<Runnable>()

    private val background = BackgroundRenderer()
    private val points = LivePointRenderer(display)

    private var session: Session? = null
    private var installRequested = false
    private var glView: GLSurfaceView? = null
    private var resumed = false
    private var textureBound = false

    @Volatile var showCamera = true
    @Volatile var highlight = false

    @Volatile private var recording = false
    private var recordingScanId: String? = null
    private var recordStartMs = 0L
    private var activeSource = settings.depthSource

    private var viewportWidth = 1
    private var viewportHeight = 1
    private var viewportChanged = true
    private var lastRotation = -1

    private val view = FloatArray(16)
    private val proj = FloatArray(16)
    private val tint = floatArrayOf(0.35f, 0.85f, 1f, 0f)
    private val featureColor = floatArrayOf(1f, 1f, 1f)

    private var lastIntegrationMs = 0L
    private var lastDepthTimestamp = 0L
    private var lastPoseTimestamp = 0L
    private val lastQuat = FloatArray(4)
    private val lastPos = FloatArray(3)
    private var tooFastUntilMs = 0L
    private var lastStateMs = 0L
    private var frameCounter = 0
    private var fpsWindowStart = 0L
    private var fps = 0

    fun attach(view: GLSurfaceView) {
        glView = view
        if (resumed) view.onResume()
    }

    // ------------------------------------------------------------------ lifecycle (main thread)

    fun onResume() {
        resumed = true
        if (session == null && !createSession()) return
        try {
            session?.resume()
        } catch (e: CameraNotAvailableException) {
            fail(activity.getString(R.string.error_camera_unavailable))
            session = null
            return
        } catch (e: Exception) {
            fail(e.message ?: e.toString())
            return
        }
        glView?.onResume()
        if (_state.value.phase == CapturePhase.INITIALIZING) {
            _state.update { it.copy(phase = CapturePhase.READY, source = activeSource) }
            if (replayScanId != null) startRecording()
        }
    }

    fun onPause() {
        resumed = false
        glView?.onPause()
        session?.pause()
    }

    fun destroy() {
        recording = false
        worker.shutdown()
        session?.close()
        session = null
    }

    private fun createSession(): Boolean {
        try {
            when (ArCoreApk.getInstance().requestInstall(activity, !installRequested)) {
                ArCoreApk.InstallStatus.INSTALL_REQUESTED -> {
                    installRequested = true
                    return false
                }
                ArCoreApk.InstallStatus.INSTALLED -> Unit
            }
            val s = Session(activity)
            if (replayScanId != null) {
                s.setPlaybackDatasetUri(Uri.fromFile(store.videoFile(replayScanId)))
            } else {
                chooseCameraConfig(s)
            }
            configure(s)
            session = s
            textureBound = false
            return true
        } catch (e: UnavailableUserDeclinedInstallationException) {
            fail(activity.getString(R.string.error_arcore_declined))
        } catch (e: UnavailableArcoreNotInstalledException) {
            fail(activity.getString(R.string.error_arcore_missing))
        } catch (e: UnavailableApkTooOldException) {
            fail(activity.getString(R.string.error_arcore_update))
        } catch (e: UnavailableSdkTooOldException) {
            fail(activity.getString(R.string.error_app_update))
        } catch (e: UnavailableDeviceNotCompatibleException) {
            fail(activity.getString(R.string.error_device_unsupported))
        } catch (e: Exception) {
            Log.e(TAG, "Session creation failed", e)
            fail(activity.getString(R.string.error_generic, e.message ?: e.javaClass.simpleName))
        }
        return false
    }

    private fun chooseCameraConfig(s: Session) {
        try {
            val filter = CameraConfigFilter(s)
                .setFacingDirection(CameraConfig.FacingDirection.BACK)
                .setTargetFps(EnumSet.of(CameraConfig.TargetFps.TARGET_FPS_30))
            val configs = s.getSupportedCameraConfigs(filter)
            val best = configs.firstOrNull {
                it.depthSensorUsage == CameraConfig.DepthSensorUsage.REQUIRE_AND_USE
            } ?: configs.firstOrNull()
            if (best != null) s.cameraConfig = best
        } catch (e: Exception) {
            Log.w(TAG, "Camera config selection failed", e)
        }
    }

    private fun configure(s: Session) {
        val config = Config(s)
        config.focusMode = Config.FocusMode.AUTO
        config.updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
        config.planeFindingMode = Config.PlaneFindingMode.DISABLED
        config.lightEstimationMode = Config.LightEstimationMode.DISABLED
        val depthSupported = s.isDepthModeSupported(Config.DepthMode.AUTOMATIC)
        activeSource = when {
            settings.depthSource == DepthSource.FEATURES -> DepthSource.FEATURES
            !depthSupported -> DepthSource.FEATURES
            else -> settings.depthSource
        }
        config.depthMode = if (activeSource == DepthSource.FEATURES) Config.DepthMode.DISABLED else Config.DepthMode.AUTOMATIC
        s.configure(config)
    }

    private fun fail(message: String) {
        _state.update { it.copy(phase = CapturePhase.ERROR, errorMessage = message) }
    }

    // ------------------------------------------------------------------ recording control

    fun startRecording() {
        val s = session ?: return
        if (recording) return
        val id = replayScanId ?: store.newId()
        recordingScanId = id
        glTasks.add(Runnable {
            worker.execute { map.clear() }
            var video = false
            if (replayScanId == null && settings.recordVideo) {
                try {
                    val cfg = RecordingConfig(s)
                        .setMp4DatasetUri(Uri.fromFile(store.videoFile(id)))
                        .setAutoStopOnPause(true)
                        .setRecordingRotation(recordingRotation(s))
                    s.startRecording(cfg)
                    video = s.recordingStatus == RecordingStatus.OK
                } catch (e: Exception) {
                    Log.w(TAG, "Video recording unavailable", e)
                }
            }
            recordStartMs = SystemClock.elapsedRealtime()
            lastIntegrationMs = 0
            lastPoseTimestamp = 0
            recording = true
            _state.update {
                it.copy(phase = CapturePhase.RECORDING, videoRecording = video, pointCount = 0, mapFull = false)
            }
        })
    }

    /** Clears the live map without stopping. */
    fun resetMap() {
        worker.execute { map.clear() }
    }

    /**
     * Stops recording, waits for integration to finish and saves the scan.
     * Returns the scan id, or null if nothing was captured.
     */
    suspend fun stopAndSave(): String? {
        if (!recording) return null
        val stopped = CompletableDeferred<Boolean>()
        val glv = glView
        val stopTask = Runnable {
            recording = false
            var hadVideo = false
            try {
                val s = session
                if (s != null && s.recordingStatus == RecordingStatus.OK) {
                    s.stopRecording()
                    hadVideo = true
                }
            } catch (e: Exception) {
                Log.w(TAG, "stopRecording failed", e)
            }
            stopped.complete(hadVideo)
        }
        if (resumed && glv != null) glTasks.add(stopTask) else stopTask.run()
        val hadVideo = stopped.await() || _state.value.videoRecording
        _state.update { it.copy(phase = CapturePhase.SAVING) }
        val id = recordingScanId ?: return null
        val duration = SystemClock.elapsedRealtime() - recordStartMs

        return withContext(Dispatchers.Default) {
            // Drain pending integrations and build the cloud on the worker for thread safety.
            val cloud = withContext(Dispatchers.IO) {
                worker.submit<com.scanner3d.core.PointCloud> { map.toPointCloud(1) }.get()
            }
            if (cloud.size == 0) {
                if (replayScanId == null) store.delete(id)
                _state.update { it.copy(phase = CapturePhase.READY) }
                return@withContext null
            }
            val bounds = cloud.bounds()
            store.saveCloud(id, cloud)
            val previous = store.load(id)
            if (replayScanId != null) store.deleteMesh(id)
            val name = previous?.name ?: activity.getString(
                R.string.scan_default_name,
                java.text.SimpleDateFormat("d MMM, HH:mm", java.util.Locale.getDefault()).format(java.util.Date()),
            )
            store.saveThumbnail(id, cloud.positions, cloud.colors, cloud.normals)
            store.save(
                ScanMeta(
                    id = id,
                    name = name,
                    createdAt = previous?.createdAt ?: System.currentTimeMillis(),
                    durationMs = previous?.durationMs ?: duration,
                    pointCount = cloud.size,
                    triangleCount = 0,
                    hasModel = false,
                    hasVideo = previous?.hasVideo ?: (hadVideo && store.videoFile(id).exists()),
                    voxelSize = settings.voxelSize,
                    source = activeSource.name.lowercase(),
                    sizeX = bounds.sizeX, sizeY = bounds.sizeY, sizeZ = bounds.sizeZ,
                ),
            )
            id
        }
    }

    private fun recordingRotation(s: Session): Int = try {
        val manager = activity.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val chars = manager.getCameraCharacteristics(s.cameraConfig.cameraId)
        val sensor = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
        val displayDeg = when (displayRotation()) {
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
        (sensor - displayDeg + 360) % 360
    } catch (e: Exception) {
        90
    }

    @Suppress("DEPRECATION")
    private fun displayRotation(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            activity.display?.rotation ?: Surface.ROTATION_0
        } else {
            (activity.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.rotation
        }

    // ------------------------------------------------------------------ GL thread

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES30.glClearColor(0.05f, 0.05f, 0.07f, 1f)
        background.create()
        points.create()
        textureBound = false
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES30.glViewport(0, 0, width, height)
        viewportWidth = width
        viewportHeight = height
        viewportChanged = true
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)
        val s = session ?: return
        if (!resumed) return

        val rotation = displayRotation()
        if (viewportChanged || rotation != lastRotation) {
            s.setDisplayGeometry(rotation, viewportWidth, viewportHeight)
            viewportChanged = false
            lastRotation = rotation
        }
        if (!textureBound) {
            s.setCameraTextureName(background.textureId)
            textureBound = true
        }
        while (true) glTasks.poll()?.run() ?: break

        val frame: Frame = try {
            s.update()
        } catch (e: Exception) {
            Log.w(TAG, "update failed", e)
            return
        }
        val camera = frame.camera
        background.update(frame)
        if (showCamera) background.draw(frame, if (recording) 0.8f else 1f)

        val now = SystemClock.elapsedRealtime()
        countFps(now)

        if (replayScanId != null && recording) {
            val status = s.playbackStatus
            if (status == PlaybackStatus.FINISHED) {
                _state.update { it.copy(replayFinished = true) }
            } else if (status == PlaybackStatus.IO_ERROR) {
                _state.update { it.copy(phase = CapturePhase.ERROR, errorMessage = activity.getString(R.string.error_replay)) }
            }
        }

        val tracking = camera.trackingState == TrackingState.TRACKING
        if (tracking) {
            camera.getViewMatrix(view, 0)
            camera.getProjectionMatrix(proj, 0, 0.03f, 50f)
            val viewportScale = viewportHeight * proj[5] / 2f

            if (recording) {
                maybeIntegrate(frame, camera, now)
                points.sync()
                tint[3] = if (highlight) 0.65f else 0f
                points.draw(view, proj, settings.voxelSize * 1.1f, viewportScale, tint)
            } else {
                try {
                    frame.acquirePointCloud().use { pc ->
                        points.drawFeatures(pc.points, view, proj, viewportScale, featureColor)
                    }
                } catch (e: Exception) {
                    // Feature preview is optional.
                }
            }
        }

        if (now - lastStateMs > 120) {
            lastStateMs = now
            val hint = when {
                tracking && now < tooFastUntilMs -> TrackingHint.TOO_FAST
                tracking -> TrackingHint.NONE
                camera.trackingState == TrackingState.PAUSED -> when (camera.trackingFailureReason) {
                    TrackingFailureReason.INSUFFICIENT_LIGHT -> TrackingHint.LOW_LIGHT
                    TrackingFailureReason.EXCESSIVE_MOTION -> TrackingHint.TOO_FAST
                    TrackingFailureReason.INSUFFICIENT_FEATURES -> TrackingHint.FEW_FEATURES
                    TrackingFailureReason.CAMERA_UNAVAILABLE -> TrackingHint.CAMERA_UNAVAILABLE
                    TrackingFailureReason.NONE -> TrackingHint.INITIALIZING
                    else -> TrackingHint.LOST
                }
                else -> TrackingHint.LOST
            }
            _state.update {
                it.copy(
                    tracking = tracking,
                    hint = hint,
                    pointCount = map.count,
                    mapFull = map.isFull,
                    elapsedMs = if (recording) now - recordStartMs else it.elapsedMs,
                    fps = fps,
                )
            }
        }
    }

    private fun countFps(now: Long) {
        frameCounter++
        if (fpsWindowStart == 0L) fpsWindowStart = now
        if (now - fpsWindowStart >= 1000) {
            fps = frameCounter
            frameCounter = 0
            fpsWindowStart = now
        }
    }

    // ------------------------------------------------------------------ integration

    private fun maybeIntegrate(frame: Frame, camera: Camera, now: Long) {
        if (now - lastIntegrationMs < MIN_INTERVAL_MS) return
        val pose = camera.pose

        // Motion gate: skip blurry frames during fast rotation.
        val ts = frame.timestamp
        val q = FloatArray(4).also { pose.getRotationQuaternion(it, 0) }
        if (lastPoseTimestamp != 0L) {
            val dt = (ts - lastPoseTimestamp) / 1e9f
            if (dt > 0f) {
                val dot = abs(q[0] * lastQuat[0] + q[1] * lastQuat[1] + q[2] * lastQuat[2] + q[3] * lastQuat[3])
                val angle = 2f * acos(min(1f, dot))
                val dx = pose.tx() - lastPos[0]; val dy = pose.ty() - lastPos[1]; val dz = pose.tz() - lastPos[2]
                val speed = sqrt(dx * dx + dy * dy + dz * dz) / dt
                if (angle / dt > MAX_ANGULAR_SPEED || speed > MAX_LINEAR_SPEED) {
                    tooFastUntilMs = now + 600
                    q.copyInto(lastQuat)
                    lastPos[0] = pose.tx(); lastPos[1] = pose.ty(); lastPos[2] = pose.tz()
                    lastPoseTimestamp = ts
                    return
                }
            }
        }
        q.copyInto(lastQuat)
        lastPos[0] = pose.tx(); lastPos[1] = pose.ty(); lastPos[2] = pose.tz()
        lastPoseTimestamp = ts

        val fd = freeFrames.poll() ?: return
        val ok = try {
            fill(fd, frame, camera)
        } catch (e: Exception) {
            Log.w(TAG, "Frame capture failed", e)
            false
        }
        if (!ok) {
            freeFrames.offer(fd)
            return
        }
        lastIntegrationMs = now
        worker.execute {
            try {
                integrator.integrate(fd)
            } catch (e: Exception) {
                Log.e(TAG, "Integration failed", e)
            } finally {
                freeFrames.offer(fd)
            }
        }
    }

    private fun fill(fd: FrameData, frame: Frame, camera: Camera): Boolean {
        camera.pose.toMatrix(fd.pose, 0)
        fd.timestampNs = frame.timestamp

        if (activeSource == DepthSource.FEATURES) {
            fd.kind = FrameData.Kind.FEATURES
            frame.acquirePointCloud().use { pc ->
                val buf = pc.points
                val n = buf.remaining() / 4
                fd.ensureFeatures(n)
                buf.get(fd.features, 0, n * 4)
                fd.featureCount = n
            }
            if (fd.featureCount == 0) return false
        } else {
            fd.kind = FrameData.Kind.DEPTH
            val depthImage = try {
                if (activeSource == DepthSource.RAW) frame.acquireRawDepthImage16Bits() else frame.acquireDepthImage16Bits()
            } catch (e: NotYetAvailableException) {
                return false
            }
            depthImage.use { img ->
                if (img.timestamp == lastDepthTimestamp) return false
                lastDepthTimestamp = img.timestamp
                copyDepth(img, fd)
            }
            fd.hasConfidence = false
            if (activeSource == DepthSource.RAW) {
                try {
                    frame.acquireRawDepthConfidenceImage().use { conf ->
                        val plane = conf.planes[0]
                        val buf = plane.buffer
                        fd.ensureConfidence(buf.remaining())
                        fd.confidenceStride = plane.rowStride
                        buf.get(fd.confidence, 0, buf.remaining())
                        fd.hasConfidence = true
                    }
                } catch (e: NotYetAvailableException) {
                    // Use depth without confidence filtering.
                }
            }
            val ti = camera.textureIntrinsics
            val dims = ti.imageDimensions
            val focal = ti.focalLength
            val pp = ti.principalPoint
            val sx = fd.depthWidth.toFloat() / dims[0]
            val sy = fd.depthHeight.toFloat() / dims[1]
            fd.dfx = focal[0] * sx; fd.dfy = focal[1] * sy
            fd.dcx = pp[0] * sx; fd.dcy = pp[1] * sy
        }

        fd.hasImage = false
        try {
            frame.acquireCameraImage().use { img -> copyImage(img, fd) }
            val ii = camera.imageIntrinsics
            val dims = ii.imageDimensions
            val sx = fd.imageWidth.toFloat() / dims[0]
            val sy = fd.imageHeight.toFloat() / dims[1]
            fd.ifx = ii.focalLength[0] * sx; fd.ify = ii.focalLength[1] * sy
            fd.icx = ii.principalPoint[0] * sx; fd.icy = ii.principalPoint[1] * sy
            fd.hasImage = true
        } catch (e: NotYetAvailableException) {
            // Points will get a neutral colour.
        }
        return true
    }

    private fun copyDepth(img: Image, fd: FrameData) {
        val plane = img.planes[0]
        val shorts = plane.buffer.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        fd.depthWidth = img.width
        fd.depthHeight = img.height
        fd.depthStride = plane.rowStride / 2
        val n = shorts.remaining()
        fd.ensureDepth(fd.depthStride * img.height)
        shorts.get(fd.depth, 0, min(n, fd.depth.size))
    }

    private fun copyImage(img: Image, fd: FrameData) {
        fd.imageWidth = img.width
        fd.imageHeight = img.height
        val yp = img.planes[0]; val up = img.planes[1]; val vp = img.planes[2]
        fd.yRowStride = yp.rowStride
        fd.uvRowStride = up.rowStride
        fd.uvPixelStride = up.pixelStride
        val yb = yp.buffer; val ub = up.buffer; val vb = vp.buffer
        fd.ensureY(yb.remaining()); val yn = yb.remaining(); yb.get(fd.y, 0, yn)
        fd.ensureU(ub.remaining()); val un = ub.remaining(); ub.get(fd.u, 0, un)
        fd.ensureV(vb.remaining()); val vn = vb.remaining(); vb.get(fd.v, 0, vn)
    }

    companion object {
        private const val TAG = "ArScanController"
        private const val MIN_INTERVAL_MS = 70L
        private const val MAX_ANGULAR_SPEED = 1.6f // rad/s
        private const val MAX_LINEAR_SPEED = 1.0f // m/s

        /** Quick capability check used before opening the capture screen. */
        fun availability(context: Context): ArCoreApk.Availability =
            ArCoreApk.getInstance().checkAvailability(context)
    }
}
