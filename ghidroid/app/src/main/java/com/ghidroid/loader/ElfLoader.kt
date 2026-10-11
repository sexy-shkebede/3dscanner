package com.ghidroid.loader

/** ELF executables, shared objects (.so), relocatable objects (.o) and core-less dumps. */
object ElfLoader {
    private const val PT_LOAD = 1L
    private const val PT_DYNAMIC = 2L
    private const val PT_NOTE = 4L
    private const val PT_INTERP = 3L

    private const val SHT_SYMTAB = 2L
    private const val SHT_RELA = 4L
    private const val SHT_NOBITS = 8L
    private const val SHT_REL = 9L
    private const val SHT_DYNSYM = 11L
    private const val SHT_INIT_ARRAY = 14L

    private const val SHF_WRITE = 1L
    private const val SHF_ALLOC = 2L
    private const val SHF_EXEC = 4L

    private const val DT_NEEDED = 1L
    private const val DT_SONAME = 14L
    private const val DT_RUNPATH = 29L

    private class Sh(
        val name: String,
        val type: Long,
        val flags: Long,
        val addr: Long,
        val offset: Long,
        val size: Long,
        val link: Int,
        val info: Long,
        val entsize: Long,
    )

    private class Sym(val name: String, val value: Long, val size: Long, val type: Int, val bind: Int, val shndx: Int)

    fun matches(bin: Bin): Boolean =
        bin.size >= 52 && bin.u8(0) == 0x7F && bin.u8(1) == 'E'.code && bin.u8(2) == 'L'.code && bin.u8(3) == 'F'.code

