package com.ghidroid.ui.workspace

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import com.ghidroid.analysis.CodeLine
import com.ghidroid.analysis.Hl
import com.ghidroid.analysis.Instruction
import com.ghidroid.analysis.Program
import com.ghidroid.loader.Addr
import com.ghidroid.ui.theme.CodeColors

private val TOKEN = Regex("""0x[0-9a-fA-F]+|[A-Za-z_.$][A-Za-z0-9_.$]*|\s+|.""")
private val SIZE_WORDS = setOf("byte", "word", "dword", "qword", "tword", "xmmword", "ymmword", "zmmword", "ptr", "short", "far", "near")

/** Formats addresses with the width the program needs (at least 4 hex digits). */
class AddressFormat(program: Program) {
    val digits: Int = run {
        val max = program.binary.blocks.maxOfOrNull { it.end - 1 } ?: 0L
        maxOf(4, java.lang.Long.toHexString(max).length)
    }

    fun format(a: Long): String = Addr.hex(a, digits)
}

fun escapeString(s: String, max: Int = 80): String {
    val sb = StringBuilder()
    for (c in s) {
        if (sb.length >= max) {
            sb.append('…')
            break
        }
        when (c) {
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\t' -> sb.append("\\t")
            '"' -> sb.append("\\\"")
            else -> sb.append(c)
        }
    }
    return sb.toString()
}

/** Short annotation for an address: symbol name or string preview. */
fun describeAddress(program: Program, a: Long): Pair<String, Boolean>? {
    program.nameAt(a)?.let { return it to false }
    program.stringAt(a)?.let { return "\"" + escapeString(it.text, 48) + "\"" to true }
    return null
}

fun buildInstruction(
    insn: Instruction,
    program: Program,
    colors: CodeColors,
    fmt: AddressFormat,
    showBytes: Boolean,
    lowercase: Boolean,
    onNavigate: (Long) -> Unit,
): AnnotatedString = buildAnnotatedString {
    withStyle(SpanStyle(color = colors.address)) { append(fmt.format(insn.address)) }
    append("  ")
    if (showBytes) {
        val maxBytes = if (program.alignment >= 4) 4 else 8
        val fo = program.binary.fileOffsetOf(insn.address)
        val bytes = if (fo != null) program.bin.bytesClamped(fo, minOf(insn.length, maxBytes)) else ByteArray(0)
        val text = bytes.joinToString(" ") { "%02x".format(it) } + if (insn.length > maxBytes) "+" else ""
        withStyle(SpanStyle(color = colors.bytes)) { append(text.padEnd(maxBytes * 3)) }
        append(" ")
    }
    val mnColor = when (insn.flow) {
        Instruction.FLOW_CALL, Instruction.FLOW_ICALL -> colors.call
        Instruction.FLOW_JUMP, Instruction.FLOW_CJUMP, Instruction.FLOW_IJUMP -> colors.jump
        Instruction.FLOW_RETURN -> colors.ret
        Instruction.FLOW_INVALID -> colors.error
        else -> colors.mnemonic
    }
    val mn = if (lowercase) insn.mnemonic.lowercase() else insn.mnemonic
    withStyle(SpanStyle(color = mnColor, fontWeight = FontWeight.Medium)) { append(mn) }
    append(" ".repeat(maxOf(1, 8 - mn.length)))
    var mentioned: Long? = null
    var prev = ""
    for (m in TOKEN.findAll(insn.operands)) {
        val t = m.value
        val immediate = prev == "#" || prev == "-"
        prev = t
        when {
            t.startsWith("0x") && t.length > 2 -> {
                val v = Addr.parse(t)
                // Small immediates (#0x10) look like addresses in images based at 0: only link
                // values the instruction really refers to or that carry a name.
                val navigable = v != null && program.isMapped(v) && v != 0L &&
                    (v == insn.target || program.nameAt(v) != null || program.stringAt(v) != null ||
                        (!immediate && (v == insn.ref || (v >= 0x1000 && program.isCode(v)))))
                if (navigable && v != null) {
                    if (mentioned == null) mentioned = v
                    withLink(link(colors.function) { onNavigate(v) }) { append(t) }
                } else withStyle(SpanStyle(color = colors.constant)) { append(t) }
            }
            t.first().isLetter() || t.first() == '_' -> {
                val lower = t.lowercase()
                if (lower in SIZE_WORDS) withStyle(SpanStyle(color = colors.keyword)) { append(if (lowercase) lower else t) }
                else withStyle(SpanStyle(color = colors.register)) { append(t) }
            }
            t.first() == '#' -> withStyle(SpanStyle(color = colors.constant)) { append(t) }
            else -> append(t)
        }
    }
    // Comment: what the branch target / data reference / mentioned address is.
    val note = when {
        insn.target != -1L -> describeAddress(program, insn.target)
        insn.ref != -1L -> describeAddress(program, insn.ref) ?: refValueNote(program, insn.ref)
        mentioned != null -> describeAddress(program, mentioned)
        else -> null
    }
    if (note != null && note.first.isNotEmpty()) {
        append("  ")
        withStyle(SpanStyle(color = if (note.second) colors.string else colors.comment, fontStyle = if (note.second) FontStyle.Normal else FontStyle.Italic)) {
            append("; ")
            append(note.first)
        }
    }
}

