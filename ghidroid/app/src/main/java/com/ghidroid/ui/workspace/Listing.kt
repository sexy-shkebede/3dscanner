package com.ghidroid.ui.workspace

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.ghidroid.analysis.FunctionInfo
import com.ghidroid.analysis.Instruction
import com.ghidroid.analysis.Program
import com.ghidroid.loader.Addr
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

sealed class ListingRow(val address: Long) {
    abstract val key: String

    class SectionStart(address: Long, val name: String) : ListingRow(address) {
        override val key get() = "s$address"
    }

    class FunctionStart(address: Long, val info: FunctionInfo) : ListingRow(address) {
        override val key get() = "f$address"
    }

    class Label(address: Long, val name: String) : ListingRow(address) {
        override val key get() = "l$address"
    }

    class Code(val insn: Instruction) : ListingRow(insn.address) {
        override val key get() = "c$address"
    }

    class Data(address: Long, val size: Int, val kind: DataKind, val text: String, val pointer: Long = -1L) : ListingRow(address) {
        override val key get() = "d$address"
    }

    class Gap(address: Long) : ListingRow(address) {
        override val key get() = "g$address"
    }
}

enum class DataKind { STRING, WSTRING, POINTER, BYTES, UNINITIALIZED }

/** A region of the listing that is rendered the same way (code or data). */
private data class Region(val start: Long, val end: Long, val exec: Boolean, val name: String, val fileBacked: Long)

/**
 * A window of the program's listing around the current location, grown in either direction as
 * the user scrolls.
 */
class ListingModel(private val program: Program, private val scope: CoroutineScope) {
    var rows by mutableStateOf<List<ListingRow>>(emptyList())
        private set
    /** Row index to bring into view after a navigation (consumed by the UI). */
    var scrollTarget by mutableStateOf<Pair<Int, Long>?>(null)
    var loading by mutableStateOf(false)
        private set
    /** Number of rows just prepended, so the UI can keep its scroll position. */
    var prepended by mutableStateOf(0)
        private set

    private var windowStart = 0L
    private var windowEnd = 0L
    private var atEnd = false
    private var atStart = false
    private val mutex = Mutex()
    private var job: Job? = null

    private val regions: List<Region> by lazy { buildRegions() }

    private fun buildRegions(): List<Region> {
        val b = program.binary
        val out = ArrayList<Region>()
        val secs = b.sections.filter { it.alloc && it.size > 0 && b.blockAt(it.address) != null && it.name != "HEADER" }
            .sortedWith { x, y -> Addr.cmp(x.address, y.address) }
        if (secs.isNotEmpty()) {
            var last = Long.MIN_VALUE
            for (s in secs) {
                if (last != Long.MIN_VALUE && Addr.cmp(s.address, last) < 0) continue  // overlapping (TLS, nested)
                out += Region(s.address, s.address + s.size, s.exec, s.name, if (s.fileSize > 0) s.fileSize else 0)
                last = s.address + s.size
            }
        } else {
            b.blocks.sortedWith { x, y -> Addr.cmp(x.address, y.address) }
                .forEach { out += Region(it.address, it.end, it.exec, it.name, it.fileSize) }
        }
        return out
    }

    private fun regionAt(addr: Long): Region? = regions.firstOrNull { Addr.inRange(addr, it.start, it.end - it.start) }

    private fun nextRegion(addr: Long): Region? = regions.firstOrNull { Addr.cmp(it.start, addr) >= 0 }

    private fun prevRegion(addr: Long): Region? = regions.lastOrNull { Addr.cmp(it.end, addr) <= 0 }

