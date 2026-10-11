package com.ghidroid.ui.workspace

import android.content.ClipData
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.automirrored.rounded.ListAlt
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DataObject
import androidx.compose.material.icons.rounded.Functions
import androidx.compose.material.icons.rounded.Grid4x4
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FloatingToolbarDefaults
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ghidroid.R
import com.ghidroid.analysis.CodeLine
import com.ghidroid.analysis.DecompiledFunction
import com.ghidroid.analysis.Hl
import com.ghidroid.data.AppSettings
import com.ghidroid.loader.Addr
import com.ghidroid.ui.components.EmptyState
import com.ghidroid.ui.theme.CodeFont
import com.ghidroid.ui.theme.CodeTheme
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch

@Composable
fun CodePane(ws: Workspace, settings: AppSettings) {
    if (ws.current == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                EmptyState(
                    icon = Icons.Rounded.DataObject,
                    title = stringResource(R.string.code_empty_title),
                    text = stringResource(R.string.code_empty_text),
                    shape = MaterialShapes.Clover8Leaf,
                )
                FilledTonalButton(onClick = { ws.tab = WsTab.FUNCTIONS }) {
                    Icon(Icons.Rounded.Functions, null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.tab_functions))
                }
            }
        }
        return
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val split = maxWidth >= 840.dp
        Column(Modifier.fillMaxSize()) {
            LocationBar(ws)
            LaunchedEffect(split) { ws.updateSplit(split) }
            if (split) {
                Row(Modifier.fillMaxSize()) {
                    ListingView(ws, settings, Modifier.weight(1f).fillMaxHeight())
                    Box(Modifier.width(1.dp).fillMaxHeight().background(MaterialTheme.colorScheme.outlineVariant))
                    DecompilerView(ws, settings, Modifier.weight(1f).fillMaxHeight())
                }
            } else {
                when (ws.mode) {
                    CodeMode.LISTING -> ListingView(ws, settings, Modifier.fillMaxSize())
                    CodeMode.DECOMPILER -> DecompilerView(ws, settings, Modifier.fillMaxSize())
                }
            }
        }
        CodeToolbar(ws, showToggle = !split, modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp))
    }
}

@Composable
private fun LocationBar(ws: Workspace) {
    val addr = ws.current ?: return
    val program = ws.program
    val fn = program.function(addr) ?: program.functionContaining(addr)
    val fmt = remember(program) { AddressFormat(program) }
    var menu by remember { mutableStateOf(false) }
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .combinedClickable(onClick = { menu = fn != null })
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    fn?.displayName ?: program.nameAt(addr) ?: program.binary.sectionAt(addr)?.name ?: "—",
                    style = MaterialTheme.typography.titleSmall,
                    fontFamily = CodeFont,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "0x" + fmt.format(addr) + (program.binary.sectionAt(addr)?.let { " · ${it.name}" } ?: ""),
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = CodeFont,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
    if (menu && fn != null) FunctionMenu(ws, fn, onDismiss = { menu = false })
}

@Composable
private fun CodeToolbar(ws: Workspace, showToggle: Boolean, modifier: Modifier = Modifier) {
    HorizontalFloatingToolbar(
        expanded = true,
        colors = FloatingToolbarDefaults.standardFloatingToolbarColors(),
        modifier = modifier,
    ) {
        IconButton(onClick = { ws.goBack() }, enabled = ws.back.isNotEmpty()) {
            Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.back))
        }
        IconButton(onClick = { ws.goForward() }, enabled = ws.forward.isNotEmpty()) {
            Icon(Icons.AutoMirrored.Rounded.ArrowForward, contentDescription = stringResource(R.string.forward))
        }
        if (showToggle) {
            Spacer(Modifier.width(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)) {
                ToggleButton(
                    checked = ws.mode == CodeMode.DECOMPILER,
                    onCheckedChange = { ws.switchMode(CodeMode.DECOMPILER) },
                    shapes = ButtonGroupDefaults.connectedLeadingButtonShapes(),
                ) { Text("C", fontWeight = FontWeight.Bold, fontFamily = CodeFont) }
                ToggleButton(
                    checked = ws.mode == CodeMode.LISTING,
                    onCheckedChange = { ws.switchMode(CodeMode.LISTING) },
                    shapes = ButtonGroupDefaults.connectedTrailingButtonShapes(),
                ) { Text("ASM", fontWeight = FontWeight.Bold, fontFamily = CodeFont) }
            }
            Spacer(Modifier.width(4.dp))
        }
        IconButton(onClick = {
            val a = ws.current ?: return@IconButton
            val fn = ws.program.function(a) ?: ws.program.functionContaining(a)
            ws.findReferences(fn?.address ?: a, fn?.displayName ?: ("0x" + Addr.hex(a)))
        }) {
            Icon(Icons.Rounded.Hub, contentDescription = stringResource(R.string.action_references))
        }
    }
}