    fun load(bin: Bin): LoadedBinary {
        val is64 = when (bin.u8(4)) {
            1 -> false
            2 -> true
            else -> throw FormatException("Unknown ELF class")
        }
        bin.littleEndian = bin.u8(5) != 2
        val bigEndian = !bin.littleEndian
        val type = bin.u16(16)
        val machine = bin.u16(18)
        val entry: Long
        val phoff: Long
        val shoff: Long
        val flags: Long
        val phentsize: Int
        val phnum: Int
        val shentsize: Int
        var shnum: Int
        var shstrndx: Int
        if (is64) {
            entry = bin.u64(24); phoff = bin.u64(32); shoff = bin.u64(40); flags = bin.u32(48)
            phentsize = bin.u16(54); phnum = bin.u16(56); shentsize = bin.u16(58); shnum = bin.u16(60); shstrndx = bin.u16(62)
        } else {
            entry = bin.u32(24); phoff = bin.u32(28); shoff = bin.u32(32); flags = bin.u32(36)
            phentsize = bin.u16(42); phnum = bin.u16(44); shentsize = bin.u16(46); shnum = bin.u16(48); shstrndx = bin.u16(50)
        }

        // ---- section headers
        val sections = ArrayList<Sh>()
        if (shoff != 0L && shentsize > 0 && bin.has(shoff, shentsize.toLong())) {
            if (shnum == 0) shnum = readSh(bin, is64, shoff).size.toInt()  // extended numbering
            if (shstrndx == 0xFFFF) shstrndx = readSh(bin, is64, shoff).link
            val raw = (0 until shnum).mapNotNull { i ->
                val off = shoff + i.toLong() * shentsize
                if (bin.has(off, shentsize.toLong())) readSh(bin, is64, off) else null
            }
            val strtab = raw.getOrNull(shstrndx)
            raw.forEach { s ->
                val name = if (strtab != null && strtab.type != SHT_NOBITS) bin.cstring(strtab.offset + s.nameOff, 256) else ""
                sections += Sh(name, s.type, s.flags, s.addr, s.offset, s.size, s.link, s.info, s.entsize)
            }
        }

        // ---- program headers / memory layout
        val blocks = ArrayList<MemBlock>()
        var dynamicOff = -1L
        var dynamicSize = 0L
        var interp: String? = null
        var buildId: String? = null
        if (phoff != 0L && phentsize > 0) {
            for (i in 0 until phnum) {
                val off = phoff + i.toLong() * phentsize
                if (!bin.has(off, phentsize.toLong())) break
                val pType = bin.u32(off)
                val pFlags: Long
                val pOffset: Long
                val pVaddr: Long
                val pFilesz: Long
                val pMemsz: Long
                if (is64) {
                    pFlags = bin.u32(off + 4); pOffset = bin.u64(off + 8); pVaddr = bin.u64(off + 16)
                    pFilesz = bin.u64(off + 32); pMemsz = bin.u64(off + 40)
                } else {
                    pOffset = bin.u32(off + 4); pVaddr = bin.u32(off + 8); pFilesz = bin.u32(off + 16)
                    pMemsz = bin.u32(off + 20); pFlags = bin.u32(off + 24)
                }
                when (pType) {
                    PT_LOAD -> if (pMemsz > 0) blocks += MemBlock(
                        name = "LOAD$i",
                        address = pVaddr,
                        fileOffset = pOffset,
                        fileSize = minOf(pFilesz, maxOf(0, bin.size - pOffset)),
                        memSize = pMemsz,
                        read = pFlags and 4L != 0L,
                        write = pFlags and 2L != 0L,
                        exec = pFlags and 1L != 0L,
                    )
                    PT_DYNAMIC -> { dynamicOff = pOffset; dynamicSize = pFilesz }
                    PT_INTERP -> interp = bin.cstring(pOffset, 256)
                    PT_NOTE -> if (buildId == null) buildId = readBuildId(bin, pOffset, pFilesz)
                }
            }
        }

        // Relocatable objects have no program headers: lay the allocatable sections out ourselves.
        val sectionBase = LongArray(sections.size) { sections[it].addr }
        val relocatable = type == 1
        if (blocks.isEmpty()) {
            var next = if (relocatable) 0x10000L else 0L
            sections.forEachIndexed { i, s ->
                if (s.flags and SHF_ALLOC == 0L || s.size == 0L) return@forEachIndexed
                val addr = if (relocatable || s.addr == 0L) {
                    val align = 16L
                    next = (next + align - 1) / align * align
                    next.also { next += s.size }
                } else s.addr
                sectionBase[i] = addr
                val nobits = s.type == SHT_NOBITS
                blocks += MemBlock(
                    name = s.name.ifEmpty { "section$i" },
                    address = addr,
                    fileOffset = if (nobits) 0 else s.offset,
                    fileSize = if (nobits) 0 else minOf(s.size, maxOf(0, bin.size - s.offset)),
                    memSize = s.size,
                    read = true,
                    write = s.flags and SHF_WRITE != 0L,
                    exec = s.flags and SHF_EXEC != 0L,
                )
            }
        }
        if (blocks.isEmpty()) throw FormatException("ELF file has no loadable segments")

        // ---- symbols
        val symbols = ArrayList<BinSymbol>()
        val thumbFuncs = ArrayList<Pair<Long, Long>>()  // ARM: (address, size) of Thumb functions
        val armFuncs = ArrayList<Pair<Long, Long>>()
        val mapping = ArrayList<Pair<Long, Char>>()  // ARM mapping symbols ($a / $t / $d)
        val isArm = machine == 40
        val symtabs = HashMap<Int, List<Sym>>()
        sections.forEachIndexed { idx, s ->
            if (s.type != SHT_SYMTAB && s.type != SHT_DYNSYM) return@forEachIndexed
            val strtab = sections.getOrNull(s.link) ?: return@forEachIndexed
            val entSize = if (s.entsize > 0) s.entsize else if (is64) 24L else 16L
            val count = (s.size / entSize).coerceAtMost(4_000_000L).toInt()
            val list = ArrayList<Sym>(count)
            for (i in 0 until count) {
                val off = s.offset + i * entSize
                if (!bin.has(off, entSize)) break
                val nameOff: Long
                val value: Long
                val size: Long
                val info: Int
                val shndx: Int
                if (is64) {
                    nameOff = bin.u32(off); info = bin.u8(off + 4); shndx = bin.u16(off + 6)
                    value = bin.u64(off + 8); size = bin.u64(off + 16)
                } else {
                    nameOff = bin.u32(off); value = bin.u32(off + 4); size = bin.u32(off + 8)
                    info = bin.u8(off + 12); shndx = bin.u16(off + 14)
                }
                val name = if (nameOff != 0L) bin.cstring(strtab.offset + nameOff, 1024) else ""
                list += Sym(name, value, size, info and 0xF, info shr 4, shndx)
            }
            symtabs[idx] = list
            for (sym in list) {
                if (sym.shndx == 0 || sym.name.isEmpty()) continue
                var addr = sym.value
                if (relocatable && sym.shndx in sectionBase.indices) addr += sectionBase[sym.shndx]
                if (sym.shndx == 0xFFF1) continue  // SHN_ABS
                if (isArm && sym.name.startsWith("$")) {
                    val c = sym.name.getOrNull(1)
                    if (c == 'a' || c == 't' || c == 'd') mapping += addr to c
                    continue
                }
                if (sym.name.startsWith("\$x") || sym.name.startsWith("\$d")) continue  // AArch64 mapping
                val exported = sym.bind != 0 && s.type == SHT_DYNSYM
                when (sym.type) {
                    2 /* FUNC */, 10 /* GNU_IFUNC */ -> {
                        var fa = addr
                        if (isArm) {
                            if (fa and 1L != 0L) {
                                fa = fa and 1L.inv()
                                thumbFuncs += fa to sym.size
                            } else armFuncs += fa to sym.size
                        }
                        symbols += BinSymbol(stripVersion(sym.name), fa, sym.size, SymbolType.FUNCTION, exported)
                    }
                    1 /* OBJECT */, 6 /* TLS */ -> symbols += BinSymbol(stripVersion(sym.name), addr, sym.size, SymbolType.DATA, exported)
                    0 /* NOTYPE */ -> if (sym.bind != 0 || s.type == SHT_SYMTAB) {
                        val sec = sections.getOrNull(sym.shndx)
                        val exec = sec != null && sec.flags and SHF_EXEC != 0L
                        var a = addr
                        if (isArm && exec && a and 1L != 0L) a = a and 1L.inv()
                        symbols += BinSymbol(stripVersion(sym.name), a, sym.size, if (exec) SymbolType.LABEL else SymbolType.DATA, exported)
                    }
                }
            }
        }

        // ---- imports: relocations against undefined symbols name the slots they patch
        val imports = LinkedHashMap<Long, ImportEntry>()
        if (!relocatable) {
            sections.forEach { s ->
                if (s.type != SHT_RELA && s.type != SHT_REL) return@forEach
                val syms = symtabs[s.link] ?: return@forEach
                val rela = s.type == SHT_RELA
                val entSize = if (s.entsize > 0) s.entsize else if (is64) (if (rela) 24L else 16L) else (if (rela) 12L else 8L)
                val count = (s.size / entSize).coerceAtMost(4_000_000L).toInt()
                for (i in 0 until count) {
                    val off = s.offset + i * entSize
                    if (!bin.has(off, entSize)) break
                    val rOffset: Long
                    val symIdx: Int
                    if (is64) {
                        rOffset = bin.u64(off)
                        val info = bin.u64(off + 8)
                        symIdx = if (machine == 8 && !bigEndian) (info and 0xFFFFFFFFL).toInt() else (info ushr 32).toInt()
                    } else {
                        rOffset = bin.u32(off)
                        symIdx = (bin.u32(off + 4) ushr 8).toInt()
                    }
                    if (symIdx == 0) continue
                    val sym = syms.getOrNull(symIdx) ?: continue
                    if (sym.shndx != 0 || sym.name.isEmpty()) continue
                    if (!imports.containsKey(rOffset)) imports[rOffset] = ImportEntry(stripVersion(sym.name), null, rOffset)
                }
            }
        }

        // ---- dynamic section: needed libraries, soname
        val libraries = ArrayList<String>()
        var soname: String? = null
        var runpath: String? = null
        val dynSec = sections.firstOrNull { it.name == ".dynamic" }
        val dynOff = dynSec?.offset ?: dynamicOff
        val dynSize = dynSec?.size ?: dynamicSize
        val dynstr = sections.firstOrNull { it.name == ".dynstr" }
        if (dynOff > 0 && dynstr != null) {
            val ent = if (is64) 16L else 8L
            var p = dynOff
            while (p + ent <= dynOff + dynSize && bin.has(p, ent)) {
                val tag = if (is64) bin.u64(p) else bin.u32(p)
                val v = if (is64) bin.u64(p + 8) else bin.u32(p + 4)
                when (tag) {
                    0L -> break
                    DT_NEEDED -> libraries += bin.cstring(dynstr.offset + v, 512)
                    DT_SONAME -> soname = bin.cstring(dynstr.offset + v, 512)
                    DT_RUNPATH -> runpath = bin.cstring(dynstr.offset + v, 1024)
                }
                p += ent
            }
        }

        // ---- init arrays (constructors), only when the slots hold link-time addresses
        val functionStarts = ArrayList<Long>()
        sections.filter { it.type == SHT_INIT_ARRAY }.forEach { s ->
            val ps = if (is64) 8L else 4L
            var p = 0L
            while (p + ps <= s.size && bin.has(s.offset + p, ps)) {
                var v = bin.ptr(s.offset + p, is64)
                if (isArm) v = v and 1L.inv()
                if (v != 0L && v != -1L && blocks.any { it.exec && v in it }) functionStarts += v
                p += ps
            }
        }

        val secList = sections.mapIndexed { i, s ->
            Section(
                name = s.name.ifEmpty { "[$i]" },
                address = sectionBase[i],
                size = s.size,
                fileOffset = s.offset,
                fileSize = if (s.type == SHT_NOBITS) 0 else s.size,
                alloc = s.flags and SHF_ALLOC != 0L,
                write = s.flags and SHF_WRITE != 0L,
                exec = s.flags and SHF_EXEC != 0L,
                kind = sectionTypeName(s.type),
            )
        }.filter { it.size > 0 || it.alloc }

        // ---- ARM / Thumb context
        val contexts = ArrayList<ContextRange>()
        val stubRanges = secList.filter { it.exec && (it.name.startsWith(".plt") || it.name == ".iplt") }
            .map { AddressRange(it.address, it.address + it.size) }
        var thumbDefault = false
        if (isArm) {
            val entryThumb = entry and 1L != 0L
            thumbDefault = when {
                thumbFuncs.size != armFuncs.size -> thumbFuncs.size > armFuncs.size
                // Nothing to go on: Android armeabi-v7a code is built as Thumb-2 by default.
                thumbFuncs.isEmpty() && armFuncs.isEmpty() -> true
                else -> entryThumb
            }
            blocks.filter { it.exec }.forEach { contexts += ContextRange("TMode", it.address, it.end, if (thumbDefault) 1 else 0) }
            armFuncs.forEach { (a, sz) -> contexts += ContextRange("TMode", a, a + maxOf(sz, 4), 0) }
            thumbFuncs.forEach { (a, sz) -> contexts += ContextRange("TMode", a, a + maxOf(sz, 2), 1) }
            val sortedMap = mapping.sortedWith { x, y -> Addr.cmp(x.first, y.first) }
            sortedMap.forEachIndexed { i, (a, c) ->
                if (c == 'd') return@forEachIndexed
                val end = sortedMap.getOrNull(i + 1)?.first ?: blocks.firstOrNull { a in it }?.end ?: (a + 4)
                contexts += ContextRange("TMode", a, end, if (c == 't') 1 else 0)
            }
            stubRanges.forEach { contexts += ContextRange("TMode", it.start, it.end, 0) }
        }

        val entryAddr = if (isArm) entry and 1L.inv() else entry
        val entries = if (entry != 0L && blocks.any { it.exec && entryAddr in it }) listOf(entryAddr) else emptyList()

        val arch = ElfMachines.describe(machine, is64, bigEndian, flags)
        val info = ArrayList<Pair<String, String>>()
        info += "Class" to if (is64) "ELF64" else "ELF32"
        info += "OS/ABI" to osAbi(bin.u8(7))
        info += "Flags" to "0x" + Addr.hex(flags)
        soname?.let { info += "SONAME" to it }
        interp?.let { info += "Interpreter" to it }
        runpath?.let { info += "RUNPATH" to it }
        buildId?.let { info += "Build ID" to it }
        if (isArm) info += "Default ISA mode" to if (thumbDefault) "Thumb" else "ARM"

        val notes = ArrayList<String>()
        if (symbols.none { it.type == SymbolType.FUNCTION }) notes += "stripped"

        return LoadedBinary(
            format = BinaryFormat.ELF,
            formatName = if (is64) "ELF64" else "ELF32",
            fileType = when (type) {
                1 -> "Relocatable object"
                2 -> "Executable"
                3 -> if (interp != null) "PIE executable" else "Shared object"
                4 -> "Core dump"
                else -> "Type $type"
            },
            machine = arch.name,
            bits = if (is64) 64 else 32,
            bigEndian = bigEndian,
            languageIds = arch.languages,
            compiler = arch.compiler,
            imageBase = blocks.minOf { it.address },
            entryPoints = entries,
            blocks = blocks,
            sections = secList,
            symbols = symbols,
            imports = imports.values.toList(),
            libraries = libraries,
            info = info,
            contexts = contexts,
            stubRanges = stubRanges,
            stubAlign = if (isArm) 4 else 16,
            functionStarts = functionStarts,
            notes = notes,
        )
    }