    fun open(address: Long) {
        job?.cancel()
        job = scope.launch {
            mutex.withLock {
                loading = true
                try {
                    val region = regionAt(address) ?: nextRegion(address)
                    if (region == null) {
                        rows = emptyList()
                        return@withLock
                    }
                    val anchor = if (regionAt(address) != null) address else region.start
                    // Start a little before the target: at its function for code, a few rows up for data.
                    val fn = program.functionContaining(anchor)
                    var start = when {
                        region.exec && fn != null && Addr.cmp(anchor - fn.address, 0x4000) < 0 -> fn.address
                        region.exec -> anchor
                        else -> maxOf(region.start, anchor - 64) .let { if (Addr.cmp(it, region.start) < 0) region.start else it }
                    }
                    if (Addr.cmp(start, region.start) < 0) start = region.start
                    val built = ArrayList<ListingRow>()
                    val end = load(region, start, built, if (region.exec) 400 else 200, mustReach = anchor)
                    windowStart = start
                    windowEnd = end
                    atEnd = false
                    atStart = false
                    prepended = 0
                    rows = built
                    val idx = built.indexOfFirst { it.address == anchor && (it is ListingRow.Code || it is ListingRow.Data) }
                        .let { i -> if (i < 0) built.indexOfFirst { Addr.cmp(it.address, anchor) >= 0 } else i }
                    // Show the function header too when jumping to a function start.
                    val headerIdx = if (idx > 0 && built[idx - 1].address == anchor) {
                        var j = idx
                        while (j > 0 && built[j - 1].address == anchor) j--
                        j
                    } else idx
                    scrollTarget = maxOf(0, headerIdx) to anchor
                } finally {
                    loading = false
                }
            }
        }
    }

    fun loadMore() {
        if (atEnd || loading) return
        scope.launch {
            mutex.withLock {
                if (atEnd) return@withLock
                loading = true
                try {
                    var region = regionAt(windowEnd)
                    if (region == null) {
                        region = nextRegion(windowEnd)
                        if (region == null) {
                            atEnd = true
                            return@withLock
                        }
                        windowEnd = region.start
                    }
                    val built = ArrayList<ListingRow>()
                    val end = load(region, windowEnd, built, if (region.exec) 300 else 200)
                    if (end == windowEnd) {
                        atEnd = nextRegion(region.end) == null
                        windowEnd = region.end
                    } else windowEnd = end
                    if (built.isNotEmpty()) rows = rows + built
                } finally {
                    loading = false
                }
            }
        }
    }

    fun loadPrevious() {
        if (atStart || loading) return
        scope.launch {
            mutex.withLock {
                if (atStart) return@withLock
                loading = true
                try {
                    var region = regionAt(windowStart)
                    var target = windowStart
                    if (region == null || region.start == windowStart) {
                        region = prevRegion(windowStart)
                        if (region == null) {
                            atStart = true
                            return@withLock
                        }
                        target = region.end
                    }
                    val span = if (region.exec) 0x200L else 0x100L
                    val from = if (Addr.cmp(target - region.start, span) > 0) target - span else region.start
                    val built = ArrayList<ListingRow>()
                    if (region.exec) {
                        val fn = program.functionContaining(target - 1)
                        val start = if (fn != null && Addr.cmp(fn.address, from) >= 0 && Addr.cmp(fn.address, region.start) >= 0) fn.address else from
                        loadCodeExact(region, start, target, built)
                        windowStart = start
                    } else {
                        loadData(region, from, target, built, Int.MAX_VALUE)
                        windowStart = from
                    }
                    // Drop a duplicated header if the old first rows already showed it.
                    val first = rows.firstOrNull()
                    if (first != null) built.removeAll { it.key == first.key }
                    if (built.isNotEmpty()) {
                        prepended = built.size
                        rows = built + rows
                    }
                } finally {
                    loading = false
                }
            }
        }
    }

    fun consumePrepended() {
        prepended = 0
    }

    /** Fills [out] from [start]; returns the address after the last row. */
    private suspend fun load(region: Region, start: Long, out: MutableList<ListingRow>, count: Int, mustReach: Long? = null): Long {
        return if (region.exec) {
            var end = loadCode(region, start, out, count)
            if (mustReach != null) {
                while (Addr.cmp(end, mustReach) <= 0 && Addr.cmp(end, region.end) < 0) {
                    val e2 = loadCode(region, end, out, count)
                    if (e2 == end) break
                    end = e2
                }
            }
            end
        } else {
            var end = loadData(region, start, region.end, out, count)
            if (mustReach != null) {
                while (Addr.cmp(end, mustReach) <= 0 && Addr.cmp(end, region.end) < 0) {
                    val e2 = loadData(region, end, region.end, out, count)
                    if (e2 == end) break
                    end = e2
                }
            }
            end
        }
    }

