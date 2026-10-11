package com.ghidroid.analysis

import com.ghidroid.engine.Engine
import com.ghidroid.engine.NativeBridge
import com.ghidroid.loader.Addr
import com.ghidroid.loader.Bin
import com.ghidroid.loader.LoadedBinary
import com.ghidroid.loader.Loaders
import com.ghidroid.loader.SymbolType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

sealed interface OpenResult {
    data class Opened(val program: Program) : OpenResult
    data class NeedsLanguage(val binary: LoadedBinary) : OpenResult
}

data class OpenOptions(
    val languageId: String? = null,
    val compilerId: String? = null,
    val forceRaw: Boolean = false,
    val rawBase: Long = 0,
)

object ProgramLoader {
    suspend fun open(file: File, displayName: String, options: OpenOptions): OpenResult {
        val bin = withContext(Dispatchers.IO) { Bin.open(file) }
        try {
            val binary = withContext(Dispatchers.Default) { Loaders.load(bin, options.forceRaw, options.rawBase) }
            val language = options.languageId?.let { Engine.language(it) }
                ?: Loaders.resolveLanguage(binary, Engine.languages)
            if (language == null) {
                bin.close()
                return OpenResult.NeedsLanguage(binary)
            }
            bin.littleEndian = !language.bigEndian
            val compiler = language.compilerOrDefault(options.compilerId ?: binary.compiler)

            val segs = LongArray(binary.blocks.size * 5)
            binary.blocks.forEachIndexed { i, b ->
                segs[i * 5] = b.address
                segs[i * 5 + 1] = b.fileOffset
                segs[i * 5 + 2] = b.fileSize
                segs[i * 5 + 3] = b.memSize
                var flags = NativeBridge.SEG_READ
                if (b.exec) flags = flags or NativeBridge.SEG_EXEC
                if (b.write) flags = flags or NativeBridge.SEG_WRITE
                segs[i * 5 + 4] = flags.toLong()
            }
            val ctxNames = binary.contexts.map { it.variable }.toTypedArray()
            val ctxValues = LongArray(binary.contexts.size * 3)
            binary.contexts.forEachIndexed { i, c ->
                ctxValues[i * 3] = c.start
                ctxValues[i * 3 + 1] = c.end
                ctxValues[i * 3 + 2] = c.value.toLong()
            }
            val target = "${language.id}:${compiler.id}"
            val opened = Engine.call {
                val h = NativeBridge.open(file.absolutePath, target, segs, ctxNames, ctxValues)
                if (h == 0L) Triple(0L, NativeBridge.lastError(), IntArray(0))
                else Triple(h, NativeBridge.description(h), NativeBridge.info(h))
            }
            if (opened.first == 0L) throw IOException(opened.second.ifBlank { "Ghidra could not open $target" })
            val program = Program(
                file = file,
                displayName = displayName,
                bin = bin,
                binary = binary,
                language = language,
                compiler = compiler,
                handle = opened.first,
                alignment = opened.third.getOrElse(0) { 1 },
                pointerSize = opened.third.getOrElse(1) { binary.bits / 8 },
                languageDescription = opened.second,
            )
            try {
                SymbolSetup.apply(program)
            } catch (t: Throwable) {
                program.close()
                throw t
            }
            return OpenResult.Opened(program)
        } catch (t: Throwable) {
            runCatching { bin.close() }
            throw t
        }
    }
}

