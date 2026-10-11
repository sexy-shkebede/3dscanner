package com.ghidroid.loader

import com.ghidroid.engine.Language

object Loaders {
    /** Parses [bin] with the matching format loader, falling back to a raw image. */
    fun load(bin: Bin, forceRaw: Boolean = false, rawBase: Long = 0): LoadedBinary {
        if (!forceRaw) {
            when {
                ElfLoader.matches(bin) -> return ElfLoader.load(bin)
                PeLoader.matches(bin) -> return PeLoader.load(bin)
                MachOLoader.matches(bin) -> return MachOLoader.load(bin)
            }
        }
        return raw(bin, rawBase)
    }

    fun raw(bin: Bin, base: Long): LoadedBinary = LoadedBinary(
        format = BinaryFormat.RAW,
        formatName = "Raw binary",
        fileType = "Raw image",
        machine = "—",
        bits = 0,
        bigEndian = false,
        languageIds = emptyList(),
        compiler = "default",
        imageBase = base,
        entryPoints = listOf(base),
        blocks = listOf(MemBlock("ram", base, 0, bin.size, maxOf(bin.size, 1), read = true, write = true, exec = true)),
        sections = listOf(Section("ram", base, bin.size, 0, bin.size, alloc = true, write = true, exec = true, kind = "RAW")),
        symbols = emptyList(),
        imports = emptyList(),
    )

    /**
     * Picks the installed Ghidra language for [binary]: exact ids first, then the closest match on
     * processor / endianness / size.
     */
    fun resolveLanguage(binary: LoadedBinary, languages: List<Language>): Language? {
        for (id in binary.languageIds) languages.firstOrNull { it.id == id && !it.deprecated }?.let { return it }
        for (id in binary.languageIds) {
            val parts = id.split(':')
            if (parts.size < 3) continue
            val candidates = languages.filter {
                it.processor.equals(parts[0], ignoreCase = true) && it.endian == parts[1] && it.size.toString() == parts[2] && !it.deprecated
            }
            candidates.firstOrNull { it.variant == "default" }?.let { return it }
            candidates.firstOrNull()?.let { return it }
        }
        return null
    }
}
