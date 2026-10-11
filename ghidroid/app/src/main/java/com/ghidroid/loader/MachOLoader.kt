package com.ghidroid.loader

/** Mach-O executables, dylibs and bundles (thin or universal). */
object MachOLoader {
    private const val MH_MAGIC = 0xFEEDFACEL
    private const val MH_MAGIC_64 = 0xFEEDFACFL
    private const val FAT_MAGIC = 0xCAFEBABEL

    private const val CPU_X86 = 7
    private const val CPU_X86_64 = 0x01000007
    private const val CPU_ARM = 12
    private const val CPU_ARM64 = 0x0100000C
    private const val CPU_ARM64_32 = 0x0200000C
    private const val CPU_PPC = 18
    private const val CPU_PPC64 = 0x01000012

    private class MSec(val seg: String, val name: String, val addr: Long, val size: Long, val offset: Long, val flags: Long, val r1: Long, val r2: Long)

    fun matches(bin: Bin): Boolean {
        if (bin.size < 28) return false
        bin.littleEndian = false
        val be = bin.u32(0)
        if (be == FAT_MAGIC) {
            val n = bin.u32(4)
            return n in 1..32  // Java class files share the magic but have a large version here
        }
        return be == MH_MAGIC || be == MH_MAGIC_64 || be == 0xCEFAEDFEL || be == 0xCFFAEDFEL
    }

