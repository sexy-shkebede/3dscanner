package com.scanner3d.ui.viewer

import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CenterFocusStrong
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Grain
import androidx.compose.material.icons.rounded.GridOn
import androidx.compose.material.icons.rounded.IosShare
import androidx.compose.material.icons.rounded.Landscape
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.rounded.SaveAlt
import androidx.compose.material.icons.rounded.Straighten
import androidx.compose.material.icons.rounded.ViewInAr
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingToolbarDefaults
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.scanner3d.R
import com.scanner3d.ui.components.ValueSlider
import com.scanner3d.ui.components.rememberExpandedSheetState
import com.scanner3d.core.ProgressSink
import com.scanner3d.data.AppGraph
import com.scanner3d.gl.ModelSurfaceView
import com.scanner3d.gl.RenderMode
import com.scanner3d.io.ExportFormat
import com.scanner3d.ui.components.StatusPill
import com.scanner3d.ui.components.formatCount
import com.scanner3d.ui.components.formatMeters
import com.scanner3d.ui.home.RenameDialog
import com.scanner3d.ui.settings.ConnectedChoice
import com.scanner3d.ui.settings.ConnectedToggle
import kotlin.math.roundToInt

@Composable
fun ViewerScreen(scanId: String, onBack: () -> Unit, onReplay: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state = remember(scanId) { ViewerState(context.applicationContext, scanId, AppGraph.store, scope) }
    val progress by state.progress.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val bg = MaterialTheme.colorScheme.surfaceContainerLow.toArgb()

    var showBuild by remember { mutableStateOf(false) }
    var showExport by remember { mutableStateOf(false) }
    var showRename by remember { mutableStateOf(false) }
    var showDelete by remember { mutableStateOf(false) }
    var showReplay by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var pendingSave by remember { mutableStateOf<Pair<ExportFormat, Boolean>?>(null) }

    LaunchedEffect(scanId) { state.load() }
    LaunchedEffect(bg) { state.setBackground(bg) }

    val messages = mapOf(
        R.string.build_empty to stringResource(R.string.build_empty),
        R.string.build_done to stringResource(R.string.build_done),
        R.string.build_oom to stringResource(R.string.build_oom),
        R.string.build_failed to stringResource(R.string.build_failed),
        R.string.export_failed to stringResource(R.string.export_failed),
        R.string.export_saved to stringResource(R.string.export_saved),
    )
    val shareTitle = stringResource(R.string.share)
    LaunchedEffect(state) {
        state.events.collect { e ->
            when (e) {
                is ViewerEvent.Message -> snackbar.showSnackbar(messages[e.text] ?: "")
                is ViewerEvent.Shared -> {
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = e.mime
                        putExtra(Intent.EXTRA_STREAM, e.uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    context.startActivity(Intent.createChooser(send, shareTitle))
                }
            }
        }
    }

    val createDocument = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val p = pendingSave
        pendingSave = null
        if (uri != null && p != null) state.saveTo(uri, p.first, p.second)
    }

    DisposableEffect(Unit) {
        onDispose {
            state.cancelBuild()
            state.detach()
        }
    }

    val meta = state.meta
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(meta?.name ?: "", maxLines = 1, overflow = TextOverflow.Ellipsis)
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                actions = {
                    if (meta?.hasVideo == true) {
                        IconButton(onClick = {
                            val uri = state.videoUri() ?: return@IconButton
                            val intent = Intent(Intent.ACTION_VIEW).apply {
                                setDataAndType(uri, "video/mp4")
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            try {
                                context.startActivity(intent)
                            } catch (e: ActivityNotFoundException) {
                                // No video player available.
                            }
                        }) {
                            Icon(Icons.Rounded.PlayCircle, contentDescription = stringResource(R.string.play_video))
                        }
                    }
                    IconButton(onClick = { state.resetCamera() }) {
                        Icon(Icons.Rounded.CenterFocusStrong, contentDescription = stringResource(R.string.reset_view))
                    }
                    Box {
                        IconButton(onClick = { menu = true }) {
                            Icon(Icons.Rounded.MoreVert, contentDescription = stringResource(R.string.more))
                        }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.rename)) },
                                leadingIcon = { Icon(Icons.Rounded.Edit, null) },
                                onClick = { menu = false; showRename = true },
                            )
                            if (meta?.hasVideo == true) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.rebuild_from_video)) },
                                    leadingIcon = { Icon(Icons.Rounded.Replay, null) },
                                    onClick = { menu = false; showReplay = true },
                                )
                            }
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.delete)) },
                                leadingIcon = { Icon(Icons.Rounded.Delete, null) },
                                onClick = { menu = false; showDelete = true },
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            AndroidView(
                factory = { ctx ->
                    ModelSurfaceView(ctx).also { v ->
                        v.renderer.clearColor = floatArrayOf(
                            ((bg shr 16) and 0xFF) / 255f, ((bg shr 8) and 0xFF) / 255f, (bg and 0xFF) / 255f, 1f,
                        )
                        state.attach(v)
                    }
                },
                onRelease = { state.detach() },
                modifier = Modifier.fillMaxSize(),
            )

            if (state.loading) {
                LoadingIndicator(Modifier.align(Alignment.Center).size(64.dp))
            }

            if (meta != null) {
                Row(
                    Modifier
                        .align(Alignment.TopCenter)
                        .padding(12.dp)
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    StatusPill(formatCount(meta.pointCount), icon = Icons.Rounded.Grain)
                    if (meta.hasModel) StatusPill(
                        stringResource(R.string.triangles_short, formatCount(meta.triangleCount)),
                        icon = Icons.Rounded.ViewInAr,
                    )
                    if (meta.sizeX > 0f) StatusPill(
                        "${formatMeters(meta.sizeX)} × ${formatMeters(meta.sizeY)} × ${formatMeters(meta.sizeZ)}",
                        icon = Icons.Rounded.Straighten,
                    )
                }
            }

            Column(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // Snackbars sit above the toolbar so they never cover its buttons.
                SnackbarHost(snackbar)
                AnimatedVisibility(
                    visible = progress != null,
                    enter = slideInVertically { it } + fadeIn(),
                    exit = slideOutVertically { it } + fadeOut(),
                ) {
                    progress?.let { BuildProgressCard(it, onCancel = { state.cancelBuild() }) }
                }
                HorizontalFloatingToolbar(
                    expanded = true,
                    floatingActionButton = {
                        FloatingToolbarDefaults.VibrantFloatingActionButton(
                            onClick = { if (progress == null) showBuild = true },
                        ) {
                            Icon(Icons.Rounded.AutoAwesome, contentDescription = stringResource(R.string.build_model))
                        }
                    },
                    colors = FloatingToolbarDefaults.vibrantFloatingToolbarColors(),
                    modifier = Modifier.padding(bottom = 16.dp, top = 8.dp),
                ) {
                    ModeButton(state.mode == RenderMode.POINTS, Icons.Rounded.Grain, R.string.mode_points, true) {
                        state.selectMode(RenderMode.POINTS)
                    }
                    ModeButton(state.mode == RenderMode.COLOR, Icons.Rounded.Palette, R.string.mode_color, state.hasMesh) {
                        state.selectMode(RenderMode.COLOR)
                    }
                    ModeButton(state.mode == RenderMode.CLAY, Icons.Rounded.Landscape, R.string.mode_clay, state.hasMesh) {
                        state.selectMode(RenderMode.CLAY)
                    }
                    ModeButton(state.mode == RenderMode.WIRE, Icons.Rounded.GridOn, R.string.mode_wire, state.hasMesh) {
                        state.selectMode(RenderMode.WIRE)
                    }
                    IconButton(onClick = { showExport = true }, enabled = !state.loading) {
                        Icon(Icons.Rounded.IosShare, contentDescription = stringResource(R.string.export))
                    }
                }
            }
        }
    }

    if (showBuild) {
        BuildSheet(
            defaultMinObs = if (meta?.source == "features") 1 else 2,
            hasModel = state.hasMesh,
            onDismiss = { showBuild = false },
            onBuild = { opts -> showBuild = false; state.build(opts) },
        )
    }

    if (showExport) {
        ExportSheet(
            canExportMesh = state.canExportMesh,
            busy = state.exporting,
            onDismiss = { showExport = false },
            onShare = { f, mesh -> state.share(f, mesh) },
            onSave = { f, mesh ->
                pendingSave = f to mesh
                createDocument.launch(state.fileName(f, mesh))
            },
        )
    }

    if (showRename && meta != null) {
        RenameDialog(meta.name, onDismiss = { showRename = false }, onConfirm = { showRename = false; state.rename(it) })
    }

    if (showDelete) {
        AlertDialog(
            onDismissRequest = { showDelete = false },
            icon = { Icon(Icons.Rounded.Delete, null) },
            title = { Text(stringResource(R.string.delete_scan_title)) },
            text = { Text(stringResource(R.string.delete_scan_text, meta?.name ?: "")) },
            confirmButton = {
                TextButton(onClick = { showDelete = false; state.delete(onBack) }) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = { TextButton(onClick = { showDelete = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }

    if (showReplay) {
        AlertDialog(
            onDismissRequest = { showReplay = false },
            icon = { Icon(Icons.Rounded.Movie, null) },
            title = { Text(stringResource(R.string.rebuild_from_video)) },
            text = { Text(stringResource(R.string.rebuild_from_video_text)) },
            confirmButton = {
                TextButton(onClick = { showReplay = false; onReplay() }) { Text(stringResource(R.string.start)) }
            },
            dismissButton = { TextButton(onClick = { showReplay = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun ModeButton(
    selected: Boolean,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: Int,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    androidx.compose.material3.FilledIconToggleButton(
        checked = selected,
        onCheckedChange = { onClick() },
        enabled = enabled,
    ) {
        Icon(icon, contentDescription = stringResource(label))
    }
}

@Composable
private fun BuildProgressCard(p: BuildProgress, onCancel: () -> Unit) {
    val stage = when (p.stage) {
        ProgressSink.Stage.PREPARE -> R.string.stage_prepare
        ProgressSink.Stage.NORMALS -> R.string.stage_normals
        ProgressSink.Stage.FIELD -> R.string.stage_field
        ProgressSink.Stage.MESH -> R.string.stage_mesh
        ProgressSink.Stage.CLEANUP -> R.string.stage_cleanup
        ProgressSink.Stage.COLOR -> R.string.stage_color
    }
    Card(
        modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest),
    ) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                LoadingIndicator(Modifier.size(40.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.building_model), style = MaterialTheme.typography.titleMedium)
                    Text(
                        stringResource(stage) + " · " + (p.overall * 100).roundToInt() + "%",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) }
            }
            Spacer(Modifier.height(14.dp))
            LinearWavyProgressIndicator(progress = { p.overall }, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun BuildSheet(defaultMinObs: Int, hasModel: Boolean, onDismiss: () -> Unit, onBuild: (BuildOptions) -> Unit) {
    var detail by remember { mutableStateOf(Detail.MEDIUM) }
    var smoothing by remember { mutableStateOf(4f) }
    var minObs by remember { mutableStateOf(defaultMinObs.toFloat()) }
    var largest by remember { mutableStateOf(false) }
    var outliers by remember { mutableStateOf(true) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberExpandedSheetState()) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .navigationBarsPadding(),
        ) {
            Text(stringResource(R.string.build_model), style = MaterialTheme.typography.headlineSmallEmphasized)
            Text(
                stringResource(R.string.build_model_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp, bottom = 16.dp),
            )
            Text(stringResource(R.string.detail), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 8.dp))
            ConnectedChoice(
                options = listOf(
                    Detail.LOW to stringResource(R.string.detail_low),
                    Detail.MEDIUM to stringResource(R.string.detail_medium),
                    Detail.HIGH to stringResource(R.string.detail_high),
                ),
                selected = detail,
                onSelect = { detail = it },
            )
            Text(
                stringResource(R.string.smoothing, smoothing.roundToInt()),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 20.dp),
            )
            ValueSlider(value = smoothing, onValueChange = { smoothing = it }, valueRange = 0f..10f, steps = 9)
            Text(
                stringResource(R.string.min_observations, minObs.roundToInt()),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 8.dp),
            )
            ValueSlider(value = minObs, onValueChange = { minObs = it }, valueRange = 1f..6f, steps = 4)
            SwitchRow(stringResource(R.string.remove_outliers), outliers) { outliers = it }
            SwitchRow(stringResource(R.string.keep_largest), largest) { largest = it }
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = {
                    onBuild(BuildOptions(detail, smoothing.roundToInt(), minObs.roundToInt(), largest, outliers))
                },
                modifier = Modifier.fillMaxWidth().height(56.dp),
            ) {
                Icon(Icons.Rounded.AutoAwesome, null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(if (hasModel) R.string.rebuild else R.string.build))
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SwitchRow(text: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun ExportSheet(
    canExportMesh: Boolean,
    busy: Boolean,
    onDismiss: () -> Unit,
    onShare: (ExportFormat, Boolean) -> Unit,
    onSave: (ExportFormat, Boolean) -> Unit,
) {
    var format by remember { mutableStateOf(ExportFormat.GLB) }
    var mesh by remember { mutableStateOf(canExportMesh) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberExpandedSheetState()) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .navigationBarsPadding(),
        ) {
            Text(stringResource(R.string.export), style = MaterialTheme.typography.headlineSmallEmphasized)
            Spacer(Modifier.height(16.dp))
            Text(stringResource(R.string.export_what), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)) {
                ConnectedToggle(checked = mesh, onCheckedChange = { mesh = true }, index = 0, count = 2, enabled = canExportMesh) {
                    Icon(Icons.Rounded.ViewInAr, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.export_model))
                }
                ConnectedToggle(checked = !mesh, onCheckedChange = { mesh = false }, index = 1, count = 2) {
                    Icon(Icons.Rounded.Grain, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.export_points))
                }
            }
            Text(
                stringResource(R.string.export_format),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 20.dp, bottom = 8.dp),
            )
            ConnectedChoice(
                options = ExportFormat.entries.map { it to it.name },
                selected = format,
                onSelect = { format = it },
            )
            Text(
                stringResource(
                    when (format) {
                        ExportFormat.GLB -> R.string.format_glb_hint
                        ExportFormat.PLY -> R.string.format_ply_hint
                        ExportFormat.OBJ -> R.string.format_obj_hint
                    },
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
            Spacer(Modifier.height(24.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(
                    onClick = { onSave(format, mesh) },
                    enabled = !busy,
                    modifier = Modifier.weight(1f).height(56.dp),
                ) {
                    Icon(Icons.Rounded.SaveAlt, null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.save_file))
                }
                Button(
                    onClick = { onShare(format, mesh) },
                    enabled = !busy,
                    modifier = Modifier.weight(1f).height(56.dp),
                ) {
                    if (busy) {
                        LoadingIndicator(Modifier.size(24.dp), color = MaterialTheme.colorScheme.onPrimary)
                    } else {
                        Icon(Icons.Rounded.IosShare, null)
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.share))
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
