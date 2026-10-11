package com.ghidroid.ui.home

import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.FileOpen
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MediumExtendedFloatingActionButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ghidroid.R
import com.ghidroid.data.LibraryEntry
import com.ghidroid.engine.Engine
import com.ghidroid.ui.AppViewModel
import com.ghidroid.ui.EngineState
import com.ghidroid.ui.components.EmptyState
import com.ghidroid.ui.components.ShapeBadge
import com.ghidroid.ui.components.formatSize

const val GHIDRA_VERSION = "12.1.4"

@Composable
fun HomeScreen(vm: AppViewModel) {
    val entries by vm.library.entries.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val listState = rememberLazyListState()
    val fabExpanded by remember { derivedStateOf { listState.firstVisibleItemIndex == 0 } }
    var deleteTarget by remember { mutableStateOf<LibraryEntry?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importUri(uri)
    }
    val engine = vm.engine

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.app_name), fontWeight = FontWeight.SemiBold) },
                subtitle = {
                    val n = Engine.languages.size
                    Text(
                        if (engine is EngineState.Ready) stringResource(R.string.home_subtitle, GHIDRA_VERSION, n)
                        else stringResource(R.string.home_subtitle_loading, GHIDRA_VERSION),
                    )
                },
                actions = {
                    IconButton(onClick = vm::openSettings) {
                        Icon(Icons.Rounded.Settings, contentDescription = stringResource(R.string.settings))
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
        floatingActionButton = {
            if (engine is EngineState.Ready) {
                MediumExtendedFloatingActionButton(
                    text = { Text(stringResource(R.string.open_file)) },
                    icon = { Icon(Icons.Rounded.FileOpen, contentDescription = null) },
                    onClick = { picker.launch(arrayOf("*/*")) },
                    expanded = fabExpanded,
                )
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(top = padding.calculateTopPadding())) {
            when (engine) {
                is EngineState.Preparing -> PreparingCard(engine.progress)
                is EngineState.Failed -> ErrorCard(engine.message)
                EngineState.Ready -> Unit
            }
            if (entries.isEmpty()) {
                EmptyState(
                    icon = Icons.Rounded.Memory,
                    title = stringResource(R.string.empty_title),
                    text = stringResource(R.string.empty_text),
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = padding.calculateBottomPadding() + 112.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    item {
                        Text(
                            stringResource(R.string.recent_files),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(start = 4.dp, bottom = 2.dp),
                        )
                    }
                    items(entries, key = { it.id }) { entry ->
                        EntryCard(
                            entry = entry,
                            isOpen = vm.workspace?.entry?.id == entry.id,
                            enabled = engine is EngineState.Ready,
                            onOpen = {
                                if (vm.workspace?.entry?.id == entry.id) vm.screen = com.ghidroid.ui.Screen.WORKSPACE else vm.open(entry)
                            },
                            onOpenAs = { vm.requestLanguage(entry) },
                            onDelete = { deleteTarget = entry },
                            modifier = Modifier.animateItem(),
                        )
                    }
                }
            }
        }
    }

    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            icon = { Icon(Icons.Rounded.Delete, contentDescription = null) },
            title = { Text(stringResource(R.string.delete_title)) },
            text = { Text(stringResource(R.string.delete_text, target.name)) },
            confirmButton = {
                TextButton(onClick = {
                    deleteTarget = null
                    vm.delete(target)
                }) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun PreparingCard(progress: Float) {
    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                LoadingIndicator(Modifier.size(44.dp))
                Spacer(Modifier.width(14.dp))
                Column {
                    Text(stringResource(R.string.preparing_title), style = MaterialTheme.typography.titleMedium)
                    Text(
                        stringResource(R.string.preparing_text),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f),
                    )
                }
            }
            Spacer(Modifier.height(14.dp))
            LinearWavyProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun ErrorCard(message: String) {
    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.errorContainer,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Column(Modifier.padding(20.dp)) {
            Text(stringResource(R.string.engine_failed), style = MaterialTheme.typography.titleMedium)
            Text(message, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun EntryCard(
    entry: LibraryEntry,
    isOpen: Boolean,
    enabled: Boolean,
    onOpen: () -> Unit,
    onOpenAs: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var menu by remember { mutableStateOf(false) }
    val format = entry.format.substringBefore(' ')
    val (shape, badge) = when {
        format.startsWith("ELF") -> MaterialShapes.Cookie7Sided to "ELF"
        format.startsWith("PE") -> MaterialShapes.Clover4Leaf to "PE"
        format.startsWith("Mach") -> MaterialShapes.Sunny to "MO"
        format.startsWith("Raw") -> MaterialShapes.Square to "BIN"
        else -> MaterialShapes.Circle to "?"
    }
    ElevatedCard(onClick = onOpen, enabled = enabled, modifier = modifier.fillMaxWidth(), shape = MaterialTheme.shapes.extraLarge) {
        Row(Modifier.padding(start = 16.dp, top = 14.dp, bottom = 14.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            ShapeBadge(
                badge,
                shape,
                container = if (isOpen) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.primaryContainer,
                content = if (isOpen) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onPrimaryContainer,
                size = 48.dp,
            )
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(entry.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val parts = listOfNotNull(
                    entry.machine.takeIf { it.isNotEmpty() },
                    entry.format.takeIf { it.isNotEmpty() },
                    formatSize(entry.size),
                )
                Text(
                    parts.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    if (isOpen) stringResource(R.string.currently_open)
                    else DateUtils.getRelativeTimeSpanString(entry.openedAt, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString(),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isOpen) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                )
            }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, contentDescription = stringResource(R.string.more)) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.open_as)) },
                        leadingIcon = { Icon(Icons.Rounded.Tune, null) },
                        onClick = { menu = false; onOpenAs() },
                        enabled = enabled,
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.delete)) },
                        leadingIcon = { Icon(Icons.Rounded.Delete, null) },
                        onClick = { menu = false; onDelete() },
                    )
                }
            }
        }
    }
}