    fun load(whole: Bin): LoadedBinary {
        whole.littleEndian = false
        var sliceOff = 0L
        var sliceSize = whole.size
        val slices = ArrayList<String>()
        if (whole.u32(0) == FAT_MAGIC) {
            val n = whole.u32(4).toInt()
            data class Arch(val cpu: Int, val off: Long, val size: Long)
            val archs = (0 until n).map { i ->
                val o = 8L + i * 20
                Arch(whole.u32(o).toInt(), whole.u32(o + 8), whole.u32(o + 12))
            }
            archs.forEach { slices += cpuName(it.cpu) }
            val pick = archs.firstOrNull { it.cpu == CPU_ARM64 } ?: archs.firstOrNull { it.cpu == CPU_X86_64 } ?: archs.first()
            sliceOff = pick.off
            sliceSize = pick.size
        }
        val bin = whole.slice(sliceOff, minOf(sliceSize, whole.size - sliceOff))
        bin.littleEndian = true
        var magic = bin.u32(0)
        if (magic != MH_MAGIC && magic != MH_MAGIC_64) {
            bin.littleEndian = false
            magic = bin.u32(0)
        }
        val is64 = magic == MH_MAGIC_64
        val cpu = bin.u32(4).toInt()
        val fileType = bin.u32(12).toInt()
        val ncmds = bin.u32(16).toInt()
        val hdr = if (is64) 32L else 28L

        val blocks = ArrayList<MemBlock>()
        val msecs = ArrayList<MSec>()
        val libraries = ArrayList<String>()
        var symoff = 0L; var nsyms = 0L; var stroff = 0L; var strsize = 0L
        var indirectOff = 0L; var nIndirect = 0L
        var entryOff: Long? = null
        var funcStartsOff = 0L; var funcStartsSize = 0L
        var uuid: String? = null
        var installName: String? = null
        var textVmaddr = 0L
        var textFileoff = 0L

        var p = hdr
        for (c in 0 until ncmds) {
            if (!bin.has(p, 8)) break
            val cmd = bin.u32(p)
            val size = bin.u32(p + 4)
            if (size < 8) break
            when (cmd) {
                0x1L, 0x19L -> {
                    val seg64 = cmd == 0x19L
                    val segname = bin.fixedString(p + 8, 16)
                    val vmaddr: Long; val vmsize: Long; val fileoff: Long; val filesize: Long; val initprot: Long; val nsects: Long
                    var q: Long
                    if (seg64) {
                        vmaddr = bin.u64(p + 24); vmsize = bin.u64(p + 32); fileoff = bin.u64(p + 40); filesize = bin.u64(p + 48)
                        initprot = bin.u32(p + 60); nsects = bin.u32(p + 64); q = p + 72
                    } else {
                        vmaddr = bin.u32(p + 24); vmsize = bin.u32(p + 28); fileoff = bin.u32(p + 32); filesize = bin.u32(p + 36)
                        initprot = bin.u32(p + 44); nsects = bin.u32(p + 48); q = p + 56
                    }
                    if (segname == "__TEXT") { textVmaddr = vmaddr; textFileoff = fileoff }
                    if (initprot != 0L && vmsize > 0) blocks += MemBlock(
                        segname, vmaddr, sliceOff + fileoff, minOf(filesize, vmsize, maxOf(0, bin.size - fileoff)), vmsize,
                        read = initprot and 1L != 0L, write = initprot and 2L != 0L, exec = initprot and 4L != 0L,
                    )
                    for (s in 0 until nsects) {
                        if (seg64) {
                            msecs += MSec(bin.fixedString(q + 16, 16), bin.fixedString(q, 16), bin.u64(q + 32), bin.u64(q + 40),
                                bin.u32(q + 48), bin.u32(q + 64), bin.u32(q + 68), bin.u32(q + 72))
                            q += 80
                        } else {
                            msecs += MSec(bin.fixedString(q + 16, 16), bin.fixedString(q, 16), bin.u32(q + 32), bin.u32(q + 36),
                                bin.u32(q + 40), bin.u32(q + 56), bin.u32(q + 60), bin.u32(q + 64))
                            q += 68
                        }
                    }
                }
                0x2L -> { symoff = bin.u32(p + 8); nsyms = bin.u32(p + 12); stroff = bin.u32(p + 16); strsize = bin.u32(p + 20) }
                0xBL -> { indirectOff = bin.u32(p + 56); nIndirect = bin.u32(p + 60) }
                0x80000028L -> entryOff = bin.u64(p + 8)
                0xCL, 0x80000018L, 0x8000001FL, 0x80000023L -> libraries += bin.cstring(p + bin.u32(p + 8), 1024)
                0xDL -> installName = bin.cstring(p + bin.u32(p + 8), 1024)
                0x1BL -> uuid = bin.bytes(p + 8, 16).joinToString("") { "%02X".format(it) }
                0x26L -> { funcStartsOff = bin.u32(p + 8); funcStartsSize = bin.u32(p + 12) }
            }
            p += size
        }
        if (blocks.isEmpty()) throw FormatException("Mach-O file has no segments")

        val execSection = { addr: Long -> msecs.any { it.flags and 0x80000400L != 0L && Addr.inRange(addr, it.addr, it.size) } || blocks.any { it.exec && addr in it } }

        // ---- symbols
        val symbols = ArrayList<BinSymbol>()
        val symNames = ArrayList<String>()
        val entSize = if (is64) 16L else 12L
        for (i in 0 until nsyms.coerceAtMost(4_000_000)) {
            val o = symoff + i * entSize
            if (!bin.has(o, entSize)) break
            val strx = bin.u32(o)
            val type = bin.u8(o + 4)
            val value = if (is64) bin.u64(o + 8) else bin.u32(o + 8)
            var name = if (strx in 1 until strsize) bin.cstring(stroff + strx, 1024) else ""
            if (name.startsWith("_")) name = name.substring(1)
            symNames += name
            if (type and 0xE0 != 0 || name.isEmpty()) continue  // debugging (stab) entries
            if (type and 0x0E == 0x0E) {  // N_SECT
                val ext = type and 0x01 != 0
                if (execSection(value)) symbols += BinSymbol(name, value, 0, SymbolType.FUNCTION, exported = ext)
                else symbols += BinSymbol(name, value, 0, SymbolType.DATA, exported = ext)
            }
        }

        // ---- imports via the indirect symbol table
        val imports = ArrayList<ImportEntry>()
        val stubs = ArrayList<StubEntry>()
        val ps = if (is64) 8L else 4L
        fun indirectName(idx: Long): String? {
            val o = indirectOff + idx * 4
            if (!bin.has(o, 4)) return null
            val sym = bin.u32(o)
            if (sym and 0xC0000000L != 0L) return null
            return symNames.getOrNull(sym.toInt())?.takeIf { it.isNotEmpty() }
        }
        for (s in msecs) {
            when ((s.flags and 0xFF).toInt()) {
                6, 7, 0x10 -> {  // non-lazy / lazy symbol pointers
                    val n = s.size / ps
                    for (i in 0 until n) indirectName(s.r1 + i)?.let { imports += ImportEntry(it, null, s.addr + i * ps) }
                }
                8 -> if (s.r2 > 0) {  // symbol stubs
                    val n = s.size / s.r2
                    for (i in 0 until n) indirectName(s.r1 + i)?.let { stubs += StubEntry(s.addr + i * s.r2, it) }
                }
            }
        }

        // ---- function starts (ULEB128 deltas from __TEXT)
        val functionStarts = ArrayList<Long>()
        if (funcStartsOff != 0L && bin.has(funcStartsOff, funcStartsSize)) {
            var addr = textVmaddr
            var q = funcStartsOff
            val end = funcStartsOff + funcStartsSize
            while (q < end) {
                var v = 0L
                var shift = 0
                while (q < end) {
                    val b = bin.u8(q++)
                    v = v or ((b and 0x7F).toLong() shl shift)
                    shift += 7
                    if (b and 0x80 == 0) break
                }
                if (v == 0L) break
                addr += v
                functionStarts += if (cpu == CPU_ARM) addr and 1L.inv() else addr
            }
        }

        val sections = msecs.map {
            val type = (it.flags and 0xFF).toInt()
            Section(
                name = "${it.seg},${it.name}",
                address = it.addr,
                size = it.size,
                fileOffset = if (type == 1 || type == 0x12) 0 else sliceOff + it.offset,
                fileSize = if (type == 1 || type == 0x12) 0 else it.size,
                alloc = true,
                write = blocks.firstOrNull { b -> it.addr in b }?.write == true,
                exec = it.flags and 0x80000400L != 0L,
                kind = sectionType(type),
            )
        }

        val entry = entryOff?.let { textVmaddr + (it - textFileoff) }
        val arch = cpuArch(cpu)
        val info = ArrayList<Pair<String, String>>()
        if (slices.isNotEmpty()) info += "Universal slices" to slices.joinToString(", ")
        installName?.let { info += "Install name" to it }
        uuid?.let { info += "UUID" to it }

        val contexts = ArrayList<ContextRange>()
        if (cpu == CPU_ARM) blocks.filter { it.exec }.forEach { contexts += ContextRange("TMode", it.address, it.end, 1) }

        return LoadedBinary(
            format = BinaryFormat.MACHO,
            formatName = if (is64) "Mach-O 64" else "Mach-O",
            fileType = when (fileType) {
                1 -> "Object"; 2 -> "Executable"; 6 -> "Dynamic library"; 7 -> "Dynamic linker"; 8 -> "Bundle"
                9 -> "Dylib stub"; 10 -> "Debug symbols"; 11 -> "Kernel extension"
                else -> "Type $fileType"
            },
            machine = arch.first,
            bits = if (is64) 64 else 32,
            bigEndian = !bin.littleEndian,
            languageIds = arch.second,
            compiler = if (cpu == CPU_X86_64 || cpu == CPU_X86) "gcc" else "default",
            imageBase = blocks.filter { it.fileSize > 0 }.minOfOrNull { it.address } ?: blocks.minOf { it.address },
            entryPoints = listOfNotNull(entry?.takeIf { e -> blocks.any { it.exec && e in it } }),
            blocks = blocks,
            sections = sections,
            symbols = symbols,
            imports = imports,
            stubs = stubs,
            libraries = libraries,
            info = info,
            contexts = contexts,
            functionStarts = functionStarts,
        )
    }