    private suspend fun loadCode(region: Region, start: Long, out: MutableList<ListingRow>, count: Int): Long {
        val insns = program.disassemble(start, count, region.end)
        if (insns.isEmpty()) return start
        appendCode(region, insns, out)
        val last = insns.last()
        return last.address + last.length
    }

    /** Disassembles [start, end) making sure the last instruction ends exactly at [end] (variable-length ISAs). */
    private suspend fun loadCodeExact(region: Region, start: Long, end: Long, out: MutableList<ListingRow>) {
        var from = start
        repeat(8) { attempt ->
            val insns = program.disassemble(from, 4096, end)
            val last = insns.lastOrNull()
            if (last != null && last.address + last.length == end) {
                appendCode(region, insns, out)
                return
            }
            if (attempt == 7 || insns.isEmpty()) {
                appendCode(region, insns.filter { Addr.cmp(it.address + it.length, end) <= 0 }, out)
                return
            }
            from += program.alignment.coerceAtLeast(1)
        }
    }

    private fun appendCode(region: Region, insns: List<Instruction>, out: MutableList<ListingRow>) {
        for (insn in insns) {
            header(region, insn.address, out)
            out += ListingRow.Code(insn)
            if (insn.isUnconditional) out += ListingRow.Gap(insn.address)
        }
    }

    private fun header(region: Region, addr: Long, out: MutableList<ListingRow>) {
        if (addr == region.start) out += ListingRow.SectionStart(addr, region.name)
        val f = program.function(addr)
        if (f != null) out += ListingRow.FunctionStart(addr, f)
        else program.shortNameAt(addr)?.let { if (program.importSlots[addr] == null) out += ListingRow.Label(addr, it) }
    }

    private fun loadData(region: Region, start: Long, end: Long, out: MutableList<ListingRow>, count: Int): Long {
        val bin = program.bin
        val ps = program.pointerSize.coerceIn(2, 8)
        var a = start
        var n = 0
        val fileEnd = region.start + region.fileBacked
        while (Addr.cmp(a, end) < 0 && n < count) {
            header(region, a, out)
            val remaining = end - a
            if (Addr.cmp(a, fileEnd) >= 0) {
                out += ListingRow.Data(a, remaining.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), DataKind.UNINITIALIZED, "")
                return end
            }
            val fo = program.binary.fileOffsetOf(a)
            if (fo == null) {
                out += ListingRow.Data(a, remaining.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), DataKind.UNINITIALIZED, "")
                return end
            }
            val str = program.stringAt(a)
            if (str != null && Addr.cmp(str.byteLength.toLong(), remaining) <= 0) {
                out += ListingRow.Data(a, str.byteLength, if (str.wide) DataKind.WSTRING else DataKind.STRING, str.text)
                a += str.byteLength
                n++
                continue
            }
            if (a % ps == 0L && Addr.cmp(remaining, ps.toLong()) >= 0 && bin.has(fo, ps.toLong())) {
                val v = if (ps == 8) bin.u64(fo) else if (ps == 4) bin.u32(fo) else bin.u16(fo).toLong()
                val target = if (program.language.processor == "ARM" && program.isCode(v and 1L.inv())) v and 1L.inv() else v
                if (v != 0L && program.isMapped(target) && (program.nameAt(target) != null || program.stringAt(target) != null || program.isCode(target))) {
                    out += ListingRow.Data(a, ps, DataKind.POINTER, "", target)
                    a += ps
                    n++
                    continue
                }
            }
            // Raw bytes up to the next alignment boundary / label / string.
            var len = 1
            while (len < 8 && Addr.cmp(len.toLong(), remaining) < 0) {
                val next = a + len
                if (next % 8 == 0L || program.stringAt(next) != null || program.shortNameAt(next) != null || program.function(next) != null) break
                len++
            }
            val bytes = bin.bytesClamped(fo, len)
            out += ListingRow.Data(a, len, DataKind.BYTES, bytes.joinToString(" ") { "%02x".format(it) })
            a += len
            n++
        }
        return a
    }
}
