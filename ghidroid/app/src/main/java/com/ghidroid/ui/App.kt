package com.ghidroid.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.ghidroid.R
import com.ghidroid.analysis.OpenOptions
import com.ghidroid.ui.home.HomeScreen
import com.ghidroid.ui.settings.SettingsScreen
import com.ghidroid.ui.workspace.LanguagePickerSheet
import com.ghidroid.ui.workspace.WorkspaceScreen

@Composable
fun AppRoot(vm: AppViewModel) {
    AnimatedContent(
        targetState = vm.screen,
        transitionSpec = {
            (fadeIn(tween(220)) + scaleIn(initialScale = 0.96f, animationSpec = tween(260))) togetherWith fadeOut(tween(160))
        },
        label = "screen",
    ) { screen ->
        when (screen) {
            Screen.HOME -> HomeScreen(vm)
            Screen.WORKSPACE -> {
                val ws = vm.workspace
                if (ws != null) WorkspaceScreen(vm, ws) else HomeScreen(vm)
            }
            Screen.SETTINGS -> SettingsScreen(vm)
        }
    }

    BackHandler(enabled = vm.screen == Screen.SETTINGS) { vm.closeSettings() }

    vm.opening?.let { OpeningDialog(it, onCancel = vm::cancelOpen) }

    vm.error?.let { message ->
        AlertDialog(
            onDismissRequest = { vm.error = null },
            icon = { Icon(Icons.Rounded.ErrorOutline, contentDescription = null) },
            title = { Text(stringResource(R.string.error_title)) },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = { vm.error = null }) { Text(stringResource(R.string.ok)) } },
        )
    }

    vm.languageRequest?.let { req ->
        LanguagePickerSheet(
            binary = req.binary,
            initialLanguage = req.entry.languageId,
            initialRaw = req.entry.forceRaw,
            initialBase = req.entry.rawBase,
            onDismiss = { vm.languageRequest = null },
            onConfirm = { lang, compiler, raw, base ->
                vm.languageRequest = null
                vm.open(req.entry, OpenOptions(lang, compiler, raw, base))
            },
        )
    }
}

@Composable
private fun OpeningDialog(p: OpenProgress, onCancel: () -> Unit) {
    Dialog(onDismissRequest = {}, properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false)) {
        Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            Column(Modifier.padding(24.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                ContainedLoadingIndicator(Modifier.size(72.dp))
                Spacer(Modifier.height(20.dp))
                Text(
                    p.name,
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    stringResource(
                        when (p.stage) {
                            OpenStage.COPYING -> R.string.stage_copying
                            OpenStage.PARSING -> R.string.stage_parsing
                            OpenStage.STARTING -> R.string.stage_starting
                        },
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                p.progress?.let {
                    Spacer(Modifier.height(16.dp))
                    LinearWavyProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth())
                }
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) }
                    Spacer(Modifier.width(4.dp))
                }
            }
        }
    }
}