    private class RawSh(
        val nameOff: Long, val type: Long, val flags: Long, val addr: Long, val offset: Long,
        val size: Long, val link: Int, val info: Long, val entsize: Long,
    )

    private fun readSh(bin: Bin, is64: Boolean, off: Long): RawSh = if (is64) RawSh(
        bin.u32(off), bin.u32(off + 4), bin.u64(off + 8), bin.u64(off + 16), bin.u64(off + 24),
        bin.u64(off + 32), bin.u32(off + 40).toInt(), bin.u32(off + 44), bin.u64(off + 56),
    ) else RawSh(
        bin.u32(off), bin.u32(off + 4), bin.u32(off + 8), bin.u32(off + 12), bin.u32(off + 16),
        bin.u32(off + 20), bin.u32(off + 24).toInt(), bin.u32(off + 28), bin.u32(off + 36),
    )

    private fun readBuildId(bin: Bin, off: Long, size: Long): String? {
        var p = off
        while (p + 12 <= off + size && bin.has(p, 12)) {
            val namesz = bin.u32(p)
            val descsz = bin.u32(p + 4)
            val type = bin.u32(p + 8)
            val nameOff = p + 12
            val descOff = nameOff + ((namesz + 3) / 4) * 4
            if (type == 3L && namesz == 4L && bin.cstring(nameOff, 4) == "GNU" && descsz in 1..64 && bin.has(descOff, descsz)) {
                return bin.bytes(descOff, descsz.toInt()).joinToString("") { "%02x".format(it) }
            }
            p = descOff + ((descsz + 3) / 4) * 4
        }
        return null
    }

