package com.scanner3d.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.scanner3d.core.PointCloud
import com.scanner3d.data.AppGraph
import com.scanner3d.data.ScanMeta
import com.scanner3d.ui.theme.ScannerTheme
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Renders the real Compose screens on the JVM to catch UI runtime errors. */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h891dp-xxhdpi")
class UiSmokeTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    @Before
    fun setUp() {
        AppGraph.init(rule.activity)
        AppGraph.store.list().forEach { AppGraph.store.delete(it.id) }
    }

    @Test
    fun emptyHomeAndSettingsSheet() {
        rule.setContent { ScannerTheme { ScannerApp() } }
        rule.onNodeWithText("No scans yet").assertIsDisplayed()
        rule.onNodeWithContentDescription("New scan").assertIsDisplayed()
        rule.onNodeWithContentDescription("Settings").performClick()
        rule.waitUntilAtLeastOneExists(hasText("Depth source"), 5_000)
        rule.onNodeWithText("Record video").assertExists()
    }

    @Test
    fun viewerBuildsModelAndOpensExport() {
        val store = AppGraph.store
        val id = "test_scan"
        val cloud = sphere(12_000, 0.15f)
        store.saveCloud(id, cloud)
        store.saveThumbnail(id, cloud.positions, cloud.colors, cloud.normals)
        store.save(ScanMeta(id = id, name = "Test scan", createdAt = 1L, pointCount = cloud.size, sizeX = 0.3f, sizeY = 0.3f, sizeZ = 0.3f))

        rule.setContent { ScannerTheme { ScannerApp() } }
        rule.waitUntilAtLeastOneExists(hasText("Test scan"), 5_000)
        rule.onNodeWithText("Test scan").performClick()

        rule.waitUntilAtLeastOneExists(hasContentDescription("Build model"), 5_000)
        rule.onNodeWithContentDescription("Build model").performClick()
        rule.waitUntilAtLeastOneExists(hasText("Build"), 5_000)
        rule.onNodeWithText("Build").performScrollTo().performClick()

        rule.waitUntil(60_000) { store.load(id)?.hasModel == true }
        rule.waitUntil(10_000) {
            runCatching { rule.onNodeWithContentDescription("Clay").assertIsEnabled() }.isSuccess
        }
        rule.onNodeWithContentDescription("Clay").performClick()
        rule.onNodeWithContentDescription("Wireframe").performClick()
        assertTrue(store.loadMesh(id)!!.triangleCount > 500)

        rule.onNodeWithContentDescription("Export").performClick()
        rule.waitUntilAtLeastOneExists(hasText("Save file"), 5_000)
        rule.onNodeWithText("PLY").performClick()
        rule.onNodeWithText("Share").performScrollTo().performClick()
    }

    private fun sphere(n: Int, r: Float): PointCloud {
        val p = FloatArray(n * 3); val nr = FloatArray(n * 3)
        val golden = Math.PI * (3 - sqrt(5.0))
        for (i in 0 until n) {
            val y = 1 - (i + 0.5) / n * 2
            val rad = sqrt(1 - y * y)
            val x = cos(golden * i) * rad
            val z = sin(golden * i) * rad
            p[3 * i] = (x * r).toFloat(); p[3 * i + 1] = (y * r).toFloat(); p[3 * i + 2] = (z * r).toFloat()
            nr[3 * i] = x.toFloat(); nr[3 * i + 1] = y.toFloat(); nr[3 * i + 2] = z.toFloat()
        }
        return PointCloud(p, nr, ByteArray(n * 3) { 120 }, IntArray(n) { 3 }, 0.006f)
    }
}