// ---------------------------------------------------------------------------------- listing

@Composable
fun ListingView(ws: Workspace, settings: AppSettings, modifier: Modifier = Modifier) {
    val listing = ws.listing
    val program = ws.program
    val colors = CodeTheme.colors
    val fmt = remember(program) { AddressFormat(program) }
    val state = rememberLazyListState()
    var selected by remember { mutableStateOf<Long?>(null) }
    var menuRow by remember { mutableStateOf<ListingRow?>(null) }
    val rows = listing.rows
    // Wrapped instructions continue under the mnemonic column rather than under the address.
    val hang = fmt.digits + 2 + if (settings.showBytes) (if (program.alignment >= 4) 13 else 25) else 0
    val style = TextStyle(
        fontFamily = CodeFont,
        fontSize = settings.codeFontSize.sp,
        lineHeight = (settings.codeFontSize * 1.45f).sp,
        textIndent = TextIndent(restLine = (settings.codeFontSize * 0.6f * hang).sp),
    )

    LaunchedEffect(listing.scrollTarget) {
        val t = listing.scrollTarget ?: return@LaunchedEffect
        state.scrollToItem(maxOf(0, t.first - 2))
        selected = t.second
        listing.scrollTarget = null
    }
    LaunchedEffect(listing) {
        snapshotFlow {
            val info = state.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: -1
            info.totalItemsCount > 0 && last >= info.totalItemsCount - 40
        }.distinctUntilChanged().filter { it }.collect { listing.loadMore() }
    }
    LaunchedEffect(listing) {
        snapshotFlow { state.firstVisibleItemIndex < 15 && state.layoutInfo.totalItemsCount > 0 && state.isScrollInProgress }
            .distinctUntilChanged().filter { it }.collect { listing.loadPrevious() }
    }

    Box(modifier) {
        if (rows.isEmpty() && listing.loading) {
            LoadingIndicator(Modifier.align(Alignment.Center).size(64.dp))
        }
        LazyColumn(
            state = state,
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 112.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            itemsIndexed(rows, key = { _, r -> r.key }) { _, row ->
                when (row) {
                    is ListingRow.SectionStart -> Text(
                        "── ${row.name} ──",
                        style = style.copy(color = colors.comment, textAlign = TextAlign.Center),
                        modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
                    )
                    is ListingRow.FunctionStart -> Column(Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 4.dp)) {
                        Box(Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.outlineVariant))
                        Spacer(Modifier.height(6.dp))
                        Text(
                            row.info.displayName,
                            style = style.copy(color = colors.function, fontWeight = FontWeight.Bold),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.combinedClickable(
                                onClick = { ws.goTo(row.address, CodeMode.DECOMPILER) },
                                onLongClick = { menuRow = row },
                            ),
                        )
                    }
                    is ListingRow.Label -> Text(
                        row.name + ":",
                        style = style.copy(color = colors.global, fontWeight = FontWeight.SemiBold),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    is ListingRow.Gap -> Spacer(Modifier.height(8.dp))
                    is ListingRow.Code -> {
                        val text = remember(row.insn, settings.showBytes, settings.lowercaseMnemonics, colors, program.strings.value.size) {
                            buildInstruction(row.insn, program, colors, fmt, settings.showBytes, settings.lowercaseMnemonics) { a ->
                                ws.goTo(a, if (program.function(a) != null) null else CodeMode.LISTING)
                            }
                        }
                        Text(
                            text,
                            style = style,
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(if (selected == row.address) colors.currentLine else Color.Transparent)
                                .combinedClickable(onClick = { selected = row.address }, onLongClick = { selected = row.address; menuRow = row }),
                        )
                    }
                    is ListingRow.Data -> {
                        val text = remember(row, colors) { buildDataRow(row, program, colors, fmt) { a -> ws.goTo(a) } }
                        Text(
                            text,
                            style = style,
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(if (selected == row.address) colors.currentLine else Color.Transparent)
                                .combinedClickable(onClick = { selected = row.address }, onLongClick = { selected = row.address; menuRow = row }),
                        )
                    }
                }
            }
        }
    }
    menuRow?.let { r -> RowMenu(ws, r, onDismiss = { menuRow = null }) }
}

