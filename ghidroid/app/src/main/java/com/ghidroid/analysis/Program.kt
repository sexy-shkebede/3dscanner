package com.ghidroid.analysis

import com.ghidroid.engine.CompilerSpec
import com.ghidroid.engine.Engine
import com.ghidroid.engine.Language
import com.ghidroid.engine.NativeBridge
import com.ghidroid.loader.Addr
import com.ghidroid.loader.Bin
import com.ghidroid.loader.LoadedBinary
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.Closeable
import java.io.File

enum class FunctionKind { ENTRY, EXPORTED, LOCAL, IMPORT, DISCOVERED }

data class FunctionInfo(
    val address: Long,
    /** Name handed to the decompiler (C identifier-ish, demangled without parameters). */
    val name: String,
    /** Full name for lists (demangled signature when available). */
    val displayName: String,
    val size: Long,
    val kind: FunctionKind,
    val mangled: String? = null,
)

data class StringInfo(val address: Long, val text: String, val byteLength: Int, val wide: Boolean, val section: String?)

data class Instruction(
    val address: Long,
    val length: Int,
    val mnemonic: String,
    val operands: String,
    val flow: Int,
    val target: Long,
    val ref: Long,
) {
    val isUnconditional: Boolean get() = flow == FLOW_JUMP || flow == FLOW_IJUMP || flow == FLOW_RETURN

    companion object {
        const val FLOW_NONE = 0
        const val FLOW_JUMP = 1
        const val FLOW_CJUMP = 2
        const val FLOW_CALL = 3
        const val FLOW_IJUMP = 4
        const val FLOW_ICALL = 5
        const val FLOW_RETURN = 6
        const val FLOW_INVALID = 7
    }
}

data class DecompileOptions(val maxLineWidth: Int = 100, val indent: Int = 2, val readonlyPropagate: Boolean = true)

/**
 * An opened binary: the parsed container, the native Ghidra session and the symbol tables the
 * analysis builds up. Native calls go through [Engine]'s thread.
 */
