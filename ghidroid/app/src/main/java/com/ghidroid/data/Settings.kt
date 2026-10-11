package com.ghidroid.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ThemeMode { SYSTEM, LIGHT, DARK }

enum class CodeView { DECOMPILER, LISTING }

data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = true,
    val codeFontSize: Float = 13f,
    /** 0 = fit the screen width. */
    val decompilerWidth: Int = 0,
    val showBytes: Boolean = false,
    val lowercaseMnemonics: Boolean = false,
    val openFunctionsIn: CodeView = CodeView.DECOMPILER,
    val discoverFunctions: Boolean = true,
    val minStringLength: Int = 5,
    val readonlyPropagate: Boolean = true,
)

class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(read())
    val state: StateFlow<AppSettings> = _state.asStateFlow()

    private fun read(): AppSettings {
        val d = AppSettings()
        return AppSettings(
            themeMode = runCatching { ThemeMode.valueOf(prefs.getString("theme", d.themeMode.name)!!) }.getOrDefault(d.themeMode),
            dynamicColor = prefs.getBoolean("dynamic", d.dynamicColor),
            codeFontSize = prefs.getFloat("font", d.codeFontSize),
            decompilerWidth = prefs.getInt("width", d.decompilerWidth),
            showBytes = prefs.getBoolean("bytes", d.showBytes),
            lowercaseMnemonics = prefs.getBoolean("lower", d.lowercaseMnemonics),
            openFunctionsIn = runCatching { CodeView.valueOf(prefs.getString("open", d.openFunctionsIn.name)!!) }.getOrDefault(d.openFunctionsIn),
            discoverFunctions = prefs.getBoolean("discover", d.discoverFunctions),
            minStringLength = prefs.getInt("minstr", d.minStringLength),
            readonlyPropagate = prefs.getBoolean("readonly", d.readonlyPropagate),
        )
    }

    fun update(transform: (AppSettings) -> AppSettings) {
        val s = transform(_state.value)
        _state.value = s
        prefs.edit()
            .putString("theme", s.themeMode.name)
            .putBoolean("dynamic", s.dynamicColor)
            .putFloat("font", s.codeFontSize)
            .putInt("width", s.decompilerWidth)
            .putBoolean("bytes", s.showBytes)
            .putBoolean("lower", s.lowercaseMnemonics)
            .putString("open", s.openFunctionsIn.name)
            .putBoolean("discover", s.discoverFunctions)
            .putInt("minstr", s.minStringLength)
            .putBoolean("readonly", s.readonlyPropagate)
            .apply()
    }
}
