package com.ghidroid.ui.workspace

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ghidroid.R
import com.ghidroid.engine.Engine
import com.ghidroid.engine.Language
import com.ghidroid.loader.Addr
import com.ghidroid.loader.LoadedBinary
import com.ghidroid.loader.Loaders
import com.ghidroid.ui.components.SearchField
import com.ghidroid.ui.theme.CodeFont

@Composable
fun GoToDialog(ws: Workspace, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf("") }
    val program = ws.program
    val functions = program.functions.value
    val matches = remember(text) {
        val q = text.trim()
        if (q.length < 2 || Addr.parse(q) != null && q.startsWith("0x")) emptyList()
        else functions.filter { it.displayName.contains(q, ignoreCase = true) }.take(30)
    }
    var error by remember { mutableStateOf(false) }

    fun submit() {
        val q = text.trim()
        val f = functions.firstOrNull { it.displayName == q || it.name == q } ?: matches.singleOrNull()
        if (f != null) {
            onDismiss(); ws.openFunction(f); return
        }
        val a = Addr.parse(q)
        if (a != null && program.isMapped(a)) {
            onDismiss(); ws.goTo(a)
        } else error = true
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.go_to)) },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it; error = false },
                    label = { Text(stringResource(R.string.go_to_hint)) },
                    singleLine = true,
                    isError = error,
                    supportingText = if (error) ({ Text(stringResource(R.string.go_to_error)) }) else null,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = CodeFont),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { submit() }),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (matches.isNotEmpty()) {
                    LazyColumn(Modifier.heightIn(max = 280.dp).padding(top = 8.dp)) {
                        items(matches, key = { it.address }) { f ->
                            Text(
                                f.displayName,
                                fontFamily = CodeFont,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onDismiss(); ws.openFunction(f) }
                                    .padding(vertical = 10.dp, horizontal = 4.dp),
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { submit() }) { Text(stringResource(R.string.go)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
fun ReferencesSheet(ws: Workspace, refs: RefsState) {
    val fmt = remember(ws.program) { AddressFormat(ws.program) }
    ModalBottomSheet(onDismissRequest = { ws.cancelReferences() }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.85f).navigationBarsPadding()) {
            Text(
                stringResource(R.string.references_to, refs.title),
                style = MaterialTheme.typography.titleLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
            Text(
                if (refs.done) stringResource(R.string.references_found, refs.results.size) else stringResource(R.string.references_searching),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
            )
            if (!refs.done) {
                LinearWavyProgressIndicator(progress = { refs.progress }, modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp))
            }
            LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                items(refs.results, key = { (if (it.isData) "d" else "c") + it.address }) { r ->
                    ListItem(
                        headlineContent = {
                            Text(
                                if (r.isData) stringResource(R.string.ref_data, r.preview.ifEmpty { "0x" + fmt.format(r.address) }) else r.preview,
                                fontFamily = CodeFont,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        supportingContent = {
                            Text(
                                "0x" + fmt.format(r.address) + (r.function?.let { " · ${it.displayName}" } ?: ""),
                                fontFamily = CodeFont,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        modifier = Modifier.clickable {
                            ws.cancelReferences()
                            ws.goTo(r.address, if (r.isData) CodeMode.LISTING else null)
                        },
                    )
                }
            }
        }
    }
}

/** Chooses the Ghidra language / compiler spec (and the raw-image load address). */
@Composable
fun LanguagePickerSheet(
    binary: LoadedBinary?,
    initialLanguage: String?,
    initialRaw: Boolean,
    initialBase: Long,
    onDismiss: () -> Unit,
    onConfirm: (languageId: String, compilerId: String, raw: Boolean, base: Long) -> Unit,
) {
    val languages = Engine.languages
    val suggested = remember(binary) { binary?.let { Loaders.resolveLanguage(it, languages) } }
    var query by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf(initialLanguage?.let { Engine.language(it) } ?: suggested) }
    var compiler by remember(selected) { mutableStateOf(selected?.compilerOrDefault(binary?.compiler)?.id) }
    var raw by remember { mutableStateOf(initialRaw || binary == null || binary.format == com.ghidroid.loader.BinaryFormat.RAW) }
    var baseText by remember { mutableStateOf("0x" + Addr.hex(initialBase)) }
    val filtered = remember(query) {
        val q = query.trim()
        languages.filter { !it.deprecated && (q.isEmpty() || it.id.contains(q, true) || it.description.contains(q, true) || it.processor.contains(q, true)) }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.92f).navigationBarsPadding().padding(horizontal = 20.dp)) {
            Text(stringResource(R.string.language_title), style = MaterialTheme.typography.headlineSmallEmphasized)
            Text(
                binary?.let { "${it.formatName} · ${it.machine}" } ?: stringResource(R.string.language_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            SearchField(query, { query = it }, stringResource(R.string.search_languages, languages.size))
            LazyColumn(Modifier.weight(1f).padding(top = 8.dp)) {
                items(filtered, key = { it.id }) { l ->
                    LanguageRow(l, selected = selected?.id == l.id, suggested = suggested?.id == l.id) { selected = l }
                }
            }
            selected?.let { lang ->
                Text(stringResource(R.string.compiler_spec), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    lang.compilers.forEach { c ->
                        FilterChip(selected = compiler == c.id, onClick = { compiler = c.id }, label = { Text(c.name) })
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.load_raw), style = MaterialTheme.typography.bodyLarge)
                    Text(stringResource(R.string.load_raw_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = raw, onCheckedChange = { raw = it })
            }
            if (raw) {
                OutlinedTextField(
                    value = baseText,
                    onValueChange = { baseText = it },
                    label = { Text(stringResource(R.string.base_address)) },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = CodeFont),
                    isError = Addr.parse(baseText) == null,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
                Spacer(Modifier.width(8.dp))
                Button(
                    enabled = selected != null && (!raw || Addr.parse(baseText) != null),
                    onClick = {
                        val l = selected ?: return@Button
                        onConfirm(l.id, compiler ?: l.compilerOrDefault(null).id, raw, if (raw) Addr.parse(baseText) ?: 0L else 0L)
                    },
                ) { Text(stringResource(R.string.open)) }
            }
        }
    }
}

@Composable
private fun LanguageRow(l: Language, selected: Boolean, suggested: Boolean, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(l.id, fontFamily = CodeFont, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Text(
                (if (suggested) "★ " else "") + l.description,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        },
        colors = ListItemDefaults.colors(
            containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
        ),
        modifier = Modifier.clickable(onClick = onClick),
    )
}
