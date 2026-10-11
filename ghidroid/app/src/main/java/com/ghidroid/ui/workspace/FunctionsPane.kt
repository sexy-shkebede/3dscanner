package com.ghidroid.ui.workspace

import android.content.ClipData
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ListAlt
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DataObject
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ghidroid.R
import com.ghidroid.analysis.FunctionInfo
import com.ghidroid.analysis.FunctionKind
import com.ghidroid.loader.Addr
import com.ghidroid.ui.components.EmptyState
import com.ghidroid.ui.components.SearchField
import com.ghidroid.ui.components.ShapeBadge
import com.ghidroid.ui.theme.CodeFont
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope

private enum class FnFilter(val label: Int) { ALL(R.string.filter_all), EXPORTED(R.string.filter_exported), IMPORTS(R.string.filter_imports), LOCAL(R.string.filter_local), DISCOVERED(R.string.filter_discovered) }

@Composable
fun FunctionsPane(ws: Workspace) {
    val program = ws.program
    val functions by program.functions.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf(FnFilter.ALL) }
    var menuFor by remember { mutableStateOf<FunctionInfo?>(null) }
    val digits = remember(program) { if (program.binary.blocks.any { Addr.cmp(it.end, 0xFFFFFFFFL) > 0 }) 16 else 8 }

    val filtered by remember(functions, query, filter) {
        derivedStateOf {
            val q = query.trim()
            val addrQuery = Addr.parse(q)
            functions.filter { f ->
                val kindOk = when (filter) {
                    FnFilter.ALL -> true
                    FnFilter.EXPORTED -> f.kind == FunctionKind.EXPORTED || f.kind == FunctionKind.ENTRY
                    FnFilter.IMPORTS -> f.kind == FunctionKind.IMPORT
                    FnFilter.LOCAL -> f.kind == FunctionKind.LOCAL
                    FnFilter.DISCOVERED -> f.kind == FunctionKind.DISCOVERED
                }
                kindOk && (q.isEmpty() || f.displayName.contains(q, ignoreCase = true) ||
                    (f.mangled?.contains(q, ignoreCase = true) == true) ||
                    (addrQuery != null && Addr.hex(f.address).contains(q.removePrefix("0x").lowercase())))
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        SearchField(
            query = query,
            onQueryChange = { query = it },
            placeholder = stringResource(R.string.search_functions, functions.size),
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp),
        )
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FnFilter.entries.forEach { f ->
                FilterChip(selected = filter == f, onClick = { filter = f }, label = { Text(stringResource(f.label)) })
            }
        }
        if (filtered.isEmpty()) {
            EmptyState(
                icon = Icons.Rounded.SearchOff,
                title = stringResource(R.string.nothing_found),
                text = stringResource(R.string.nothing_found_text),
                modifier = Modifier.fillMaxSize(),
                shape = MaterialShapes.Pill,
            )
        } else {
            LazyColumn(contentPadding = PaddingValues(bottom = 16.dp), modifier = Modifier.fillMaxSize()) {
                items(filtered, key = { it.address }) { f ->
                    FunctionRow(f, digits, onClick = { ws.openFunction(f) }, onLongClick = { menuFor = f })
                }
            }
        }
    }

    menuFor?.let { f -> FunctionMenu(ws, f, onDismiss = { menuFor = null }) }
}

@Composable
private fun FunctionRow(f: FunctionInfo, digits: Int, onClick: () -> Unit, onLongClick: () -> Unit) {
    val (badge, shape, container, content) = when (f.kind) {
        FunctionKind.IMPORT -> Quad("im", MaterialShapes.Clover4Leaf, MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.colorScheme.onTertiaryContainer)
        FunctionKind.EXPORTED -> Quad("ex", MaterialShapes.Cookie6Sided, MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer)
        FunctionKind.ENTRY -> Quad("▶", MaterialShapes.Sunny, MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.onPrimary)
        FunctionKind.LOCAL -> Quad("f", MaterialShapes.Circle, MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.onSecondaryContainer)
        FunctionKind.DISCOVERED -> Quad("?", MaterialShapes.Square, MaterialTheme.colorScheme.surfaceContainerHighest, MaterialTheme.colorScheme.onSurfaceVariant)
    }
    ListItem(
        headlineContent = { Text(f.displayName, fontFamily = CodeFont, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium) },
        supportingContent = {
            Text(
                "0x" + Addr.hex(f.address, digits) + if (f.size > 0) " · ${f.size} B" else "",
                fontFamily = CodeFont,
                style = MaterialTheme.typography.bodySmall,
            )
        },
        leadingContent = { ShapeBadge(badge, shape, container, content) },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick),
    )
}

private data class Quad<A, B, C, D>(val a: A, val b: B, val c: C, val d: D)

@Composable
fun FunctionMenu(ws: Workspace, f: FunctionInfo, onDismiss: () -> Unit) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.navigationBarsPadding().padding(bottom = 12.dp)) {
            Text(
                f.displayName,
                style = MaterialTheme.typography.titleMedium,
                fontFamily = CodeFont,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
            Text(
                "0x" + Addr.hex(f.address) + (f.mangled?.let { " · $it" } ?: ""),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = CodeFont,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
            Spacer(Modifier.height(8.dp))
            MenuAction(Icons.Rounded.DataObject, stringResource(R.string.action_decompile)) { onDismiss(); ws.goTo(f.address, CodeMode.DECOMPILER) }
            MenuAction(Icons.AutoMirrored.Rounded.ListAlt, stringResource(R.string.action_listing)) { onDismiss(); ws.goTo(f.address, CodeMode.LISTING) }
            MenuAction(Icons.Rounded.Hub, stringResource(R.string.action_references)) { onDismiss(); ws.findReferences(f.address, f.displayName) }
            MenuAction(Icons.Rounded.ContentCopy, stringResource(R.string.action_copy_name)) {
                onDismiss()
                scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("name", f.displayName))) }
            }
            MenuAction(Icons.Rounded.ContentCopy, stringResource(R.string.action_copy_address)) {
                onDismiss()
                scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("address", "0x" + Addr.hex(f.address)))) }
            }
        }
    }
}

@Composable
fun MenuAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(label) },
        leadingContent = { Icon(icon, contentDescription = null) },
        colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
        modifier = Modifier.combinedClickable(onClick = onClick),
    )
}