    private fun stripVersion(name: String): String {
        val at = name.indexOf('@')
        return if (at > 0) name.substring(0, at) else name
    }

    private fun sectionTypeName(type: Long): String = when (type) {
        0L -> "NULL"; 1L -> "PROGBITS"; 2L -> "SYMTAB"; 3L -> "STRTAB"; 4L -> "RELA"; 5L -> "HASH"
        6L -> "DYNAMIC"; 7L -> "NOTE"; 8L -> "NOBITS"; 9L -> "REL"; 11L -> "DYNSYM"; 14L -> "INIT_ARRAY"
        15L -> "FINI_ARRAY"; 16L -> "PREINIT_ARRAY"; 17L -> "GROUP"; 0x6ffffff6L -> "GNU_HASH"
        0x6fffffffL -> "VERSYM"; 0x6ffffffeL -> "VERNEED"; 0x6ffffffdL -> "VERDEF"; 0x70000001L -> "ARM_EXIDX"
        0x70000003L -> "ARM_ATTRIBUTES"; 0x60000001L -> "ANDROID_REL"; 0x60000002L -> "ANDROID_RELA"
        0x6fff4c04L -> "LLVM_ADDRSIG"; 0x6fffff00L -> "ANDROID_RELR"; 19L -> "RELR"
        else -> "0x" + Addr.hex(type)
    }