private fun buildDataRow(
    row: ListingRow.Data,
    program: com.ghidroid.analysis.Program,
    colors: com.ghidroid.ui.theme.CodeColors,
    fmt: AddressFormat,
    onNavigate: (Long) -> Unit,
) = androidx.compose.ui.text.buildAnnotatedString {
    fun styled(color: Color, s: String) = withStyleCompat(this, color, s)
    styled(colors.address, fmt.format(row.address))
    append("  ")
    val ps = program.pointerSize
    when (row.kind) {
        DataKind.STRING -> {
            styled(colors.keyword, "ds".padEnd(8))
            styled(colors.string, "\"" + escapeString(row.text, 200) + "\"")
        }
        DataKind.WSTRING -> {
            styled(colors.keyword, "unicode".padEnd(8))
            styled(colors.string, "u\"" + escapeString(row.text, 200) + "\"")
        }
        DataKind.POINTER -> {
            styled(colors.keyword, (if (ps == 8) "dq" else if (ps == 4) "dd" else "dw").padEnd(8))
            withLinkCompat(this, colors.function, "0x" + Addr.hex(row.pointer)) { onNavigate(row.pointer) }
            describeAddress(program, row.pointer)?.let { (d, isStr) ->
                append("  ")
                styled(if (isStr) colors.string else colors.comment, "; $d")
            }
        }
        DataKind.BYTES -> {
            styled(colors.keyword, "db".padEnd(8))
            styled(colors.constant, row.text)
        }
        DataKind.UNINITIALIZED -> {
            styled(colors.keyword, "??".padEnd(8))
            styled(colors.comment, "× 0x" + Addr.hex(row.size.toLong()))
        }
    }
}

private fun withStyleCompat(b: androidx.compose.ui.text.AnnotatedString.Builder, color: Color, s: String) {
    val i = b.pushStyle(androidx.compose.ui.text.SpanStyle(color = color))
    b.append(s)
    b.pop(i)
}

private fun withLinkCompat(b: androidx.compose.ui.text.AnnotatedString.Builder, color: Color, s: String, onClick: () -> Unit) {
    val i = b.pushLink(link(color, onClick))
    b.append(s)
    b.pop(i)
}

