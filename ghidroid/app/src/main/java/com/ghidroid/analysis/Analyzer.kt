package com.ghidroid.analysis

import com.ghidroid.engine.NativeBridge
import com.ghidroid.loader.Addr
import com.ghidroid.loader.AddressRange
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

enum class AnalysisStage { IDLE, STRINGS, FUNCTIONS, DONE }

data class AnalysisState(val stage: AnalysisStage = AnalysisStage.IDLE, val progress: Float = 0f, val discovered: Int = 0)

/** Background auto-analysis: string search, then function discovery by sweeping for call targets. */
class Analyzer(private val program: Program) {
    private val _state = MutableStateFlow(AnalysisState())
    val state: StateFlow<AnalysisState> = _state.asStateFlow()

    suspend fun run(discoverFunctions: Boolean, minStringLength: Int) {
        _state.value = AnalysisState(AnalysisStage.STRINGS, 0f)
        val strings = withContext(Dispatchers.Default) {
            StringScanner.scan(program, minStringLength) { _state.value = AnalysisState(AnalysisStage.STRINGS, it) }
        }
        program.setStrings(strings)
        program.addNativeSymbols(stringSymbols(strings))

        if (discoverFunctions) discover()
        _state.value = _state.value.copy(stage = AnalysisStage.DONE, progress = 1f)
    }

    private fun stringSymbols(strings: List<StringInfo>): List<NativeSymbol> {
        val out = ArrayList<NativeSymbol>()
        for (s in strings) {
            if (out.size >= 100_000) break
            if (s.wide || s.byteLength < 2) continue
            val block = program.binary.blockAt(s.address) ?: continue
            // Untyped labels (assembler locals such as Mach-O "l_.str") may share the address.
            if (block.write || program.function(s.address) != null || program.importSlots.containsKey(s.address)) continue
            out += NativeSymbol(s.address, s.byteLength.toLong(), NativeBridge.SYM_STRING, stringLabel(s, program.pointerSize))
        }
        return out
    }

    private suspend fun discover() {
        val b = program.binary
        val skip = b.stubRanges
        val ranges = ArrayList<AddressRange>()
        val execSections = b.sections.filter { it.exec && it.alloc && it.size > 0 && it.fileSize > 0 }
        if (execSections.isNotEmpty()) execSections.forEach { ranges += AddressRange(it.address, it.address + it.size) }
        else b.blocks.filter { it.exec && it.fileSize > 0 }.forEach { ranges += AddressRange(it.address, it.address + it.fileSize) }
        val work = ranges.filter { r -> skip.none { it.start == r.start } }
        val total = work.sumOf { it.end - it.start }.coerceAtLeast(1)
        var done = 0L
        val found = HashSet<Long>()
        val known = program.functions.value.associateBy { it.address }
        var pending = ArrayList<Long>()

        suspend fun flush() {
            if (pending.isEmpty()) return
            val fresh = pending.filter { it !in known }
            pending = ArrayList()
            if (fresh.isEmpty()) return
            val infos = fresh.map {
                val n = SymbolSetup.defaultName(it, program.pointerSize)
                FunctionInfo(it, n, n, 0, FunctionKind.DISCOVERED)
            }
            program.addNativeSymbols(infos.map { NativeSymbol(it.address, 0, NativeBridge.SYM_FUNCTION, it.name) })
            program.setFunctions(program.functions.value + infos)
        }

        _state.value = AnalysisState(AnalysisStage.FUNCTIONS, 0f, 0)
        for (r in work) {
            var p = r.start
            while (Addr.cmp(p, r.end) < 0) {
                coroutineContext.ensureActive()
                val end = if (Addr.cmp(r.end - p, Program.CHUNK) > 0) p + Program.CHUNK else r.end
                for (t in program.scanCalls(p, end)) {
                    if (found.add(t) && t !in known && program.function(t) == null) pending += t
                }
                done += end - p
                p = end
                _state.value = AnalysisState(AnalysisStage.FUNCTIONS, done.toFloat() / total, found.count { it !in known })
                if (pending.size >= 256) flush()
            }
        }
        flush()
    }

    companion object {
        fun stringLabel(s: StringInfo, pointerSize: Int): String {
            val sb = StringBuilder("s_")
            for (c in s.text) {
                if (sb.length >= 26) break
                sb.append(if (c.isLetterOrDigit() && c.code < 128) c else '_')
            }
            return sb.toString().trimEnd('_') + "_" + Addr.hex(s.address, pointerSize.coerceIn(2, 8) * 2)
        }
    }
}