    private fun osAbi(v: Int): String = when (v) {
        0 -> "System V"; 3 -> "GNU/Linux"; 6 -> "Solaris"; 9 -> "FreeBSD"; 12 -> "OpenBSD"; 97 -> "ARM"; 255 -> "Standalone"
        else -> v.toString()
    }
}

/** ELF e_machine → processor and Ghidra language. */
object ElfMachines {
    data class Arch(val name: String, val languages: List<String>, val compiler: String)

    fun describe(machine: Int, is64: Boolean, bigEndian: Boolean, flags: Long): Arch {
        val e = if (bigEndian) "BE" else "LE"
        return when (machine) {
            3 -> Arch("x86 (i386)", listOf("x86:LE:32:default"), "gcc")
            62 -> Arch("x86-64", listOf("x86:LE:64:default"), "gcc")
            40 -> Arch("ARM", listOf("ARM:$e:32:v8", "ARM:$e:32:v7"), "default")
            183 -> if (is64) Arch("AArch64", listOf("AARCH64:$e:64:v8A"), "default")
            else Arch("AArch64 (ILP32)", listOf("AARCH64:$e:32:ilp32"), "default")
            8, 10 -> {
                val r6 = (flags ushr 28) and 0xF == 9L || (flags ushr 28) and 0xF == 10L
                if (is64) Arch("MIPS64", listOfNotNull(if (r6) "MIPS:$e:64:R6" else null, "MIPS:$e:64:default"), "default")
                else Arch("MIPS", listOfNotNull(if (r6) "MIPS:$e:32:R6" else null, "MIPS:$e:32:default"), "default")
            }
            20 -> Arch("PowerPC", listOf("PowerPC:$e:32:default"), "default")
            21 -> Arch("PowerPC64", listOf("PowerPC:$e:64:default"), "default")
            243 -> if (is64) Arch("RISC-V 64", listOf("RISCV:LE:64:default"), "gcc") else Arch("RISC-V 32", listOf("RISCV:LE:32:default"), "gcc")
            2, 18 -> Arch("SPARC", listOf("sparc:BE:32:default"), "default")
            43 -> Arch("SPARC V9", listOf("sparc:BE:64:default"), "default")
            42 -> Arch("SuperH", listOf("SuperH4:$e:32:default"), "default")
            258 -> if (is64) Arch("LoongArch64", listOf("Loongarch:LE:64:lp64d"), "default") else Arch("LoongArch32", listOf("Loongarch:LE:32:ilp32d"), "default")
            164 -> Arch("Hexagon", listOf("Hexagon:LE:32:default"), "default")
            94 -> Arch("Xtensa", listOf("Xtensa:$e:32:default"), "default")
            83 -> Arch("AVR", listOf("avr8:LE:16:extended", "avr8:LE:16:default"), "default")
            105 -> Arch("MSP430", listOf("TI_MSP430:LE:16:default"), "default")
            247 -> Arch("eBPF", listOf("eBPF:$e:64:default"), "default")
            4 -> Arch("Motorola 68k", listOf("68000:BE:32:default"), "default")
            15 -> Arch("PA-RISC", listOf("pa-risc:BE:32:default"), "default")
            87 -> Arch("V850", listOf("V850:LE:32:default"), "default")
            44 -> Arch("TriCore", listOf("tricore:LE:32:default"), "default")
            else -> Arch("e_machine $machine", emptyList(), "default")
        }
    }
}