@Composable
private fun RowMenu(ws: Workspace, row: ListingRow, onDismiss: () -> Unit) {
    val program = ws.program
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val fn = program.function(row.address) ?: program.functionContaining(row.address)
    val target = (row as? ListingRow.Code)?.insn?.let { if (it.target != -1L) it.target else if (it.ref != -1L) it.ref else null }
        ?: (row as? ListingRow.Data)?.pointer?.takeIf { it != -1L }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.navigationBarsPadding().padding(bottom = 12.dp)) {
            Text(
                "0x" + Addr.hex(row.address) + (fn?.let { "  ·  ${it.displayName}" } ?: ""),
                style = MaterialTheme.typography.titleSmall,
                fontFamily = CodeFont,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
            )
            if (fn != null) MenuAction(Icons.Rounded.DataObject, stringResource(R.string.action_decompile)) { onDismiss(); ws.goTo(row.address, CodeMode.DECOMPILER) }
            MenuAction(Icons.Rounded.Hub, stringResource(R.string.action_refs_here)) {
                onDismiss(); ws.findReferences(row.address, program.nameAt(row.address) ?: ("0x" + Addr.hex(row.address)))
            }
            if (target != null) MenuAction(Icons.AutoMirrored.Rounded.ListAlt, stringResource(R.string.action_follow, Addr.hex(target))) { onDismiss(); ws.goTo(target) }
            val len = (row as? ListingRow.Code)?.insn?.length ?: (row as? ListingRow.Data)?.size ?: 1
            MenuAction(Icons.Rounded.Grid4x4, stringResource(R.string.action_show_hex)) { onDismiss(); ws.showInHex(row.address, len) }
            MenuAction(Icons.Rounded.ContentCopy, stringResource(R.string.action_copy_address)) {
                onDismiss()
                scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("address", "0x" + Addr.hex(row.address)))) }
            }
            (row as? ListingRow.Code)?.insn?.let { insn ->
                MenuAction(Icons.Rounded.ContentCopy, stringResource(R.string.action_copy_line)) {
                    onDismiss()
                    val line = "0x${Addr.hex(insn.address)}  ${insn.mnemonic} ${insn.operands}"
                    scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("line", line))) }
                }
            }
        }
    }
}

// ------------------------------------------------------------------------------- decompiler

@Composable
fun DecompilerView(ws: Workspace, settings: AppSettings, modifier: Modifier = Modifier) {
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val style = TextStyle(fontFamily = CodeFont, fontSize = settings.codeFontSize.sp, lineHeight = (settings.codeFontSize * 1.45f).sp)
    val charWidthPx = remember(settings.codeFontSize) { measurer.measure("0123456789", style).size.width / 10f }

    BoxWithConstraints(modifier) {
        val availablePx = with(density) { (maxWidth - 24.dp - 36.dp).toPx() }
        val fitChars = (availablePx / charWidthPx).toInt()
        if (settings.decompilerWidth == 0) LaunchedEffect(fitChars) { ws.onWidthChanged(fitChars) }

        when (val d = ws.decomp) {
            DecompState.Idle -> Unit
            is DecompState.Loading -> Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                LoadingIndicator(Modifier.size(72.dp))
                Spacer(Modifier.height(12.dp))
                Text(
                    stringResource(R.string.decompiling, d.function?.name ?: ("0x" + Addr.hex(d.address))),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 24.dp),
                )
            }
            is DecompState.Failed -> Column(Modifier.align(Alignment.Center).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Rounded.WarningAmber, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(48.dp))
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.decompile_failed), style = MaterialTheme.typography.titleMedium)
                Text(d.message, style = MaterialTheme.typography.bodySmall, fontFamily = CodeFont, textAlign = TextAlign.Center)
                Spacer(Modifier.height(12.dp))
                FilledTonalButton(onClick = { ws.switchMode(CodeMode.LISTING) }) { Text(stringResource(R.string.show_listing)) }
            }
            is DecompState.Ready -> CodeLines(ws, d.code, settings, style, charWidthPx, fixedWidth = settings.decompilerWidth > 0)
        }
    }
}

