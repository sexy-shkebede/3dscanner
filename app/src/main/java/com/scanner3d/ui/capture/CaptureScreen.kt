package com.scanner3d.ui.capture

import android.opengl.GLSurfaceView
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AutoFixHigh
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.FiberManualRecord
import androidx.compose.material.icons.rounded.Grain
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.FilledIconToggleButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FloatingToolbarDefaults
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.graphics.shapes.Morph
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.scanner3d.R
import com.scanner3d.ar.ArScanController
import com.scanner3d.ar.CapturePhase
import com.scanner3d.ar.TrackingHint
import com.scanner3d.data.AppGraph
import com.scanner3d.data.DepthSource
import com.scanner3d.ui.components.MorphShape
import com.scanner3d.ui.components.StatusPill
import com.scanner3d.ui.components.formatCount
import com.scanner3d.ui.components.formatDuration
import kotlinx.coroutines.launch

@Composable
fun CaptureScreen(
    replayScanId: String?,
    onClose: () -> Unit,
    onSaved: (String) -> Unit,
) {
    val activity = LocalActivity.current ?: return
    val lifecycleOwner = LocalLifecycleOwner.current
    val controller = remember {
        ArScanController(activity, AppGraph.settings.settings.value, AppGraph.store, replayScanId)
    }
    val state by controller.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var showCamera by remember { mutableStateOf(true) }
    var highlight by remember { mutableStateOf(false) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> controller.onResume()
                Lifecycle.Event.ON_PAUSE -> controller.onPause()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            controller.onPause()
            controller.destroy()
        }
    }

    fun stop() {
        scope.launch {
            val id = controller.stopAndSave()
            if (id != null) onSaved(id)
        }
    }

    // Replays stop automatically when the video ends.
    LaunchedEffect(state.replayFinished) {
        if (state.replayFinished && state.phase == CapturePhase.RECORDING) stop()
    }

    BackHandler(enabled = state.phase == CapturePhase.RECORDING || state.phase == CapturePhase.SAVING) {
        if (state.phase == CapturePhase.RECORDING) stop()
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { ctx ->
                GLSurfaceView(ctx).apply {
                    preserveEGLContextOnPause = true
                    setEGLContextClientVersion(3)
                    setEGLConfigChooser(8, 8, 8, 8, 16, 0)
                    setRenderer(controller)
                    renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
                    setWillNotDraw(false)
                    controller.attach(this)
                }
            },
            modifier = Modifier.fillMaxSize(),
        )

        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            TopBar(
                state = state,
                replay = replayScanId != null,
                onClose = {
                    if (state.phase == CapturePhase.RECORDING) stop() else onClose()
                },
            )
            Spacer(Modifier.height(12.dp))
            HintBanner(state.hint, state.tracking, state.mapFull, Modifier.align(Alignment.CenterHorizontally))
            Spacer(Modifier.weight(1f))

            if (state.phase == CapturePhase.READY && replayScanId == null) {
                Text(
                    stringResource(R.string.capture_ready_hint),
                    style = MaterialTheme.typography.bodyLarge,
                    color = Color.White,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 12.dp),
                )
            }

            if (replayScanId == null) {
                HorizontalFloatingToolbar(
                    expanded = true,
                    colors = FloatingToolbarDefaults.standardFloatingToolbarColors(),
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                ) {
                    FilledIconToggleButton(
                        checked = showCamera,
                        onCheckedChange = { showCamera = it; controller.showCamera = it },
                    ) {
                        Icon(Icons.Rounded.CameraAlt, contentDescription = stringResource(R.string.toggle_camera))
                    }
                    FilledIconToggleButton(
                        checked = highlight,
                        onCheckedChange = { highlight = it; controller.highlight = it },
                    ) {
                        Icon(Icons.Rounded.AutoFixHigh, contentDescription = stringResource(R.string.toggle_highlight))
                    }
                    IconButton(
                        onClick = { controller.resetMap() },
                        enabled = state.phase == CapturePhase.RECORDING,
                    ) {
                        Icon(Icons.Rounded.RestartAlt, contentDescription = stringResource(R.string.reset_map))
                    }
                }
                Spacer(Modifier.height(16.dp))
                RecordButton(
                    recording = state.phase == CapturePhase.RECORDING,
                    enabled = state.phase == CapturePhase.READY || state.phase == CapturePhase.RECORDING,
                    onClick = {
                        if (state.phase == CapturePhase.RECORDING) stop() else controller.startRecording()
                    },
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                )
                Spacer(Modifier.height(24.dp))
            } else {
                ReplayPanel(
                    running = state.phase == CapturePhase.RECORDING,
                    onStop = { stop() },
                    modifier = Modifier.align(Alignment.CenterHorizontally).padding(bottom = 32.dp),
                )
            }
        }

        AnimatedVisibility(
            visible = state.phase == CapturePhase.SAVING,
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            Box(
                Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    ContainedLoadingIndicator(Modifier.size(96.dp))
                    Spacer(Modifier.height(20.dp))
                    Text(stringResource(R.string.saving_scan), style = MaterialTheme.typography.titleLarge, color = Color.White)
                }
            }
        }

        if (state.phase == CapturePhase.ERROR) {
            ErrorCard(
                message = state.errorMessage ?: stringResource(R.string.error_unknown),
                onClose = onClose,
                modifier = Modifier.align(Alignment.Center).padding(24.dp),
            )
        }
    }
}

