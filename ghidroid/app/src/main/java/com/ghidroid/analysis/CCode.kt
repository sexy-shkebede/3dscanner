package com.ghidroid.analysis

/** Token classes emitted by the decompiler (Ghidra's ClangToken syntax highlight ids). */
object Hl {
    const val KEYWORD = 0
    const val COMMENT = 1
    const val TYPE = 2
    const val FUNCTION = 3
    const val VARIABLE = 4
    const val CONSTANT = 5
    const val PARAMETER = 6
    const val GLOBAL = 7
    const val PLAIN = 8
    const val ERROR = 9
    const val SPECIAL = 10
}

data class CodeSpan(val start: Int, val end: Int, val kind: Int, val address: Long)

data class CodeLine(val text: String, val spans: List<CodeSpan>, val address: Long)

data class DecompiledFunction(val address: Long, val lines: List<CodeLine>) {
    val plainText: String by lazy { lines.joinToString("\n") { it.text } }
    val maxLineLength: Int by lazy { lines.maxOfOrNull { it.text.length } ?: 0 }
}

/** Parses the markup written by the native `EmitColor` emitter (see engine.cc). */
object CCodeParser {
    fun parse(address: Long, raw: String): DecompiledFunction {
        val lines = ArrayList<CodeLine>()
        val text = StringBuilder()
        var spans = ArrayList<CodeSpan>()
        var lineAddr = -1L
        var i = 0
        val n = raw.length

        fun endLine() {
            lines += CodeLine(text.toString().trimEnd(), spans, lineAddr)
            text.setLength(0)
            spans = ArrayList()
            lineAddr = -1L
        }

        fun readHex(stop: Char): Long {
            var v = 0L
            while (i < n && raw[i] != stop) {
                v = (v shl 4) or Character.digit(raw[i], 16).toLong().coerceAtLeast(0)
                i++
            }
            i++  // skip terminator
            return v
        }

        while (i < n) {
            when (val c = raw[i]) {
                '\n' -> {
                    endLine()
                    i++
                }
                '\u0001', '\u0005' -> {
                    val kind = if (i + 1 < n) raw[i + 1] - 'A' else Hl.PLAIN
                    i += 2
                    val addr = if (c == '\u0005') readHex('\u0006') else -1L
                    val start = text.length
                    while (i < n && raw[i] != '\u0002') {
                        if (raw[i] != '\n') text.append(raw[i])
                        i++
                    }
                    i++
                    if (text.length > start) spans += CodeSpan(start, text.length, kind, addr)
                }
                '\u0003' -> {
                    i++
                    val a = readHex('\u0003')
                    if (lineAddr == -1L) lineAddr = a
                }
                else -> {
                    text.append(c)
                    i++
                }
            }
        }
        if (text.isNotEmpty() || spans.isNotEmpty()) endLine()
        while (lines.isNotEmpty() && lines.first().text.isBlank()) lines.removeAt(0)
        while (lines.isNotEmpty() && lines.last().text.isBlank()) lines.removeAt(lines.size - 1)
        return DecompiledFunction(address, lines)
    }
}
