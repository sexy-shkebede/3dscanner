package com.ghidroid.ui.workspace

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ghidroid.data.AppSettings
import com.ghidroid.loader.Addr
import com.ghidroid.ui.theme.CodeFont
import com.ghidroid.ui.theme.CodeTheme

@Composable
fun HexPane(ws: Workspace, settings: AppSettings) {
    val program = ws.program
    val size = program.bin.size
    val colors = CodeTheme.colors
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val fontSize = (settings.codeFontSize - 1f).coerceAtLeast(9f)
    val style = TextStyle(fontFamily = CodeFont, fontSize = fontSize.sp, lineHeight = (fontSize * 1.5f).sp)
    val charW = remember(fontSize) { measurer.measure("0123456789", style).size.width / 10f }
    val offDigits = maxOf(6, java.lang.Long.toHexString(maxOf(size - 1, 0)).length)
    var highlight by remember { mutableStateOf<LongRange?>(null) }
    val state = rememberLazyListState()

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val chars = with(density) { (maxWidth - 24.dp).toPx() } / charW
        // offset + 2 spaces + 3 per byte + 1 + 1 per byte
        val perRow = if (chars >= offDigits + 2 + 16 * 4 + 1) 16 else 8
        val rowCount = ((size + perRow - 1) / perRow).toInt()

        LaunchedEffect(ws.hexTarget, perRow) {
            val t = ws.hexTarget ?: return@LaunchedEffect
            highlight = t.first until t.first + t.second
            state.scrollToItem(maxOf(0, (t.first / perRow).toInt() - 4))
        }

        Column(Modifier.fillMaxSize()) {
            Surface(color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
                Text(
                    buildAnnotatedString {
                        withStyle(SpanStyle(color = colors.address)) { append("offset".padEnd(offDigits) + "  ") }
                        withStyle(SpanStyle(color = colors.keyword)) {
                            append((0 until perRow).joinToString(" ") { "%02x".format(it) })
                        }
                    },
                    style = style,
                    maxLines = 1,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
            LazyColumn(state = state, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp), modifier = Modifier.fillMaxSize()) {
                items(rowCount) { row ->
                    val off = row.toLong() * perRow
                    val bytes = program.bin.bytesClamped(off, perRow)
                    val hl = highlight
                    val text = buildAnnotatedString {
                        withStyle(SpanStyle(color = colors.address)) { append(Addr.hex(off, offDigits)) }
                        append("  ")
                        for (i in 0 until perRow) {
                            if (i > 0) append(' ')
                            if (i >= bytes.size) {
                                append("  ")
                                continue
                            }
                            val b = bytes[i].toInt() and 0xFF
                            val inHl = hl != null && (off + i) in hl
                            withStyle(
                                SpanStyle(
                                    color = if (b == 0) colors.bytes else colors.variable,
                                    background = if (inHl) colors.highlight else Color.Unspecified,
                                ),
                            ) { append("%02x".format(b)) }
                        }
                        append("  ")
                        for (i in bytes.indices) {
                            val b = bytes[i].toInt() and 0xFF
                            val inHl = hl != null && (off + i) in hl
                            withStyle(SpanStyle(color = if (b in 0x20..0x7E) colors.string else colors.bytes, background = if (inHl) colors.highlight else Color.Unspecified)) {
                                append(if (b in 0x20..0x7E) b.toChar() else '·')
                            }
                        }
                    }
                    Text(
                        text,
                        style = style,
                        maxLines = 1,
                        softWrap = false,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(if (hl != null && off <= hl.last && off + perRow > hl.first) colors.currentLine else Color.Transparent)
                            .combinedClickable(
                                onClick = { highlight = off until off + perRow },
                                onLongClick = {
                                    program.binary.addressOfFileOffset(off)?.let { ws.goTo(it, CodeMode.LISTING) }
                                },
                            ),
                    )
                }
            }
        }
    }
}