@Composable
private fun TopBar(state: com.scanner3d.ar.CaptureUiState, replay: Boolean, onClose: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilledTonalIconButton(onClick = onClose, shapes = IconButtonDefaults.shapes()) {
            Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.back))
        }
        Spacer(Modifier.weight(1f))
        val recording = state.phase == CapturePhase.RECORDING
        AnimatedVisibility(visible = recording, enter = scaleIn() + fadeIn(), exit = scaleOut() + fadeOut()) {
            val blink = rememberInfiniteTransition(label = "rec")
            val alpha by blink.animateFloat(1f, 0.25f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "a")
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.errorContainer) {
                Row(
                    Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        if (state.videoRecording) Icons.Rounded.Videocam else Icons.Rounded.FiberManualRecord,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(16.dp).graphicsLayer { this.alpha = alpha },
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        formatDuration(state.elapsedMs),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            }
        }
        StatusPill(
            text = if (recording || replay) formatCount(state.pointCount) else sourceLabel(state.source),
            icon = Icons.Rounded.Grain,
        )
    }
}

@Composable
private fun sourceLabel(source: DepthSource): String = when (source) {
    DepthSource.RAW -> stringResource(R.string.source_raw)
    DepthSource.SMOOTHED -> stringResource(R.string.source_smoothed)
    DepthSource.FEATURES -> stringResource(R.string.source_features)
}

@Composable
private fun HintBanner(hint: TrackingHint, tracking: Boolean, mapFull: Boolean, modifier: Modifier = Modifier) {
    val text = when {
        mapFull -> stringResource(R.string.hint_map_full)
        hint == TrackingHint.NONE && tracking -> null
        hint == TrackingHint.INITIALIZING -> stringResource(R.string.hint_initializing)
        hint == TrackingHint.LOW_LIGHT -> stringResource(R.string.hint_low_light)
        hint == TrackingHint.TOO_FAST -> stringResource(R.string.hint_too_fast)
        hint == TrackingHint.FEW_FEATURES -> stringResource(R.string.hint_few_features)
        hint == TrackingHint.CAMERA_UNAVAILABLE -> stringResource(R.string.hint_camera_unavailable)
        hint == TrackingHint.LOST -> stringResource(R.string.hint_lost)
        else -> null
    }
    AnimatedVisibility(
        visible = text != null,
        enter = slideInVertically { -it } + fadeIn(),
        exit = slideOutVertically { -it } + fadeOut(),
        modifier = modifier,
    ) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.95f),
            contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
            modifier = Modifier.padding(horizontal = 24.dp),
        ) {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                if (hint == TrackingHint.INITIALIZING && !mapFull) {
                    LoadingIndicator(Modifier.size(28.dp))
                } else {
                    Icon(Icons.Rounded.Warning, contentDescription = null, modifier = Modifier.size(20.dp))
                }
                Spacer(Modifier.width(10.dp))
                Text(text ?: "", style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

/** Large record button that morphs from a rotating cookie into a rounded square while recording. */
@Composable
private fun RecordButton(recording: Boolean, enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val morph = remember { Morph(MaterialShapes.Cookie9Sided.normalized(), MaterialShapes.Square.normalized()) }
    val progress by animateFloatAsState(
        targetValue = if (recording) 1f else 0f,
        animationSpec = MaterialTheme.motionScheme.defaultSpatialSpec(),
        label = "morph",
    )
    val spin = rememberInfiniteTransition(label = "spin")
    val angle by spin.animateFloat(0f, 360f, infiniteRepeatable(tween(12000, easing = LinearEasing)), label = "angle")
    val container = if (recording) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    val content = if (recording) MaterialTheme.colorScheme.onError else MaterialTheme.colorScheme.onPrimary
    val label = stringResource(if (recording) R.string.stop_scan else R.string.start_scan)

    Box(modifier.size(96.dp), contentAlignment = Alignment.Center) {
        Surface(
            onClick = onClick,
            enabled = enabled,
            shape = MorphShape(morph, progress),
            color = if (enabled) container else MaterialTheme.colorScheme.surfaceContainerHighest,
            contentColor = content,
            shadowElevation = 6.dp,
            modifier = Modifier
                .size(96.dp)
                .graphicsLayer { rotationZ = angle * (1f - progress) }
                .semantics { contentDescription = label },
        ) {}
        Icon(
            if (recording) Icons.Rounded.Stop else Icons.Rounded.Videocam,
            contentDescription = null,
            tint = if (enabled) content else MaterialTheme.colorScheme.outline,
            modifier = Modifier.size(40.dp),
        )
    }
}

@Composable
private fun ReplayPanel(running: Boolean, onStop: () -> Unit, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.padding(horizontal = 24.dp),
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            if (running) LoadingIndicator(Modifier.size(40.dp)) else Icon(Icons.Rounded.Movie, null)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f, fill = false)) {
                Text(stringResource(R.string.replay_title), style = MaterialTheme.typography.titleMedium)
                Text(
                    stringResource(R.string.replay_text),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(12.dp))
            Button(onClick = onStop, enabled = running) { Text(stringResource(R.string.finish)) }
        }
    }
}

@Composable
private fun ErrorCard(message: String, onClose: () -> Unit, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Rounded.Warning, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(40.dp))
            Spacer(Modifier.height(12.dp))
            Text(message, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
            Spacer(Modifier.height(16.dp))
            Button(onClick = onClose) {
                Icon(Icons.Rounded.Close, null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.close))
            }
        }
    }
}