/** For loads from read-only pointers (GOT, literal pools) name what the pointer points at. */
private fun refValueNote(program: Program, ref: Long): Pair<String, Boolean>? {
    val fo = program.binary.fileOffsetOf(ref) ?: return null
    val ps = program.pointerSize
    if (!program.bin.has(fo, ps.toLong())) return null
    val v = if (ps == 8) program.bin.u64(fo) else program.bin.u32(fo)
    if (v == 0L || !program.isMapped(v)) return null
    val d = describeAddress(program, v) ?: describeAddress(program, v and 1L.inv()) ?: return null
    return "→ " + d.first to d.second
}

fun link(color: Color, onClick: () -> Unit): LinkAnnotation.Clickable =
    LinkAnnotation.Clickable(
        tag = "nav",
        styles = TextLinkStyles(SpanStyle(color = color, textDecoration = TextDecoration.Underline)),
        linkInteractionListener = { onClick() },
    )

fun codeColor(kind: Int, c: CodeColors): Color = when (kind) {
    Hl.KEYWORD -> c.keyword
    Hl.COMMENT -> c.comment
    Hl.TYPE -> c.type
    Hl.FUNCTION -> c.function
    Hl.VARIABLE -> c.variable
    Hl.CONSTANT -> c.constant
    Hl.PARAMETER -> c.parameter
    Hl.GLOBAL -> c.global
    Hl.ERROR -> c.error
    Hl.SPECIAL -> c.special
    else -> c.variable
}

fun buildCodeLine(
    line: CodeLine,
    colors: CodeColors,
    highlight: String?,
    onNavigate: (Long, Int) -> Unit,
    onToken: (String) -> Unit,
): AnnotatedString = buildAnnotatedString {
    var pos = 0
    val text = line.text
    for (sp in line.spans) {
        if (sp.start > pos) append(text.substring(pos, sp.start))
        val tokenText = text.substring(sp.start, minOf(sp.end, text.length))
        val base = SpanStyle(
            color = codeColor(sp.kind, colors),
            fontWeight = if (sp.kind == Hl.KEYWORD || sp.kind == Hl.FUNCTION) FontWeight.SemiBold else null,
            fontStyle = if (sp.kind == Hl.COMMENT) FontStyle.Italic else null,
            background = if (highlight != null && tokenText == highlight) colors.highlight else Color.Unspecified,
        )
        val clickable = sp.address != -1L && (sp.kind == Hl.FUNCTION || sp.kind == Hl.GLOBAL || sp.kind == Hl.PLAIN || sp.kind == Hl.KEYWORD)
        if (clickable) {
            withLink(
                LinkAnnotation.Clickable(
                    tag = "nav",
                    styles = TextLinkStyles(base.copy(textDecoration = TextDecoration.Underline)),
                    linkInteractionListener = { onNavigate(sp.address, sp.kind) },
                ),
            ) { append(tokenText) }
        } else if (sp.kind == Hl.VARIABLE || sp.kind == Hl.PARAMETER || sp.kind == Hl.GLOBAL || sp.kind == Hl.FUNCTION || sp.kind == Hl.TYPE) {
            withLink(
                LinkAnnotation.Clickable(tag = "tok", styles = TextLinkStyles(base), linkInteractionListener = { onToken(tokenText) }),
            ) { append(tokenText) }
        } else {
            withStyle(base) { append(tokenText) }
        }
        pos = maxOf(pos, minOf(sp.end, text.length))
    }
    if (pos < text.length) append(text.substring(pos))
}
