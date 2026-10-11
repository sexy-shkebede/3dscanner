package com.ghidroid.loader

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Windows PE/COFF images: .exe, .dll, .sys, .ocx, .efi ... */
object PeLoader {
    private class Sec(val name: String, val vsize: Long, val va: Long, val rawSize: Long, val rawPtr: Long, val chars: Long)

    fun matches(bin: Bin): Boolean {
        if (bin.size < 0x40 || bin.u8(0) != 'M'.code || bin.u8(1) != 'Z'.code) return false
        bin.littleEndian = true
        val lfanew = bin.u32(0x3C)
        return bin.has(lfanew, 24) && bin.u32(lfanew) == 0x00004550L
    }

    fun load(bin: Bin): LoadedBinary {
        bin.littleEndian = true
        val pe = bin.u32(0x3C)
        val coff = pe + 4
        val machine = bin.u16(coff)
        val nsec = bin.u16(coff + 2)
        val timestamp = bin.u32(coff + 4)
        val symPtr = bin.u32(coff + 8)
        val symCount = bin.u32(coff + 12)
        val optSize = bin.u16(coff + 16)
        val characteristics = bin.u16(coff + 18)
        val opt = coff + 20
        val magic = if (optSize >= 2) bin.u16(opt) else 0
        val is64 = magic == 0x20B
        if (magic != 0x10B && magic != 0x20B) throw FormatException("Unsupported optional header 0x${Integer.toHexString(magic)}")
        val entryRva = bin.u32(opt + 16)
        val imageBase = if (is64) bin.u64(opt + 24) else bin.u32(opt + 28)
        val sizeOfImage = bin.u32(opt + 56)
        val sizeOfHeaders = bin.u32(opt + 60)
        val subsystem = bin.u16(opt + 68)
        val dllChars = bin.u16(opt + 70)
        val ddBase = opt + if (is64) 112 else 96
        val ddCount = bin.u32(ddBase - 4).coerceAtMost(16).toInt()
        fun dir(i: Int): Pair<Long, Long> =
            if (i < ddCount && bin.has(ddBase + i * 8L, 8)) bin.u32(ddBase + i * 8L) to bin.u32(ddBase + i * 8L + 4) else 0L to 0L

        // ---- sections
        val secTable = opt + optSize
        val strTableOff = if (symPtr != 0L) symPtr + symCount * 18 else 0L
        val secs = (0 until nsec).mapNotNull { i ->
            val off = secTable + i * 40L
            if (!bin.has(off, 40)) return@mapNotNull null
            var name = bin.fixedString(off, 8)
            if (name.startsWith("/") && strTableOff != 0L) {
                name.substring(1).toLongOrNull()?.let { name = bin.cstring(strTableOff + it, 256) }
            }
            Sec(name, bin.u32(off + 8), bin.u32(off + 12), bin.u32(off + 16), bin.u32(off + 20), bin.u32(off + 36))
        }

        fun rvaToOff(rva: Long): Long? {
            if (rva < sizeOfHeaders && rva < bin.size) return rva
            for (s in secs) {
                val span = maxOf(s.vsize, s.rawSize)
                if (rva >= s.va && rva < s.va + span) {
                    val d = rva - s.va
                    return if (d < s.rawSize) s.rawPtr + d else null
                }
            }
            return null
        }

        fun str(rva: Long, max: Int = 512): String = rvaToOff(rva)?.let { bin.cstring(it, max) } ?: ""

        val blocks = ArrayList<MemBlock>()
        blocks += MemBlock("HEADER", imageBase, 0, minOf(sizeOfHeaders, bin.size), maxOf(sizeOfHeaders, 1), read = true, write = false, exec = false)
        val sections = ArrayList<Section>()
        sections += Section("HEADER", imageBase, sizeOfHeaders, 0, minOf(sizeOfHeaders, bin.size), alloc = true, write = false, exec = false)
        for (s in secs) {
            val memSize = if (s.vsize != 0L) s.vsize else s.rawSize
            if (memSize == 0L) continue
            val exec = s.chars and 0x20000000L != 0L || s.chars and 0x20L != 0L
            val write = s.chars and 0x80000000L != 0L
            val fileSize = minOf(s.rawSize, memSize, maxOf(0, bin.size - s.rawPtr))
            blocks += MemBlock(s.name, imageBase + s.va, s.rawPtr, if (s.rawPtr == 0L) 0 else fileSize, memSize, read = true, write = write, exec = exec)
            sections += Section(s.name, imageBase + s.va, memSize, s.rawPtr, fileSize, alloc = true, write = write, exec = exec,
                kind = when { exec -> "CODE"; s.chars and 0x80L != 0L -> "BSS"; else -> "DATA" })
        }

        val symbols = ArrayList<BinSymbol>()
        fun isCode(addr: Long) = blocks.any { it.exec && addr in it }

        // ---- exports
        val (expRva, expSize) = dir(0)
        var dllName: String? = null
        if (expRva != 0L) rvaToOff(expRva)?.let { e ->
            if (!bin.has(e, 40)) return@let
            dllName = str(bin.u32(e + 12))
            val ordBase = bin.u32(e + 16)
            val nFuncs = bin.u32(e + 20).coerceAtMost(200_000).toInt()
            val nNames = bin.u32(e + 24).coerceAtMost(200_000).toInt()
            val funcsOff = rvaToOff(bin.u32(e + 28))
            val namesOff = rvaToOff(bin.u32(e + 32))
            val ordsOff = rvaToOff(bin.u32(e + 36))
            val names = HashMap<Int, String>()
            if (namesOff != null && ordsOff != null) for (i in 0 until nNames) {
                if (!bin.has(namesOff + i * 4L, 4) || !bin.has(ordsOff + i * 2L, 2)) break
                names[bin.u16(ordsOff + i * 2L)] = str(bin.u32(namesOff + i * 4L))
            }
            if (funcsOff != null) for (i in 0 until nFuncs) {
                if (!bin.has(funcsOff + i * 4L, 4)) break
                val rva = bin.u32(funcsOff + i * 4L)
                if (rva == 0L || (rva >= expRva && rva < expRva + expSize)) continue  // empty or forwarder
                val addr = imageBase + rva
                val name = names[i] ?: "Ordinal_${ordBase + i}"
                symbols += BinSymbol(name, addr, 0, if (isCode(addr)) SymbolType.FUNCTION else SymbolType.DATA, exported = true)
            }
        }

        // ---- imports (normal + delay-load)
        val imports = ArrayList<ImportEntry>()
        val libraries = LinkedHashSet<String>()
        val ps = if (is64) 8L else 4L
        val ordFlag = if (is64) Long.MIN_VALUE else 0x80000000L

        fun readThunks(dll: String, intRva: Long, iatRva: Long) {
            val lookup = rvaToOff(if (intRva != 0L) intRva else iatRva) ?: return
            var i = 0L
            while (i < 65536) {
                val off = lookup + i * ps
                if (!bin.has(off, ps)) break
                val v = bin.ptr(off, is64)
                if (v == 0L) break
                val name = if (v and ordFlag != 0L) "${dll.substringBeforeLast('.')}_Ordinal_${v and 0xFFFF}"
                else str((v and 0x7FFFFFFFL) + 2, 512).ifEmpty { "${dll}_${i}" }
                imports += ImportEntry(name, dll, imageBase + iatRva + i * ps)
                i++
            }
        }

        val (impRva, _) = dir(1)
        if (impRva != 0L) rvaToOff(impRva)?.let { d ->
            var p = d
            for (n in 0 until 4096) {
                if (!bin.has(p, 20)) break
                val oft = bin.u32(p)
                val nameRva = bin.u32(p + 12)
                val ft = bin.u32(p + 16)
                if (nameRva == 0L && ft == 0L) break
                val dll = str(nameRva, 256)
                libraries += dll
                readThunks(dll, oft, ft)
                p += 20
            }
        }
        val (delayRva, _) = dir(13)
        if (delayRva != 0L) rvaToOff(delayRva)?.let { d ->
            var p = d
            for (n in 0 until 4096) {
                if (!bin.has(p, 32)) break
                val attrs = bin.u32(p)
                val nameRva = bin.u32(p + 4)
                val iat = bin.u32(p + 12)
                val int = bin.u32(p + 16)
                if (nameRva == 0L) break
                val rvaBased = attrs and 1L != 0L
                fun fix(v: Long) = if (rvaBased || v == 0L) v else v - imageBase
                val dll = str(fix(nameRva), 256)
                libraries += "$dll (delay)"
                readThunks(dll, fix(int), fix(iat))
                p += 32
            }
        }

        // ---- function starts from the exception directory (.pdata)
        val functionStarts = ArrayList<Long>()
        val (excRva, excSize) = dir(3)
        if (excRva != 0L && (machine == 0x8664 || machine == 0xAA64 || machine == 0x1C4)) rvaToOff(excRva)?.let { e ->
            val ent = if (machine == 0x8664) 12L else 8L
            val count = (excSize / ent).coerceAtMost(1_000_000)
            for (i in 0 until count) {
                val off = e + i * ent
                if (!bin.has(off, ent)) break
                var begin = bin.u32(off)
                if (machine == 0x1C4) begin = begin and 1L.inv()
                if (begin != 0L) functionStarts += imageBase + begin
            }
        }

        // ---- COFF symbol table (MinGW / GCC builds keep one)
        if (symPtr != 0L && symCount in 1..2_000_000) {
            var i = 0L
            while (i < symCount) {
                val off = symPtr + i * 18
                if (!bin.has(off, 18)) break
                val name = if (bin.u32(off) == 0L) bin.cstring(strTableOff + bin.u32(off + 4), 512) else bin.fixedString(off, 8)
                val value = bin.u32(off + 8)
                val secNum = bin.u16(off + 12).toShort().toInt()
                val type = bin.u16(off + 14)
                val storage = bin.u8(off + 16)
                val aux = bin.u8(off + 17)
                if (secNum in 1..secs.size && (storage == 2 || storage == 3) && name.isNotEmpty() && !name.startsWith(".")) {
                    val addr = imageBase + secs[secNum - 1].va + value
                    val isFunc = (type ushr 4) and 3 == 2 || (storage == 2 && isCode(addr))
                    if (isFunc && isCode(addr)) symbols += BinSymbol(name, addr, 0, SymbolType.FUNCTION, exported = false)
                    else if (!isCode(addr) && storage == 2) symbols += BinSymbol(name, addr, 0, SymbolType.DATA, exported = false)
                }
                i += 1 + aux
            }
        }

        // ---- x86 / x64 jump thunks: FF 25 <disp32> through an import slot
        val stubs = ArrayList<StubEntry>()
        if (machine == 0x14C || machine == 0x8664) {
            val slotNames = imports.associate { it.slot to it.name }
            for (b in blocks) {
                if (!b.exec || b.fileSize < 6) continue
                val data = bin.bytesClamped(b.fileOffset, b.fileSize.toInt())
                var k = 0
                while (k + 6 <= data.size) {
                    if (data[k] == 0xFF.toByte() && data[k + 1] == 0x25.toByte()) {
                        val disp = (data[k + 2].toLong() and 0xFF) or ((data[k + 3].toLong() and 0xFF) shl 8) or
                            ((data[k + 4].toLong() and 0xFF) shl 16) or ((data[k + 5].toLong() and 0xFF) shl 24)
                        val at = b.address + k
                        val slot = if (machine == 0x8664) at + 6 + disp.toInt().toLong() else disp
                        slotNames[slot]?.let { stubs += StubEntry(at, it) }
                    }
                    k++
                }
            }
        }

        val entry = if (entryRva != 0L) imageBase + entryRva else null
        val arch = machineArch(machine)
        val info = ArrayList<Pair<String, String>>()
        info += "Image base" to "0x" + Addr.hex(imageBase)
        info += "Size of image" to "0x" + Addr.hex(sizeOfImage)
        info += "Subsystem" to subsystemName(subsystem)
        info += "Timestamp" to formatTime(timestamp)
        info += "Characteristics" to "0x" + Addr.hex(characteristics.toLong())
        info += "DLL characteristics" to dllCharacteristics(dllChars)
        dllName?.takeIf { it.isNotEmpty() }?.let { info += "Export name" to it }
        readPdb(bin, dir(6), ::rvaToOff)?.let { info += "PDB" to it }
        val notes = ArrayList<String>()
        if (dir(14).first != 0L) notes += "dotnet"

        val isDll = characteristics and 0x2000 != 0
        return LoadedBinary(
            format = BinaryFormat.PE,
            formatName = if (is64) "PE32+" else "PE32",
            fileType = when {
                isDll -> "DLL"
                subsystem == 1 -> "Driver"
                subsystem in 10..13 -> "EFI image"
                else -> "Executable"
            },
            machine = arch.first,
            bits = if (is64) 64 else 32,
            bigEndian = false,
            languageIds = arch.second,
            compiler = "windows",
            imageBase = imageBase,
            entryPoints = listOfNotNull(entry?.takeIf { isCode(it) }),
            blocks = blocks,
            sections = sections,
            symbols = symbols,
            imports = imports,
            stubs = stubs,
            libraries = libraries.toList(),
            info = info,
            functionStarts = functionStarts,
            notes = notes,
        )
    }

