package com.scanner3d.data

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class DepthSource { RAW, SMOOTHED, FEATURES }

data class ScanSettings(
    val voxelSizeMm: Int = 10,
    val maxDepthM: Float = 2.5f,
    val minConfidence: Float = 0.5f,
    val recordVideo: Boolean = true,
    val depthSource: DepthSource = DepthSource.RAW,
    val maxPoints: Int = 1_000_000,
) {
    val voxelSize: Float get() = voxelSizeMm / 1000f
}

class SettingsRepository(context: Context) {
    private val prefs = context.getSharedPreferences("scan_settings", Context.MODE_PRIVATE)
    private val _settings = MutableStateFlow(read())
    val settings: StateFlow<ScanSettings> = _settings

    private fun read(): ScanSettings {
        val d = ScanSettings()
        return ScanSettings(
            voxelSizeMm = prefs.getInt("voxelSizeMm", d.voxelSizeMm),
            maxDepthM = prefs.getFloat("maxDepthM", d.maxDepthM),
            minConfidence = prefs.getFloat("minConfidence", d.minConfidence),
            recordVideo = prefs.getBoolean("recordVideo", d.recordVideo),
            depthSource = runCatching { DepthSource.valueOf(prefs.getString("depthSource", null) ?: "") }
                .getOrDefault(d.depthSource),
            maxPoints = prefs.getInt("maxPoints", d.maxPoints).coerceIn(100_000, 2_000_000),
        )
    }

    fun update(transform: (ScanSettings) -> ScanSettings) {
        val s = transform(_settings.value)
        prefs.edit {
            putInt("voxelSizeMm", s.voxelSizeMm)
            putFloat("maxDepthM", s.maxDepthM)
            putFloat("minConfidence", s.minConfidence)
            putBoolean("recordVideo", s.recordVideo)
            putString("depthSource", s.depthSource.name)
            putInt("maxPoints", s.maxPoints.coerceIn(100_000, 2_000_000))
        }
        _settings.value = s
    }
}

/** Simple service locator for app-wide singletons. */
object AppGraph {
    lateinit var store: ScanStore
        private set
    lateinit var settings: SettingsRepository
        private set

    fun init(context: Context) {
        if (::store.isInitialized) return
        store = ScanStore(context.applicationContext)
        settings = SettingsRepository(context.applicationContext)
    }
}
