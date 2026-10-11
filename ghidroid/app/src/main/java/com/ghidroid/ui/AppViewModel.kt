package com.ghidroid.ui

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ghidroid.GhidroidApp
import com.ghidroid.analysis.OpenOptions
import com.ghidroid.analysis.OpenResult
import com.ghidroid.analysis.ProgramLoader
import com.ghidroid.data.LibraryEntry
import com.ghidroid.engine.Engine
import com.ghidroid.loader.Bin
import com.ghidroid.loader.LoadedBinary
import com.ghidroid.loader.Loaders
import com.ghidroid.ui.workspace.Workspace
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface EngineState {
    data class Preparing(val progress: Float) : EngineState
    data object Ready : EngineState
    data class Failed(val message: String) : EngineState
}

enum class OpenStage { COPYING, PARSING, STARTING }

data class OpenProgress(val name: String, val stage: OpenStage, val progress: Float? = null)

/** Asks the user for a processor when the loader could not pick one (raw images, unknown machines). */
data class LanguageRequest(val entry: LibraryEntry, val binary: LoadedBinary?)

enum class Screen { HOME, WORKSPACE, SETTINGS }

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val graph = app as GhidroidApp
    val settings = graph.settings
    val library = graph.library

    var engine by mutableStateOf<EngineState>(EngineState.Preparing(0f))
        private set
    var screen by mutableStateOf(Screen.HOME)
    private var returnTo = Screen.HOME
    var workspace by mutableStateOf<Workspace?>(null)
        private set
    var opening by mutableStateOf<OpenProgress?>(null)
        private set
    var error by mutableStateOf<String?>(null)
    var languageRequest by mutableStateOf<LanguageRequest?>(null)

    private var openJob: Job? = null
    private var pendingUri: Uri? = null

    init {
        viewModelScope.launch { library.refresh() }
        viewModelScope.launch {
            try {
                Engine.ensureReady(app) { engine = EngineState.Preparing(it) }
                engine = EngineState.Ready
                pendingUri?.let {
                    pendingUri = null
                    importUri(it)
                }
            } catch (t: Throwable) {
                engine = EngineState.Failed(t.message ?: t.toString())
            }
        }
    }

    fun openSettings() {
        returnTo = screen
        screen = Screen.SETTINGS
    }

    fun closeSettings() {
        screen = if (returnTo == Screen.WORKSPACE && workspace != null) Screen.WORKSPACE else Screen.HOME
    }

    fun importUri(uri: Uri) {
        if (engine !is EngineState.Ready) {
            pendingUri = uri
            return
        }
        openJob?.cancel()
        openJob = viewModelScope.launch {
            val name = uri.lastPathSegment?.substringAfterLast('/') ?: "binary"
            opening = OpenProgress(name, OpenStage.COPYING, 0f)
            val entry = try {
                library.import(uri) { opening = OpenProgress(name, OpenStage.COPYING, it) }
            } catch (t: Throwable) {
                opening = null
                error = t.message ?: t.toString()
                return@launch
            }
            openInternal(entry, OpenOptions())
        }
    }

    fun open(entry: LibraryEntry, options: OpenOptions? = null) {
        openJob?.cancel()
        openJob = viewModelScope.launch {
            openInternal(entry, options ?: OpenOptions(entry.languageId, entry.compilerId, entry.forceRaw, entry.rawBase))
        }
    }

    private suspend fun openInternal(entry: LibraryEntry, options: OpenOptions) {
        opening = OpenProgress(entry.name, OpenStage.PARSING)
        closeWorkspace()
        try {
            when (val r = ProgramLoader.open(library.fileOf(entry), entry.name, options)) {
                is OpenResult.NeedsLanguage -> {
                    opening = null
                    val updated = entry.copy(format = r.binary.formatName, machine = r.binary.machine)
                    library.save(updated)
                    languageRequest = LanguageRequest(updated, r.binary)
                }
                is OpenResult.Opened -> {
                    val p = r.program
                    val updated = entry.copy(
                        openedAt = System.currentTimeMillis(),
                        format = "${p.binary.formatName} · ${p.binary.fileType}",
                        machine = p.binary.machine.takeIf { it != "—" } ?: p.language.processor,
                        languageId = options.languageId,
                        compilerId = options.compilerId,
                        forceRaw = options.forceRaw,
                        rawBase = options.rawBase,
                        functionCount = p.functions.value.size,
                    )
                    library.save(updated)
                    val ws = Workspace(p, updated, viewModelScope, settings.state)
                    workspace = ws
                    opening = null
                    screen = Screen.WORKSPACE
                    ws.startAnalysis()
                    p.binary.entryPoints.firstOrNull()?.let { ws.goTo(it, record = false) }
                    ws.tab = com.ghidroid.ui.workspace.WsTab.OVERVIEW
                }
            }
        } catch (t: Throwable) {
            opening = null
            error = t.message ?: t.toString()
        }
    }

    fun requestLanguage(entry: LibraryEntry) {
        val loaded = workspace?.takeIf { it.entry.id == entry.id }?.program?.binary
        if (loaded != null) {
            languageRequest = LanguageRequest(entry, loaded)
            return
        }
        viewModelScope.launch {
            val binary = withContext(Dispatchers.IO) {
                runCatching { Bin.open(library.fileOf(entry)).use { Loaders.load(it) } }.getOrNull()
            }
            languageRequest = LanguageRequest(entry, binary)
        }
    }

    fun closeWorkspace() {
        workspace?.close()
        workspace = null
        if (screen == Screen.WORKSPACE) screen = Screen.HOME
    }

    fun delete(entry: LibraryEntry) {
        if (workspace?.entry?.id == entry.id) closeWorkspace()
        viewModelScope.launch { library.delete(entry) }
    }

    fun cancelOpen() {
        openJob?.cancel()
        opening = null
    }

    override fun onCleared() {
        workspace?.close()
        super.onCleared()
    }
}
