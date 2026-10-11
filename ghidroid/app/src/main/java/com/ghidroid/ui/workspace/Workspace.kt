package com.ghidroid.ui.workspace

import android.util.LruCache
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.ghidroid.analysis.Analyzer
import com.ghidroid.analysis.CCodeParser
import com.ghidroid.analysis.DecompileOptions
import com.ghidroid.analysis.DecompiledFunction
import com.ghidroid.analysis.FunctionInfo
import com.ghidroid.analysis.FunctionKind
import com.ghidroid.analysis.Program
import com.ghidroid.data.AppSettings
import com.ghidroid.data.CodeView
import com.ghidroid.data.LibraryEntry
import com.ghidroid.loader.Addr
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class WsTab { OVERVIEW, FUNCTIONS, CODE, STRINGS, HEX }

enum class CodeMode { DECOMPILER, LISTING }

data class Location(val address: Long, val mode: CodeMode)

sealed interface DecompState {
    data object Idle : DecompState
    data class Loading(val address: Long, val function: FunctionInfo?) : DecompState
    data class Ready(val code: DecompiledFunction, val function: FunctionInfo?) : DecompState
    data class Failed(val address: Long, val function: FunctionInfo?, val message: String) : DecompState
}

data class RefResult(val address: Long, val function: FunctionInfo?, val preview: String, val isData: Boolean)

data class RefsState(
    val target: Long,
    val title: String,
    val progress: Float = 0f,
    val done: Boolean = false,
    val results: List<RefResult> = emptyList(),
)

