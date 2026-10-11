package com.ghidroid.ui.workspace

import android.content.ClipData
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ListAlt
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Grid4x4
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material.icons.rounded.TextFields
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ghidroid.R
import com.ghidroid.analysis.AnalysisStage
import com.ghidroid.analysis.StringInfo
import com.ghidroid.loader.Addr
import com.ghidroid.ui.components.EmptyState
import com.ghidroid.ui.components.SearchField
import com.ghidroid.ui.theme.CodeFont
import com.ghidroid.ui.theme.CodeTheme
import kotlinx.coroutines.launch

@Composable
fun StringsPane(ws: Workspace) {
    val program = ws.program
    val strings by program.strings.collectAsStateWithLifecycle()
    val analysis by ws.analyzer.state.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    var menuFor by remember { mutableStateOf<StringInfo?>(null) }
    val fmt = remember(program) { AddressFormat(program) }
    val filtered by remember(strings, query) {
        derivedStateOf { if (query.isBlank()) strings else strings.filter { it.text.contains(query, ignoreCase = true) } }
    }

    Column(Modifier.fillMaxSize()) {
        SearchField(
            query = query,
            onQueryChange = { query = it },
            placeholder = stringResource(R.string.search_strings, strings.size),
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 8.dp),
        )
        when {
            strings.isEmpty() && analysis.stage == AnalysisStage.STRINGS -> Column(
                Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(Modifier.height(64.dp))
                LoadingIndicator(Modifier.size(64.dp))
                Text(stringResource(R.string.analysis_strings), style = MaterialTheme.typography.bodyMedium)
            }
            filtered.isEmpty() -> EmptyState(
                icon = Icons.Rounded.TextFields,
                title = stringResource(R.string.no_strings),
                text = stringResource(R.string.no_strings_text),
                modifier = Modifier.fillMaxSize(),
                shape = MaterialShapes.Puffy,
            )
            else -> LazyColumn(contentPadding = PaddingValues(bottom = 16.dp), modifier = Modifier.fillMaxSize()) {
                items(filtered, key = { it.address }) { s ->
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .combinedClickable(onClick = { menuFor = s })
                            .padding(horizontal = 20.dp, vertical = 10.dp),
                    ) {
                        Text(
                            s.text,
                            style = MaterialTheme.typography.bodyMedium,
                            fontFamily = CodeFont,
                            color = CodeTheme.colors.string,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Row {
                            Text(
                                "0x" + fmt.format(s.address) + " · " + (s.section ?: "") + (if (s.wide) " · UTF-16" else "") + " · ${s.text.length}",
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = CodeFont,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    HorizontalDivider(Modifier.padding(horizontal = 20.dp), color = MaterialTheme.colorScheme.surfaceContainerHighest)
                }
            }
        }
    }

    menuFor?.let { s ->
        val clipboard = LocalClipboard.current
        val scope = rememberCoroutineScope()
        ModalBottomSheet(onDismissRequest = { menuFor = null }) {
            Column(Modifier.navigationBarsPadding().padding(bottom = 12.dp)) {
                Text(
                    s.text,
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = CodeFont,
                    maxLines = 6,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
                )
                MenuAction(Icons.Rounded.Hub, stringResource(R.string.action_references)) {
                    menuFor = null
                    ws.findReferences(s.address, "\"" + escapeString(s.text, 40) + "\"")
                }
                MenuAction(Icons.AutoMirrored.Rounded.ListAlt, stringResource(R.string.action_listing)) { menuFor = null; ws.goTo(s.address, CodeMode.LISTING) }
                MenuAction(Icons.Rounded.Grid4x4, stringResource(R.string.action_show_hex)) { menuFor = null; ws.showInHex(s.address, s.byteLength) }
                MenuAction(Icons.Rounded.ContentCopy, stringResource(R.string.action_copy_text)) {
                    menuFor = null
                    scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("string", s.text))) }
                }
                MenuAction(Icons.Rounded.ContentCopy, stringResource(R.string.action_copy_address)) {
                    menuFor = null
                    scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("address", "0x" + Addr.hex(s.address)))) }
                }
            }
        }
    }
}
