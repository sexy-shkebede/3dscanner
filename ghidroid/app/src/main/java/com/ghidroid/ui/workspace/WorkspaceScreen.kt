package com.ghidroid.ui.workspace

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Dashboard
import androidx.compose.material.icons.rounded.Functions
import androidx.compose.material.icons.rounded.Grid4x4
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.TextFields
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ShortNavigationBar
import androidx.compose.material3.ShortNavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ghidroid.R
import com.ghidroid.ui.AppViewModel

private data class TabSpec(val tab: WsTab, val icon: ImageVector, val label: Int)

private val Tabs = listOf(
    TabSpec(WsTab.OVERVIEW, Icons.Rounded.Dashboard, R.string.tab_overview),
    TabSpec(WsTab.FUNCTIONS, Icons.Rounded.Functions, R.string.tab_functions),
    TabSpec(WsTab.CODE, Icons.Rounded.Code, R.string.tab_code),
    TabSpec(WsTab.STRINGS, Icons.Rounded.TextFields, R.string.tab_strings),
    TabSpec(WsTab.HEX, Icons.Rounded.Grid4x4, R.string.tab_hex),
)

@Composable
fun WorkspaceScreen(vm: AppViewModel, ws: Workspace) {
    val settings by vm.settings.state.collectAsStateWithLifecycle()
    var showGoTo by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    val program = ws.program

    BackHandler {
        when {
            ws.tab == WsTab.CODE && ws.goBack() -> Unit
            ws.tab != WsTab.OVERVIEW -> ws.tab = WsTab.OVERVIEW
            else -> vm.screen = com.ghidroid.ui.Screen.HOME
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(program.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                        Text(
                            "${program.binary.formatName} · ${program.language.id}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { vm.screen = com.ghidroid.ui.Screen.HOME }) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                actions = {
                    IconButton(onClick = { showGoTo = true }) {
                        Icon(Icons.Rounded.MyLocation, contentDescription = stringResource(R.string.go_to))
                    }
                    IconButton(onClick = { menu = true }) {
                        Icon(Icons.Rounded.MoreVert, contentDescription = stringResource(R.string.more))
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.change_language)) },
                            leadingIcon = { Icon(Icons.Rounded.Tune, null) },
                            onClick = { menu = false; vm.requestLanguage(ws.entry) },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.settings)) },
                            leadingIcon = { Icon(Icons.Rounded.Settings, null) },
                            onClick = { menu = false; vm.openSettings() },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.close_file)) },
                            leadingIcon = { Icon(Icons.Rounded.Close, null) },
                            onClick = { menu = false; vm.closeWorkspace() },
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            )
        },
        bottomBar = {
            ShortNavigationBar {
                Tabs.forEach { spec ->
                    ShortNavigationBarItem(
                        selected = ws.tab == spec.tab,
                        onClick = { ws.tab = spec.tab },
                        icon = { Icon(spec.icon, contentDescription = null) },
                        label = { Text(stringResource(spec.label), maxLines = 1) },
                    )
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            AnimatedContent(
                targetState = ws.tab,
                transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(120)) },
                label = "tab",
            ) { tab ->
                when (tab) {
                    WsTab.OVERVIEW -> OverviewPane(ws, onChangeLanguage = { vm.requestLanguage(ws.entry) })
                    WsTab.FUNCTIONS -> FunctionsPane(ws)
                    WsTab.CODE -> CodePane(ws, settings)
                    WsTab.STRINGS -> StringsPane(ws)
                    WsTab.HEX -> HexPane(ws, settings)
                }
            }
        }
    }

    if (showGoTo) GoToDialog(ws, onDismiss = { showGoTo = false })
    ws.refs?.let { ReferencesSheet(ws, it) }
}