class Program(
    val file: File,
    val displayName: String,
    val bin: Bin,
    val binary: LoadedBinary,
    val language: Language,
    val compiler: CompilerSpec,
    private var handle: Long,
    val alignment: Int,
    val pointerSize: Int,
    val languageDescription: String,
) : Closeable {
    private val _functions = MutableStateFlow<List<FunctionInfo>>(emptyList())
    val functions: StateFlow<List<FunctionInfo>> = _functions.asStateFlow()

    private val _strings = MutableStateFlow<List<StringInfo>>(emptyList())
    val strings: StateFlow<List<StringInfo>> = _strings.asStateFlow()

    /** Imports by pointer slot. */
    @Volatile
    var importSlots: Map<Long, String> = emptyMap()
        internal set

    @Volatile
    private var funcByAddr: Map<Long, FunctionInfo> = emptyMap()

    @Volatile
    private var sortedStarts: LongArray = LongArray(0)

    @Volatile
    private var labels: Map<Long, String> = emptyMap()

    @Volatile
    private var stringByAddr: Map<Long, StringInfo> = emptyMap()

    val isOpen: Boolean get() = handle != 0L

    internal fun setFunctions(list: List<FunctionInfo>) {
        val sorted = list.sortedWith { a, b -> Addr.cmp(a.address, b.address) }
        funcByAddr = sorted.associateBy { it.address }
        sortedStarts = LongArray(sorted.size) { sorted[it].address }
        _functions.value = sorted
    }

    internal fun setLabels(map: Map<Long, String>) {
        labels = map
    }

    internal fun setStrings(list: List<StringInfo>) {
        stringByAddr = list.associateBy { it.address }
        _strings.value = list
    }

    fun function(address: Long): FunctionInfo? = funcByAddr[address]

    /** The function whose body most likely contains [address] (nearest start at or before it). */
    fun functionContaining(address: Long): FunctionInfo? {
        val starts = sortedStarts
        var lo = 0
        var hi = starts.size - 1
        var best = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (Addr.cmp(starts[mid], address) <= 0) {
                best = mid
                lo = mid + 1
            } else hi = mid - 1
        }
        if (best < 0) return null
        val f = funcByAddr[starts[best]] ?: return null
        if (f.kind == FunctionKind.IMPORT && f.address != address) return null
        if (f.size > 0 && !Addr.inRange(address, f.address, f.size)) return null
        val block = binary.blockAt(f.address)
        if (block == null || address !in block) return null
        return f
    }

    fun nextFunctionStart(address: Long): Long? {
        val starts = sortedStarts
        var lo = 0
        var hi = starts.size - 1
        var res: Long? = null
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (Addr.cmp(starts[mid], address) > 0) {
                res = starts[mid]
                hi = mid - 1
            } else lo = mid + 1
        }
        return res
    }

    /** Best human-readable name for an address: function, import slot, label or string. */
    fun nameAt(address: Long): String? =
        funcByAddr[address]?.displayName ?: importSlots[address]?.let { "→ $it" } ?: labels[address]

    fun shortNameAt(address: Long): String? = funcByAddr[address]?.name ?: importSlots[address] ?: labels[address]

    fun stringAt(address: Long): StringInfo? = stringByAddr[address]

    fun isMapped(address: Long): Boolean = binary.blockAt(address) != null

    fun isCode(address: Long): Boolean = binary.isExecutable(address)

    suspend fun disassemble(address: Long, maxCount: Int, end: Long = -1L): List<Instruction> = Engine.call {
        val h = handle
        if (h == 0L) return@call emptyList()
        val r = NativeBridge.disassemble(h, address, maxCount, end)
        val addrs = r[0] as LongArray
        val lens = r[1] as IntArray
        val flows = r[2] as IntArray
        val targets = r[3] as LongArray
        val refs = r[4] as LongArray
        @Suppress("UNCHECKED_CAST") val mn = r[5] as Array<String>
        @Suppress("UNCHECKED_CAST") val ops = r[6] as Array<String>
        List(addrs.size) { i -> Instruction(addrs[i], lens[i], mn[i], ops[i], flows[i], targets[i], refs[i]) }
    }

    suspend fun decompileRaw(address: Long, options: DecompileOptions): Result<String> = Engine.call {
        val h = handle
        if (h == 0L) return@call Result.failure(IllegalStateException("closed"))
        val out = NativeBridge.decompile(h, address, options.maxLineWidth, options.indent, options.readonlyPropagate)
        if (out != null) Result.success(out) else Result.failure(DecompileException(NativeBridge.lastError()))
    }

    /** Code locations referencing [target], from every executable block. */
    suspend fun findCodeReferences(target: Long, onProgress: (Float) -> Unit = {}): List<Long> {
        val blocks = binary.blocks.filter { it.exec }
        val total = blocks.sumOf { it.memSize }.coerceAtLeast(1)
        var done = 0L
        val out = ArrayList<Long>()
        for (b in blocks) {
            var p = b.address
            while (Addr.cmp(p, b.end) < 0) {
                val chunkEnd = if (Addr.cmp(b.end - p, CHUNK) > 0) p + CHUNK else b.end
                val hits = Engine.call { if (handle == 0L) LongArray(0) else NativeBridge.findRefs(handle, p, chunkEnd, target) }
                hits.forEach { out += it }
                done += chunkEnd - p
                onProgress(done.toFloat() / total)
                p = chunkEnd
            }
        }
        return out
    }

    /** Pointer-sized values equal to [target] stored in data (vtables, literal pools, pointer tables). */
    fun findDataReferences(target: Long, limit: Int = 500): List<Long> {
        val out = ArrayList<Long>()
        val ps = pointerSize.coerceIn(2, 8)
        for (b in binary.blocks) {
            if (b.fileSize < ps) continue
            var off = 0L
            val align = ps.toLong()
            while (off + ps <= b.fileSize && out.size < limit) {
                val fo = b.fileOffset + off
                val v = when (ps) {
                    8 -> bin.u64(fo)
                    4 -> bin.u32(fo)
                    else -> bin.u16(fo).toLong()
                }
                if (v == target || (language.processor == "ARM" && v == (target or 1L))) out += b.address + off
                off += align
            }
        }
        return out
    }

    override fun close() {
        val h = handle
        handle = 0
        if (h != 0L) {
            // Native teardown must happen on the engine thread, after anything still queued there.
            Engine.post { NativeBridge.close(h) }
        }
        runCatching { bin.close() }
    }

    internal suspend fun addNativeSymbols(syms: List<NativeSymbol>) {
        if (syms.isEmpty()) return
        val addrs = LongArray(syms.size) { syms[it].address }
        val sizes = LongArray(syms.size) { syms[it].size }
        val kinds = IntArray(syms.size) { syms[it].kind }
        val names = Array(syms.size) { syms[it].name }
        Engine.call { if (handle != 0L) NativeBridge.addSymbols(handle, addrs, sizes, kinds, names) }
    }

    internal suspend fun resolveStubs(start: Long, end: Long, align: Int): LongArray =
        Engine.call { if (handle == 0L) LongArray(0) else NativeBridge.resolveStubs(handle, start, end, align) }

    internal suspend fun scanCalls(start: Long, end: Long): LongArray =
        Engine.call { if (handle == 0L) LongArray(0) else NativeBridge.scanCalls(handle, start, end) }

    companion object {
        const val CHUNK = 0x10000L
    }
}

class DecompileException(message: String) : Exception(message)

internal data class NativeSymbol(val address: Long, val size: Long, val kind: Int, val name: String)