@Composable
private fun CodeLines(
    ws: Workspace,
    code: DecompiledFunction,
    settings: AppSettings,
    style: TextStyle,
    charWidthPx: Float,
    fixedWidth: Boolean,
) {
    val colors = CodeTheme.colors
    val density = LocalDensity.current
    var highlight by remember(code) { mutableStateOf<String?>(null) }
    var selected by remember(code) { mutableStateOf(-1) }
    var menuLine by remember { mutableStateOf<Pair<Int, CodeLine>?>(null) }
    val state = rememberLazyListState()
    val gutterDigits = code.lines.size.toString().length
    val program = ws.program

    // Scroll to the line that matches the current address when it is inside the function.
    LaunchedEffect(code, ws.current) {
        val cur = ws.current ?: return@LaunchedEffect
        if (cur == code.address) return@LaunchedEffect
        val idx = code.lines.indexOfFirst { it.address == cur }
        if (idx >= 0) {
            selected = idx
            state.scrollToItem(maxOf(0, idx - 4))
        }
    }

    val hScroll = rememberScrollState()
    val contentWidth = with(density) { ((code.maxLineLength + gutterDigits + 3) * charWidthPx).toDp() + 32.dp }
    val listModifier = if (fixedWidth) Modifier.width(contentWidth).fillMaxHeight() else Modifier.fillMaxSize()
    Box(if (fixedWidth) Modifier.fillMaxSize().horizontalScroll(hScroll) else Modifier.fillMaxSize()) {
        LazyColumn(
            state = state,
            contentPadding = PaddingValues(start = 8.dp, end = 12.dp, top = 8.dp, bottom = 112.dp),
            modifier = listModifier,
        ) {
            itemsIndexed(code.lines) { i, line ->
                val text = remember(line, highlight, colors) {
                    buildCodeLine(
                        line, colors, highlight,
                        onNavigate = { a, kind ->
                            when {
                                program.function(a) != null -> ws.goTo(a, CodeMode.DECOMPILER)
                                kind == Hl.FUNCTION && program.isCode(a) -> ws.goTo(a, CodeMode.DECOMPILER)
                                program.isMapped(a) && a in (code.address until code.address + 0x100000) && kind != Hl.GLOBAL -> ws.goTo(a, CodeMode.LISTING)
                                program.isMapped(a) -> ws.goTo(a, CodeMode.LISTING)
                            }
                        },
                        onToken = { t -> highlight = if (highlight == t) null else t },
                    )
                }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(if (selected == i) colors.currentLine else Color.Transparent)
                        .combinedClickable(onClick = { selected = if (selected == i) -1 else i }, onLongClick = { selected = i; menuLine = i to line }),
                ) {
                    Text(
                        (i + 1).toString().padStart(gutterDigits),
                        style = style.copy(color = colors.bytes),
                        modifier = Modifier.padding(end = 10.dp),
                    )
                    Text(text, style = style, softWrap = !fixedWidth)
                }
            }
        }
    }
    menuLine?.let { (i, line) -> LineMenu(ws, code, i, line, onDismiss = { menuLine = null }) }
}

@Composable
private fun LineMenu(ws: Workspace, code: DecompiledFunction, index: Int, line: CodeLine, onDismiss: () -> Unit) {
    val clipboard = LocalClipboard.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val addr = line.address.takeIf { it != -1L } ?: code.lines.drop(index).firstOrNull { it.address != -1L }?.address
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.navigationBarsPadding().padding(bottom = 12.dp)) {
            Text(
                line.text.trim(),
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = CodeFont,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
            )
            if (addr != null) {
                MenuAction(Icons.AutoMirrored.Rounded.ListAlt, stringResource(R.string.action_show_listing_at, Addr.hex(addr))) {
                    onDismiss(); ws.goTo(addr, CodeMode.LISTING)
                }
            }
            MenuAction(Icons.Rounded.ContentCopy, stringResource(R.string.action_copy_line)) {
                onDismiss(); scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("line", line.text.trim()))) }
            }
            MenuAction(Icons.Rounded.ContentCopy, stringResource(R.string.action_copy_function)) {
                onDismiss(); scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("code", code.plainText))) }
            }
            MenuAction(Icons.Rounded.Share, stringResource(R.string.action_share_function)) {
                onDismiss()
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, code.plainText)
                }
                context.startActivity(Intent.createChooser(send, null))
            }
        }
    }
}
