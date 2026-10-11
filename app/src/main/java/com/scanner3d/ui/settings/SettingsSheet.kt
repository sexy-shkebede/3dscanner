package com.scanner3d.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.scanner3d.R
import com.scanner3d.ui.components.ValueSlider
import com.scanner3d.ui.components.rememberExpandedSheetState
import com.scanner3d.data.AppGraph
import com.scanner3d.data.DepthSource
import kotlin.math.roundToInt

@Composable
fun SettingsSheet(onDismiss: () -> Unit) {
    val repo = AppGraph.settings
    val s by repo.settings.collectAsStateWithLifecycle()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberExpandedSheetState()) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .navigationBarsPadding(),
        ) {
            Text(stringResource(R.string.settings), style = MaterialTheme.typography.headlineSmallEmphasized)
            Spacer(Modifier.height(20.dp))

            SectionTitle(stringResource(R.string.setting_source))
            ConnectedChoice(
                options = listOf(
                    DepthSource.RAW to stringResource(R.string.source_raw),
                    DepthSource.SMOOTHED to stringResource(R.string.source_smoothed),
                    DepthSource.FEATURES to stringResource(R.string.source_features),
                ),
                selected = s.depthSource,
                onSelect = { v -> repo.update { it.copy(depthSource = v) } },
            )
            Hint(stringResource(R.string.setting_source_hint))

            SectionTitle(stringResource(R.string.setting_voxel, s.voxelSizeMm))
            ValueSlider(
                value = s.voxelSizeMm.toFloat(),
                onValueChange = { v -> repo.update { it.copy(voxelSizeMm = v.roundToInt()) } },
                valueRange = 3f..30f,
                steps = 26,
            )
            Hint(stringResource(R.string.setting_voxel_hint))

            SectionTitle(stringResource(R.string.setting_max_depth, s.maxDepthM))
            ValueSlider(
                value = s.maxDepthM,
                onValueChange = { v -> repo.update { it.copy(maxDepthM = (v * 10).roundToInt() / 10f) } },
                valueRange = 0.5f..5f,
            )

            SectionTitle(stringResource(R.string.setting_confidence, (s.minConfidence * 100).roundToInt()))
            ValueSlider(
                value = s.minConfidence,
                onValueChange = { v -> repo.update { it.copy(minConfidence = (v * 20).roundToInt() / 20f) } },
                valueRange = 0f..0.95f,
                enabled = s.depthSource == DepthSource.RAW,
            )

            SectionTitle(stringResource(R.string.setting_max_points))
            ConnectedChoice(
                options = listOf(500_000 to "0.5M", 1_000_000 to "1M", 1_500_000 to "1.5M", 2_000_000 to "2M"),
                selected = s.maxPoints,
                onSelect = { v -> repo.update { it.copy(maxPoints = v) } },
            )
            Hint(stringResource(R.string.setting_max_points_hint))

            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 16.dp)
                    .semantics { role = Role.Switch },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.setting_record_video), style = MaterialTheme.typography.titleMedium)
                    Text(
                        stringResource(R.string.setting_record_video_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = s.recordVideo, onCheckedChange = { v -> repo.update { it.copy(recordVideo = v) } })
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(top = 16.dp, bottom = 8.dp),
    )
}

@Composable
private fun Hint(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 6.dp),
    )
}

/** Material 3 Expressive connected button group for single choice. */
@Composable
fun <T> ConnectedChoice(
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Row(
        modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
    ) {
        options.forEachIndexed { index, (value, label) ->
            ConnectedToggle(
                checked = value == selected,
                onCheckedChange = { onSelect(value) },
                index = index,
                count = options.size,
                enabled = enabled,
            ) {
                Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
fun RowScope.ConnectedToggle(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    index: Int,
    count: Int,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    ToggleButton(
        checked = checked,
        onCheckedChange = onCheckedChange,
        enabled = enabled,
        modifier = Modifier.weight(1f).semantics { role = Role.RadioButton },
        shapes = when (index) {
            0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
            count - 1 -> ButtonGroupDefaults.connectedTrailingButtonShapes()
            else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
        },
        content = content,
    )
}