    private fun machineArch(m: Int): Pair<String, List<String>> = when (m) {
        0x14C -> "x86 (i386)" to listOf("x86:LE:32:default")
        0x8664 -> "x86-64" to listOf("x86:LE:64:default")
        0x1C0, 0x1C2, 0x1C4 -> "ARM / Thumb-2" to listOf("ARM:LE:32:v8", "ARM:LE:32:v7")
        0xAA64 -> "ARM64" to listOf("AARCH64:LE:64:v8A")
        0x166 -> "MIPS R4000" to listOf("MIPS:LE:32:default")
        0x169 -> "MIPS WCE v2" to listOf("MIPS:LE:32:default")
        0x1F0, 0x1F1 -> "PowerPC" to listOf("PowerPC:LE:32:default")
        0x1A2, 0x1A3, 0x1A6 -> "SuperH" to listOf("SuperH4:LE:32:default")
        0x5064 -> "RISC-V 64" to listOf("RISCV:LE:64:default")
        0x5032 -> "RISC-V 32" to listOf("RISCV:LE:32:default")
        else -> "Machine 0x${Integer.toHexString(m)}" to emptyList()
    }

    private fun subsystemName(s: Int): String = when (s) {
        1 -> "Native"; 2 -> "Windows GUI"; 3 -> "Windows console"; 5 -> "OS/2 console"; 7 -> "POSIX console"
        9 -> "Windows CE"; 10 -> "EFI application"; 11 -> "EFI boot driver"; 12 -> "EFI runtime driver"
        13 -> "EFI ROM"; 14 -> "Xbox"; 16 -> "Boot application"
        else -> s.toString()
    }