    private fun cpuName(cpu: Int): String = cpuArch(cpu).first

    private fun cpuArch(cpu: Int): Pair<String, List<String>> = when (cpu) {
        CPU_X86 -> "x86 (i386)" to listOf("x86:LE:32:default")
        CPU_X86_64 -> "x86-64" to listOf("x86:LE:64:default")
        CPU_ARM -> "ARM" to listOf("ARM:LE:32:v8", "ARM:LE:32:v7")
        CPU_ARM64 -> "ARM64" to listOf("AARCH64:LE:64:AppleSilicon", "AARCH64:LE:64:v8A")
        CPU_ARM64_32 -> "ARM64_32" to listOf("AARCH64:LE:32:ilp32")
        CPU_PPC -> "PowerPC" to listOf("PowerPC:BE:32:default")
        CPU_PPC64 -> "PowerPC64" to listOf("PowerPC:BE:64:default")
        else -> "CPU 0x${Integer.toHexString(cpu)}" to emptyList()
    }

    private fun sectionType(t: Int): String = when (t) {
        0 -> "REGULAR"; 1 -> "ZEROFILL"; 2 -> "CSTRINGS"; 6 -> "NL_POINTERS"; 7 -> "LAZY_POINTERS"; 8 -> "STUBS"
        9 -> "INIT_FUNCS"; 0xB -> "COALESCED"; 0x12 -> "GB_ZEROFILL"; 0x13 -> "INTERPOSING"; 0x10 -> "LAZY_DYLIB_PTRS"
        else -> "0x" + Integer.toHexString(t)
    }
}
