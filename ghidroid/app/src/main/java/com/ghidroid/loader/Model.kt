package com.ghidroid.loader

enum class BinaryFormat(val label: String) { ELF("ELF"), PE("PE"), MACHO("Mach-O"), RAW("Raw") }

/** A run of file bytes mapped into the program's address space. */
data class MemBlock(
    val name: String,
    val address: Long,
    val fileOffset: Long,
    val fileSize: Long,
    val memSize: Long,
    val read: Boolean,
    val write: Boolean,
    val exec: Boolean,
) {
    val end: Long get() = address + memSize
    operator fun contains(addr: Long): Boolean = Addr.inRange(addr, address, memSize)
}

data class Section(
    val name: String,
    val address: Long,
    val size: Long,
    val fileOffset: Long,
    val fileSize: Long,
    val alloc: Boolean,
    val write: Boolean,
    val exec: Boolean,
    val kind: String = "",
) {
    operator fun contains(addr: Long): Boolean = Addr.inRange(addr, address, size)
}

enum class SymbolType { FUNCTION, DATA, LABEL }

data class BinSymbol(
    val name: String,
    val address: Long,
    val size: Long,
    val type: SymbolType,
    val exported: Boolean = false,
)

/** An imported function or variable, reachable through a pointer slot (GOT / IAT entry). */
data class ImportEntry(val name: String, val library: String?, val slot: Long)

/** Import stub found directly by the loader (Mach-O __stubs, PE jump thunks). */
data class StubEntry(val address: Long, val name: String)

data class ContextRange(val variable: String, val start: Long, val end: Long, val value: Int)

data class AddressRange(val start: Long, val end: Long)

data class LoadedBinary(
    val format: BinaryFormat,
    val formatName: String,
    val fileType: String,
    val machine: String,
    val bits: Int,
    val bigEndian: Boolean,
    /** Ghidra language ids in order of preference (resolved against the installed languages). */
    val languageIds: List<String>,
    val compiler: String,
    val imageBase: Long,
    val entryPoints: List<Long>,
    val blocks: List<MemBlock>,
    val sections: List<Section>,
    val symbols: List<BinSymbol>,
    val imports: List<ImportEntry>,
    val stubs: List<StubEntry> = emptyList(),
    val libraries: List<String> = emptyList(),
    val info: List<Pair<String, String>> = emptyList(),
    val contexts: List<ContextRange> = emptyList(),
    /** Ranges holding PLT style stubs that the engine should resolve against [imports]. */
    val stubRanges: List<AddressRange> = emptyList(),
    val stubAlign: Int = 16,
    /** Extra function starts (exception tables, LC_FUNCTION_STARTS, ...). */
    val functionStarts: List<Long> = emptyList(),
    val notes: List<String> = emptyList(),
) {
    fun blockAt(addr: Long): MemBlock? = blocks.firstOrNull { addr in it }

    fun sectionAt(addr: Long): Section? =
        sections.filter { it.alloc && addr in it }.minByOrNull { it.size }

    fun fileOffsetOf(addr: Long): Long? {
        val b = blockAt(addr) ?: return null
        val delta = addr - b.address
        return if (delta in 0 until b.fileSize) b.fileOffset + delta else null
    }

    fun addressOfFileOffset(off: Long): Long? {
        val b = blocks.firstOrNull { off >= it.fileOffset && off < it.fileOffset + it.fileSize } ?: return null
        return b.address + (off - b.fileOffset)
    }

    fun isExecutable(addr: Long): Boolean = blockAt(addr)?.exec == true
}

class FormatException(message: String) : Exception(message)

object Addr {
    /** [addr] in [start, start + size) using unsigned arithmetic. */
    fun inRange(addr: Long, start: Long, size: Long): Boolean {
        val d = addr - start
        return java.lang.Long.compareUnsigned(d, size) < 0
    }

    fun cmp(a: Long, b: Long): Int = java.lang.Long.compareUnsigned(a, b)

    fun hex(v: Long, digits: Int = 0): String {
        val s = java.lang.Long.toHexString(v)
        return if (s.length >= digits) s else "0".repeat(digits - s.length) + s
    }

    fun parse(text: String): Long? {
        val t = text.trim().removePrefix("0x").removePrefix("0X").removeSuffix("h").replace("_", "").replace("`", "")
        if (t.isEmpty() || t.length > 16) return null
        return runCatching { java.lang.Long.parseUnsignedLong(t, 16) }.getOrNull()
    }
}