/** Everything the workspace screens share for one opened program. */
class Workspace(
    val program: Program,
    val entry: LibraryEntry,
    private val scope: CoroutineScope,
    private val settings: StateFlow<AppSettings>,
) {
    val analyzer = Analyzer(program)
    val listing = ListingModel(program, scope)

    var tab by mutableStateOf(WsTab.OVERVIEW)
    var mode by mutableStateOf(if (settings.value.openFunctionsIn == CodeView.LISTING) CodeMode.LISTING else CodeMode.DECOMPILER)
    var current by mutableStateOf<Long?>(null)
        private set
    var decomp by mutableStateOf<DecompState>(DecompState.Idle)
        private set
    var refs by mutableStateOf<RefsState?>(null)
    /** Hex view request: file offset to reveal and number of bytes to highlight. */
    var hexTarget by mutableStateOf<Pair<Long, Int>?>(null)
    /** Line width the decompiler wraps at when "fit to screen" is on. */
    var fitWidth by mutableStateOf(72)

    val back = mutableStateListOf<Location>()
    val forward = mutableStateListOf<Location>()

    private val cache = LruCache<String, DecompiledFunction>(48)
    private var decompJob: Job? = null
    private var refsJob: Job? = null
    private var analysisJob: Job? = null

    fun startAnalysis() {
        if (analysisJob != null) return
        val s = settings.value
        analysisJob = scope.launch { analyzer.run(s.discoverFunctions, s.minStringLength) }
    }

    fun close() {
        analysisJob?.cancel()
        decompJob?.cancel()
        refsJob?.cancel()
        program.close()
    }

    private fun effectiveWidth(): Int = settings.value.decompilerWidth.takeIf { it > 0 } ?: fitWidth

    /** Opens [address] in the code tab, recording history. */
    fun goTo(address: Long, newMode: CodeMode? = null, record: Boolean = true) {
        val target = when {
            newMode != null -> newMode
            !program.isCode(address) -> CodeMode.LISTING
            program.function(address) == null && mode == CodeMode.DECOMPILER && program.functionContaining(address) == null -> CodeMode.LISTING
            else -> mode
        }
        val cur = current
        if (record && cur != null && (cur != address || mode != target)) {
            back += Location(cur, mode)
            if (back.size > 200) back.removeAt(0)
            forward.clear()
        }
        current = address
        mode = target
        tab = WsTab.CODE
        refresh()
    }

    fun openFunction(f: FunctionInfo) {
        val m = if (settings.value.openFunctionsIn == CodeView.LISTING || f.kind == FunctionKind.IMPORT) CodeMode.LISTING else CodeMode.DECOMPILER
        goTo(f.address, m)
    }

    fun switchMode(m: CodeMode) {
        if (m == mode) return
        mode = m
        refresh()
    }

    fun goBack(): Boolean {
        val loc = back.removeLastOrNull() ?: return false
        current?.let { forward += Location(it, mode) }
        current = loc.address
        mode = loc.mode
        tab = WsTab.CODE
        refresh()
        return true
    }

    fun goForward() {
        val loc = forward.removeLastOrNull() ?: return
        current?.let { back += Location(it, mode) }
        current = loc.address
        mode = loc.mode
        tab = WsTab.CODE
        refresh()
    }

    /** Wide screens show listing and decompiler side by side; both then follow every navigation. */
    var split by mutableStateOf(false)
        private set

    fun updateSplit(value: Boolean) {
        if (split == value) return
        split = value
        refresh()
    }

    /** Re-renders the current location (mode switch, settings change, width change). */
    fun refresh() {
        val addr = current ?: return
        if (split || mode == CodeMode.LISTING) listing.open(addr)
        if (split || mode == CodeMode.DECOMPILER) {
            if (program.isCode(addr)) decompileAt(addr)
        }
    }

    fun onWidthChanged(chars: Int) {
        val w = chars.coerceIn(40, 400)
        if (w == fitWidth) return
        fitWidth = w
        if ((split || mode == CodeMode.DECOMPILER) && settings.value.decompilerWidth == 0) current?.let { if (program.isCode(it)) decompileAt(it) }
    }

    private fun decompileAt(address: Long) {
        val fn = program.function(address) ?: program.functionContaining(address)
        val start = fn?.address ?: address
        val s = settings.value
        val width = effectiveWidth()
        val key = "$start:$width:${s.readonlyPropagate}"
        cache.get(key)?.let {
            decomp = DecompState.Ready(it, fn)
            return
        }
        decompJob?.cancel()
        decomp = DecompState.Loading(start, fn)
        decompJob = scope.launch {
            val raw = program.decompileRaw(start, DecompileOptions(width, 2, s.readonlyPropagate))
            raw.onSuccess { text ->
                val parsed = withContext(Dispatchers.Default) { CCodeParser.parse(start, text) }
                cache.put(key, parsed)
                decomp = DecompState.Ready(parsed, fn)
            }.onFailure {
                decomp = DecompState.Failed(start, fn, it.message ?: it.toString())
            }
        }
    }

    fun clearCache() {
        cache.evictAll()
    }

    fun showInHex(address: Long, length: Int) {
        val off = program.binary.fileOffsetOf(address) ?: return
        hexTarget = off to length.coerceAtLeast(1)
        tab = WsTab.HEX
    }

    fun findReferences(target: Long, title: String) {
        refsJob?.cancel()
        refs = RefsState(target, title)
        refsJob = scope.launch {
            val data = withContext(Dispatchers.Default) { program.findDataReferences(target) }
            val dataRefs = data.map { RefResult(it, program.functionContaining(it), program.nameAt(it) ?: "", isData = true) }
            refs = refs?.copy(results = dataRefs)
            // Literal pools and pointer tables are referenced in turn: search for both.
            val targets = listOf(target) + data.filter { !program.isCode(it) || program.language.processor == "ARM" }.take(8)
            val found = LinkedHashSet<Long>()
            targets.forEachIndexed { i, t ->
                val hits = program.findCodeReferences(t) { p ->
                    refs = refs?.copy(progress = (i + p) / targets.size)
                }
                found += hits
            }
            val codeRefs = found.sortedWith { a, b -> Addr.cmp(a, b) }.map { a ->
                val insn = program.disassemble(a, 1).firstOrNull()
                RefResult(a, program.functionContaining(a), insn?.let { "${it.mnemonic} ${it.operands}" } ?: "", isData = false)
            }
            refs = refs?.copy(results = codeRefs + dataRefs, progress = 1f, done = true)
        }
    }

    fun cancelReferences() {
        refsJob?.cancel()
        refs = null
    }
}