    private fun dllCharacteristics(c: Int): String {
        val parts = ArrayList<String>()
        if (c and 0x0020 != 0) parts += "HIGH_ENTROPY_VA"
        if (c and 0x0040 != 0) parts += "DYNAMIC_BASE"
        if (c and 0x0100 != 0) parts += "NX_COMPAT"
        if (c and 0x0400 != 0) parts += "NO_SEH"
        if (c and 0x4000 != 0) parts += "GUARD_CF"
        return if (parts.isEmpty()) "0x" + Integer.toHexString(c) else parts.joinToString(" · ")
    }

    private fun formatTime(t: Long): String {
        if (t == 0L || t == 0xFFFFFFFFL) return "—"
        val f = SimpleDateFormat("yyyy-MM-dd HH:mm:ss 'UTC'", Locale.US)
        f.timeZone = TimeZone.getTimeZone("UTC")
        return f.format(Date(t * 1000))
    }

    private fun readPdb(bin: Bin, debug: Pair<Long, Long>, rvaToOff: (Long) -> Long?): String? {
        val (rva, size) = debug
        if (rva == 0L) return null
        val base = rvaToOff(rva) ?: return null
        var i = 0L
        while (i + 28 <= size && i < 28 * 32) {
            val off = base + i
            if (!bin.has(off, 28)) return null
            if (bin.u32(off + 12) == 2L) {  // IMAGE_DEBUG_TYPE_CODEVIEW
                val ptr = bin.u32(off + 24)
                if (bin.has(ptr, 24) && bin.u32(ptr) == 0x53445352L) return bin.cstring(ptr + 24, 512)  // "RSDS"
            }
            i += 28
        }
        return null
    }
}
