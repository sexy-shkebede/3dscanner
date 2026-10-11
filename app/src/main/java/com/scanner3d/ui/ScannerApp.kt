package com.scanner3d.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import com.google.ar.core.ArCoreApk
import com.scanner3d.R
import com.scanner3d.ar.ArScanController
import com.scanner3d.ui.capture.CaptureScreen
import com.scanner3d.ui.home.HomeScreen
import com.scanner3d.ui.viewer.ViewerScreen

/** Routes are encoded as strings so the back stack survives process recreation. */
private object Routes {
    const val HOME = "home"
    const val CAPTURE = "capture"
    fun replay(id: String) = "replay:$id"
    fun viewer(id: String) = "viewer:$id"
}

@Composable
fun ScannerApp() {
    val context = LocalContext.current
    var stack by rememberSaveable { mutableStateOf(listOf(Routes.HOME)) }
    var pendingRoute by remember { mutableStateOf<String?>(null) }
    var dialog by remember { mutableStateOf<Int?>(null) }

    fun push(route: String) { stack = stack + route }
    fun pop() { if (stack.size > 1) stack = stack.dropLast(1) }
    fun replaceTop(route: String) { stack = stack.dropLast(1) + route }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val route = pendingRoute
        pendingRoute = null
        if (granted && route != null) push(route) else if (!granted) dialog = R.string.permission_camera_denied
    }

    fun openCamera(route: String) {
        val availability = runCatching { ArScanController.availability(context) }.getOrNull()
        if (availability == ArCoreApk.Availability.UNSUPPORTED_DEVICE_NOT_CAPABLE) {
            dialog = R.string.error_device_unsupported
            return
        }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            push(route)
        } else {
            pendingRoute = route
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    BackHandler(enabled = stack.size > 1) { pop() }

    val current = stack.last()
    val motion = MaterialTheme.motionScheme
    AnimatedContent(
        targetState = current,
        transitionSpec = {
            val spatial = motion.defaultSpatialSpec<Float>()
            val effects = motion.defaultEffectsSpec<Float>()
            (fadeIn(effects) + scaleIn(spatial, initialScale = 0.94f)) togetherWith
                (fadeOut(effects) + scaleOut(spatial, targetScale = 1.04f))
        },
        label = "nav",
    ) { route ->
        when {
            route == Routes.HOME -> HomeScreen(
                onNewScan = { openCamera(Routes.CAPTURE) },
                onOpenScan = { push(Routes.viewer(it)) },
            )
            route == Routes.CAPTURE -> CaptureScreen(
                replayScanId = null,
                onClose = { pop() },
                onSaved = { replaceTop(Routes.viewer(it)) },
            )
            route.startsWith("replay:") -> {
                val id = route.removePrefix("replay:")
                CaptureScreen(
                    replayScanId = id,
                    onClose = { pop() },
                    onSaved = { pop() },
                )
            }
            route.startsWith("viewer:") -> {
                val id = route.removePrefix("viewer:")
                ViewerScreen(
                    scanId = id,
                    onBack = { pop() },
                    onReplay = { openCamera(Routes.replay(id)) },
                )
            }
        }
    }

    dialog?.let { msg ->
        AlertDialog(
            onDismissRequest = { dialog = null },
            confirmButton = { TextButton(onClick = { dialog = null }) { Text(stringResource(android.R.string.ok)) } },
            title = { Text(stringResource(R.string.app_name)) },
            text = { Text(stringResource(msg)) },
        )
    }
}
