package com.ghidroid.ui

import android.graphics.Bitmap
import android.os.Looper
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.lifecycle.ViewModelProvider
import com.ghidroid.MainActivity
import com.ghidroid.analysis.AnalysisStage
import com.ghidroid.data.LibraryEntry
import com.ghidroid.data.ThemeMode
import com.ghidroid.loader.LoaderTest
import com.ghidroid.ui.workspace.CodeMode
import com.ghidroid.ui.workspace.DecompState
import com.ghidroid.ui.workspace.WsTab
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Drives the real app (Ghidra engine included, via the host build of libghidroid) and saves
 * screenshots of the main screens to build/screenshots.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w412dp-h900dp-xxhdpi")
class ScreenshotTest {
    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private val out = File("build/screenshots").apply { mkdirs() }

    private fun shot(name: String) {
        rule.waitForIdle()
        val bmp = rule.onRoot().captureToImage().asAndroidBitmap()
        File(out, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** Pumps the main looper (coroutines resume there) until [cond] holds. */
    private fun await(timeoutMs: Long, what: String, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!cond()) {
            if (System.currentTimeMillis() > end) throw AssertionError("timed out waiting for $what")
            shadowOf(Looper.getMainLooper()).idle()
            rule.waitForIdle()
            Thread.sleep(20)
        }
        rule.waitForIdle()
    }

    private fun vm(): AppViewModel = ViewModelProvider(rule.activity)[AppViewModel::class.java]

    private fun addToLibrary(vm: AppViewModel, fixture: String, name: String): LibraryEntry {
        val src = LoaderTest.fixture(fixture)
        val id = name.replace('.', '_')
        val dir = File(rule.activity.filesDir, "library/$id").apply { mkdirs() }
        src.copyTo(File(dir, "blob"), overwrite = true)
        val entry = LibraryEntry(id, name, src.length(), System.currentTimeMillis(), System.currentTimeMillis())
        runBlocking { vm.library.save(entry) }
        return entry
    }

    @Test
    fun screens() {
        val loaded = runCatching { com.ghidroid.engine.NativeBridge }.isSuccess
        assumeTrue(loaded)
        val vm = vm()
        await(180_000, "engine") { vm.engine is EngineState.Ready }
        addToLibrary(vm, "sample-x64.dll", "sample-x64.dll")
        addToLibrary(vm, "libsample-armv7.so.bin", "libnative-armv7.so")
        val entry = addToLibrary(vm, "libsample-arm64.so.bin", "libnative-arm64.so")
        rule.waitForIdle()
        shot("01-home")

        rule.runOnUiThread { vm.open(entry) }
        await(120_000, "analysis") { vm.workspace?.analyzer?.state?.value?.stage == AnalysisStage.DONE }
        val ws = vm.workspace!!
        shot("02-overview")

        rule.runOnUiThread { ws.tab = WsTab.FUNCTIONS }
        shot("03-functions")

        val main = ws.program.functions.value.first { it.name == "main" }
        rule.runOnUiThread { ws.goTo(main.address, CodeMode.DECOMPILER) }
        await(60_000, "decompiler") { ws.decomp is DecompState.Ready }
        shot("04-decompiler")

        rule.runOnUiThread { ws.switchMode(CodeMode.LISTING) }
        await(30_000, "listing") { ws.listing.rows.isNotEmpty() && !ws.listing.loading }
        shot("05-listing")

        val strlen = ws.program.functions.value.first { it.name == "strlen" }
        rule.runOnUiThread { ws.findReferences(strlen.address, strlen.displayName) }
        await(30_000, "references") { ws.refs?.done == true }
        shot("06-references")
        rule.runOnUiThread { ws.cancelReferences() }

        rule.runOnUiThread { ws.tab = WsTab.STRINGS }
        shot("06-strings")

        rule.runOnUiThread { ws.showInHex(main.address, 16) }
        shot("07-hex")

        rule.runOnUiThread {
            vm.settings.update { it.copy(themeMode = ThemeMode.DARK, showBytes = true) }
            ws.goTo(main.address, CodeMode.DECOMPILER)
        }
        await(60_000, "decompiler") { ws.decomp is DecompState.Ready }
        shot("08-decompiler-dark")
        rule.runOnUiThread { ws.switchMode(CodeMode.LISTING) }
        await(30_000, "listing") { ws.listing.rows.isNotEmpty() && !ws.listing.loading }
        shot("09-listing-dark")

        rule.runOnUiThread { vm.openSettings() }
        shot("10-settings-dark")
    }
}