/** Turns the loader's symbols, imports and stubs into function / label tables and engine symbols. */
internal object SymbolSetup {
    suspend fun apply(program: Program) {
        val b = program.binary
        val demangled = Demangler.demangleAll(
            (b.symbols.map { it.name } + b.imports.map { it.name } + b.stubs.map { it.name }).filter { Demangler.isMangled(it) },
        )
        fun pretty(raw: String): Pair<String, String> {
            val d = demangled[raw]
            return if (d.isNullOrEmpty()) raw to raw else Demangler.shortName(d) to d
        }

        val natives = ArrayList<NativeSymbol>()
        val funcs = LinkedHashMap<Long, FunctionInfo>()
        val labels = HashMap<Long, String>()

        // Imports: each pointer slot becomes an external reference.
        val slots = HashMap<Long, String>()
        for (imp in b.imports) {
            val (short, _) = pretty(imp.name)
            if (slots.putIfAbsent(imp.slot, short) == null) natives += NativeSymbol(imp.slot, 0, NativeBridge.SYM_IMPORT, short)
        }
        program.importSlots = slots

        fun addStub(addr: Long, raw: String) {
            val (short, display) = pretty(raw)
            funcs[addr] = FunctionInfo(addr, short, display, 0, FunctionKind.IMPORT, raw.takeIf { it != display })
        }
        b.stubs.forEach { addStub(it.address, it.name) }
        val slotNames = b.imports.associate { it.slot to it.name }
        for (r in b.stubRanges) {
            val pairs = program.resolveStubs(r.start, r.end, b.stubAlign)
            var i = 0
            while (i + 1 < pairs.size) {
                slotNames[pairs[i + 1]]?.let { addStub(pairs[i], it) }
                i += 2
            }
        }

        for (s in b.symbols) {
            val (short, display) = pretty(s.name)
            when (s.type) {
                SymbolType.FUNCTION -> {
                    val existing = funcs[s.address]
                    val replace = existing == null ||
                        (existing.kind == FunctionKind.LOCAL && s.exported) ||
                        (existing.kind == s.kind() && existing.name.startsWith("_") && !s.name.startsWith("_"))
                    if (replace && existing?.kind != FunctionKind.IMPORT) {
                        funcs[s.address] = FunctionInfo(
                            s.address, short, display, maxOf(s.size, existing?.size ?: 0), s.kind(), s.name.takeIf { it != display },
                        )
                    }
                }
                SymbolType.DATA -> {
                    labels.putIfAbsent(s.address, display)
                    natives += NativeSymbol(s.address, s.size, NativeBridge.SYM_DATA, short)
                }
                SymbolType.LABEL -> {
                    labels.putIfAbsent(s.address, display)
                    natives += NativeSymbol(s.address, 0, NativeBridge.SYM_LABEL, short)
                }
            }
        }
        b.entryPoints.forEachIndexed { i, e ->
            if (!funcs.containsKey(e)) {
                val n = if (i == 0) "entry" else "entry_$i"
                funcs[e] = FunctionInfo(e, n, n, 0, FunctionKind.ENTRY)
            }
        }
        for (start in b.functionStarts) {
            if (!funcs.containsKey(start) && b.isExecutable(start)) {
                val n = defaultName(start, program.pointerSize)
                funcs[start] = FunctionInfo(start, n, n, 0, FunctionKind.DISCOVERED)
            }
        }
        for (f in funcs.values) natives += NativeSymbol(f.address, f.size, NativeBridge.SYM_FUNCTION, f.name)
        program.addNativeSymbols(natives)
        program.setLabels(labels)
        program.setFunctions(funcs.values.toList())
    }

    private fun com.ghidroid.loader.BinSymbol.kind(): FunctionKind = if (exported) FunctionKind.EXPORTED else FunctionKind.LOCAL

    fun defaultName(addr: Long, pointerSize: Int): String = "FUN_" + Addr.hex(addr, pointerSize.coerceIn(2, 8) * 2)
}

object Demangler {
    fun isMangled(name: String): Boolean = name.startsWith("_Z") || name.startsWith("__Z") || name.startsWith("___Z")

    fun demangleAll(names: List<String>): Map<String, String> {
        if (names.isEmpty()) return emptyMap()
        val distinct = names.distinct()
        val input = distinct.map { it.trimStart('_').let { n -> "_$n" } }.toTypedArray()
        val out = runCatching { NativeBridge.demangle(input) }.getOrNull() ?: return emptyMap()
        val map = HashMap<String, String>(distinct.size)
        distinct.forEachIndexed { i, n -> if (out[i].isNotEmpty()) map[n] = out[i] }
        return map
    }

    /** "ns::Cls::method(int) const" → "ns::Cls::method" (names handed to the decompiler). */
    fun shortName(demangled: String): String {
        var s = demangled.trim()
        for (q in listOf(" const", " volatile", " &&", " &", " noexcept")) {
            while (s.endsWith(q)) s = s.removeSuffix(q).trimEnd()
        }
        if (s.endsWith(")")) {
            var depth = 0
            var i = s.length - 1
            while (i >= 0) {
                val c = s[i]
                if (c == ')') depth++
                else if (c == '(') {
                    depth--
                    if (depth == 0) break
                }
                i--
            }
            if (i > 0) s = s.substring(0, i)
        }
        if (s.length > 160) s = s.take(160)
        return s.ifBlank { demangled }
    }
}