object StringScanner {
    private const val CHUNK = 1 shl 20
    private const val OVERLAP = 4096
    private const val MAX_STRINGS = 250_000

    fun scan(program: Program, minLength: Int, onProgress: (Float) -> Unit): List<StringInfo> {
        val b = program.binary
        data class Region(val name: String, val address: Long, val fileOffset: Long, val size: Long)
        var regions = b.sections
            .filter { it.alloc && !it.exec && it.fileSize > 0 && it.name != "HEADER" }
            .map { Region(it.name, it.address, it.fileOffset, it.fileSize) }
        if (regions.isEmpty()) regions = b.blocks.filter { it.fileSize > 0 }.map { Region(it.name, it.address, it.fileOffset, it.fileSize) }
        val total = regions.sumOf { it.size }.coerceAtLeast(1)
        var done = 0L
        val out = ArrayList<StringInfo>()
        val min = minLength.coerceIn(3, 64)
        for (r in regions) {
            var off = 0L
            while (off < r.size && out.size < MAX_STRINGS) {
                val nominal = minOf(CHUNK.toLong(), r.size - off).toInt()
                val data = program.bin.bytesClamped(r.fileOffset + off, minOf(r.size - off, nominal.toLong() + OVERLAP).toInt())
                // A string running over the previous chunk boundary was already reported in full.
                val skip = off > 0 && program.bin.byteAt(r.fileOffset + off - 1).toInt() != 0
                scanUtf8(data, nominal, min, skip) { start, len, terminated, text ->
                    out += StringInfo(r.address + off + start, text, len + if (terminated) 1 else 0, wide = false, section = r.name)
                }
                scanUtf16(data, nominal, min, (r.fileOffset + off) % 2 == 0L) { start, len, text ->
                    out += StringInfo(r.address + off + start, text, len + 2, wide = true, section = r.name)
                }
                off += nominal
                done += nominal
                onProgress(done.toFloat() / total)
            }
        }
        out.sortWith { x, y -> Addr.cmp(x.address, y.address) }
        return out
    }

    private inline fun scanUtf8(data: ByteArray, limit: Int, min: Int, skipLeading: Boolean, emit: (Int, Int, Boolean, String) -> Unit) {
        var i = 0
        if (skipLeading) {
            while (i < limit && data[i].toInt() != 0) i++
        }
        while (i < limit) {
            val start = i
            var chars = 0
            var letters = 0
            while (i < data.size) {
                val c = data[i].toInt() and 0xFF
                if (c in 0x20..0x7E || c == 9 || c == 10 || c == 13) {
                    if ((c or 0x20) in 'a'.code..'z'.code || c in '0'.code..'9'.code || c == ' '.code) letters++
                    i++
                    chars++
                } else if (c in 0xC2..0xF4) {
                    val n = if (c < 0xE0) 2 else if (c < 0xF0) 3 else 4
                    if (i + n > data.size) break
                    var ok = true
                    for (k in 1 until n) if ((data[i + k].toInt() and 0xC0) != 0x80) ok = false
                    if (!ok) break
                    i += n
                    chars++
                    letters++
                } else break
            }
            if (i == start) {
                i++
                continue
            }
            val terminated = i < data.size && data[i].toInt() == 0
            if (chars >= min && terminated && letters * 2 >= chars) {
                emit(start, i - start, true, String(data, start, i - start, Charsets.UTF_8))
            }
        }
    }

    private inline fun scanUtf16(data: ByteArray, limit: Int, min: Int, evenBase: Boolean, emit: (Int, Int, String) -> Unit) {
        var i = if (evenBase) 0 else 1
        while (i + 1 < limit) {
            val start = i
            val sb = StringBuilder()
            while (i + 1 < data.size) {
                val lo = data[i].toInt() and 0xFF
                val hi = data[i + 1].toInt() and 0xFF
                val ch = lo or (hi shl 8)
                val ok = (hi == 0 && (lo in 0x20..0x7E || lo == 9 || lo == 10 || lo == 13)) || hi == 0x04 || (hi == 0 && lo in 0xA0..0xFF)
                if (!ok) break
                sb.append(ch.toChar())
                i += 2
            }
            if (i == start) {
                i += 2
                continue
            }
            val terminated = i + 1 < data.size && data[i].toInt() == 0 && data[i + 1].toInt() == 0
            if (sb.length >= min && terminated) emit(start, i - start, sb.toString())
        }
    }
}
