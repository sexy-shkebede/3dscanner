package com.ghidroid.ui.settings

import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ghidroid.R
import com.ghidroid.data.CodeView
import com.ghidroid.data.ThemeMode
import com.ghidroid.engine.Engine
import com.ghidroid.ui.AppViewModel
import com.ghidroid.ui.components.SectionTitle
import com.ghidroid.ui.home.GHIDRA_VERSION
import com.ghidroid.ui.theme.CodeFont
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(vm: AppViewModel) {
    val s by vm.settings.state.collectAsStateWithLifecycle()
    val store = vm.settings
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    fun changed(rerender: Boolean) {
        if (rerender) vm.workspace?.let { it.clearCache(); it.refresh() }
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.settings), fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = vm::closeSettings) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 24.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item {
                SectionTitle(stringResource(R.string.settings_appearance))
                Text(stringResource(R.string.settings_theme), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(bottom = 8.dp))
                Connected(
                    options = listOf(
                        ThemeMode.SYSTEM to stringResource(R.string.theme_system),
                        ThemeMode.LIGHT to stringResource(R.string.theme_light),
                        ThemeMode.DARK to stringResource(R.string.theme_dark),
                    ),
                    selected = s.themeMode,
                    onSelect = { m -> store.update { it.copy(themeMode = m) } },
                )
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    SwitchRow(stringResource(R.string.settings_dynamic), stringResource(R.string.settings_dynamic_hint), s.dynamicColor) { v ->
                        store.update { it.copy(dynamicColor = v) }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.settings_font_size, s.codeFontSize.roundToInt()), style = MaterialTheme.typography.bodyLarge)
                Slider(
                    value = s.codeFontSize,
                    onValueChange = { v -> store.update { it.copy(codeFontSize = v.roundToInt().toFloat()) } },
                    valueRange = 9f..20f,
                    steps = 10,
                )
                Text(
                    "mov  x0, #0x2a  ; int main(void)",
                    fontFamily = CodeFont,
                    fontSize = s.codeFontSize.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item {
                SectionTitle(stringResource(R.string.settings_decompiler))
                Text(stringResource(R.string.settings_line_width), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(bottom = 8.dp))
                Connected(
                    options = listOf(0 to stringResource(R.string.width_fit), 80 to "80", 100 to "100", 140 to "140"),
                    selected = s.decompilerWidth,
                    onSelect = { w -> store.update { it.copy(decompilerWidth = w) }; changed(true) },
                )
                SwitchRow(stringResource(R.string.settings_readonly), stringResource(R.string.settings_readonly_hint), s.readonlyPropagate) { v ->
                    store.update { it.copy(readonlyPropagate = v) }; changed(true)
                }
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.settings_open_in), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(bottom = 8.dp))
                Connected(
                    options = listOf(CodeView.DECOMPILER to stringResource(R.string.view_decompiler), CodeView.LISTING to stringResource(R.string.view_listing)),
                    selected = s.openFunctionsIn,
                    onSelect = { v -> store.update { it.copy(openFunctionsIn = v) } },
                )
            }
            item {
                SectionTitle(stringResource(R.string.settings_listing))
                SwitchRow(stringResource(R.string.settings_bytes), stringResource(R.string.settings_bytes_hint), s.showBytes) { v ->
                    store.update { it.copy(showBytes = v) }
                }
                SwitchRow(stringResource(R.string.settings_lowercase), stringResource(R.string.settings_lowercase_hint), s.lowercaseMnemonics) { v ->
                    store.update { it.copy(lowercaseMnemonics = v) }
                }
            }
            item {
                SectionTitle(stringResource(R.string.settings_analysis))
                SwitchRow(stringResource(R.string.settings_discover), stringResource(R.string.settings_discover_hint), s.discoverFunctions) { v ->
                    store.update { it.copy(discoverFunctions = v) }
                }
                Text(stringResource(R.string.settings_min_string, s.minStringLength), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 8.dp))
                Slider(
                    value = s.minStringLength.toFloat(),
                    onValueChange = { v -> store.update { it.copy(minStringLength = v.roundToInt()) } },
                    valueRange = 3f..16f,
                    steps = 12,
                )
            }
            item {
                SectionTitle(stringResource(R.string.settings_about))
                Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(18.dp)) {
                        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleMedium)
                        Text(
                            stringResource(R.string.about_text, GHIDRA_VERSION, Engine.languages.size),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(stringResource(R.string.about_license), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun SwitchRow(title: String, hint: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/** A connected button group acting as a single-choice selector. */
@Composable
private fun <T> Connected(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)) {
        options.forEachIndexed { i, (value, label) ->
            ConnectedToggle(selected == value, { onSelect(value) }, i, options.size) { Text(label, maxLines = 1) }
        }
    }
}

@Composable
private fun RowScope.ConnectedToggle(checked: Boolean, onClick: () -> Unit, index: Int, count: Int, content: @Composable RowScope.() -> Unit) {
    ToggleButton(
        checked = checked,
        onCheckedChange = { onClick() },
        modifier = Modifier.weight(1f).semantics { role = Role.RadioButton },
        shapes = when (index) {
            0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
            count - 1 -> ButtonGroupDefaults.connectedTrailingButtonShapes()
            else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
        },
        content = content,
    )
}
