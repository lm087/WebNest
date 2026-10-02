package com.mt.webnest

import android.app.Activity
import android.app.Instrumentation
import android.app.UiModeManager
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mt.webnest.data.AppDatabase
import com.mt.webnest.data.WebApp
import com.mt.webnest.shortcut.WebAppShortcutManager
import com.mt.webnest.ui.AddWebAppActivity
import com.mt.webnest.ui.IconCropActivity
import com.mt.webnest.web.SiteMetadata
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EditorIntegrationTest {
    @get:Rule val rule = createEmptyComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val db = AppDatabase.get(context)

    @Test
    fun pwaManifestAndGzipHtmlHaveWorkingFallbacks() =
        runBlocking<Unit> {
            MetadataFixtureServer().use { server ->
                val pwa = SiteMetadata.fetch(server.url + "/pwa")
                assertEquals(0xff336699.toInt(), pwa.themeColor)
                assertEquals("PWA short", pwa.name)
                assertEquals(Color.BLUE, bitmap(pwa.icon!!).getPixel(0, 0))
                val fallback = SiteMetadata.fetch(server.url + "/fallback")
                assertEquals(0xffabcdef.toInt(), fallback.themeColor)
                assertEquals("Fallback & title", fallback.name)
                val image = bitmap(fallback.icon!!)
                assertEquals(Color.RED, image.getPixel(0, 0))
                assertEquals(Color.GREEN, image.getPixel(1, 0))
                assertEquals(Color.BLUE, image.getPixel(0, 1))
                assertEquals(0, Color.alpha(image.getPixel(1, 1)))
                image.recycle()
            }
        }

    @Test
    fun manyBrokenAdvertisedIconsStillFallBackToRootFavicon() =
        runBlocking<Unit> {
            MetadataFixtureServer().use { server ->
                val result = SiteMetadata.fetch(server.url + "/many-icons")
                assertEquals("Many icons", result.name)
                assertTrue(server.paths.contains("/favicon.ico"))
                val image = bitmap(result.icon!!)
                try {
                    assertEquals(Color.RED, image.getPixel(0, 0))
                } finally {
                    image.recycle()
                }
            }
        }

    @Test
    fun svgAndInlineIconsDecodeAndBrokenSvgFallsBackToFavicon() =
        runBlocking<Unit> {
            MetadataFixtureServer().use { server ->
                for (path in listOf("/svg", "/inline-svg")) {
                    val image = bitmap(SiteMetadata.fetch(server.url + path).icon!!)
                    try {
                        assertEquals(Color.BLUE, image.getPixel(image.width / 2, image.height / 2))
                    } finally {
                        image.recycle()
                    }
                }
                val result = SiteMetadata.fetch(server.url + "/broken-svg")
                assertEquals(0xffabcdef.toInt(), result.themeColor)
                val image = bitmap(result.icon!!)
                try {
                    assertEquals(Color.RED, image.getPixel(0, 0))
                } finally {
                    image.recycle()
                }
            }
        }

    @Test
    fun homepageIconFallbackPreservesInternalPageTitleAndTheme() =
        runBlocking<Unit> {
            MetadataFixtureServer(rootIconsOnly = true).use { server ->
                val result = SiteMetadata.fetch(server.url + "/article")
                assertEquals("Article title", result.name)
                assertEquals(0xff123456.toInt(), result.themeColor)
                assertTrue(server.paths.contains("/"))
                val image = bitmap(result.icon!!)
                try {
                    assertEquals(Color.BLUE, image.getPixel(128, 128))
                } finally {
                    image.recycle()
                }
            }
        }

    @Test
    fun fetchKeepsExistingNameAndIcon() = editor { scenario, record, server ->
        rule.onNodeWithContentDescription("Fetch site details").performClick()
        fetched(server)
        rule.onNodeWithText("Personal").assertExists()
        rule.onNodeWithText("Save").performClick()
        saved(scenario)
        val result = runBlocking { db.find(record.id)!! }
        assertEquals("Personal", result.name)
        assertArrayEquals(record.icon, result.icon)
    }

    @Test
    fun fetchRefillsOnlyFieldsThatWereCleared() = editor { scenario, record, server ->
        rule.onNode(hasSetTextAction() and hasText("Name")).performTextClearance()
        rule.onNodeWithContentDescription("Change icon").performClick()
        rule.onNodeWithText("Remove icon").performClick()
        rule.onNodeWithContentDescription("Fetch site details").performClick()
        fetched(server)
        rule.onNodeWithText("PWA short").assertExists()
        rule.onNodeWithText("Save").performClick()
        saved(scenario)
        val result = runBlocking { db.find(record.id)!! }
        assertEquals("PWA short", result.name)
        assertEquals(Color.BLUE, bitmap(result.icon!!).getPixel(0, 0))
    }

    @Test
    fun photoChoiceUsesPinchAndDragForSquareCrop() = editor { scenario, record, _ ->
        val result =
            Intent()
                .setData(Uri.parse("content://com.mt.webnest.test.upload/image"))
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val monitor =
            object : Instrumentation.ActivityMonitor() {
                override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? =
                    if (intent.action == Intent.ACTION_GET_CONTENT)
                        Instrumentation.ActivityResult(Activity.RESULT_OK, result)
                    else null
            }
        instrumentation.addMonitor(monitor)
        try {
            rule.onNodeWithContentDescription("Change icon").performClick()
            rule.onNodeWithText("Choose photo").performClick()
            rule.waitUntil(10000) {
                rule
                    .onAllNodesWithContentDescription("Square icon preview")
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }
            rule.waitUntil(10000) {
                rule
                    .onAllNodes(hasText("Use icon") and isEnabled())
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }
            if (InstrumentationRegistry.getArguments().getString("captureScreenshots") == "true") {
                instrumentation.waitForIdleSync()
                Thread.sleep(1000)
                instrumentation.uiAutomation.takeScreenshot()?.let { screenshot ->
                    val directory = context.getExternalFilesDir("screenshots")!!.apply { mkdirs() }
                    java.io.File(directory, "crop.png").outputStream().use {
                        screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)
                    }
                    screenshot.recycle()
                }
            }
            rule.onNodeWithContentDescription("Square icon preview").performTouchInput {
                pinch(
                    center - Offset(width * .15f, 0f),
                    center - Offset(width * .35f, 0f),
                    center + Offset(width * .15f, 0f),
                    center + Offset(width * .35f, 0f),
                )
            }
            repeat(3) {
                rule.onNodeWithContentDescription("Square icon preview").performTouchInput {
                    swipeLeft()
                }
            }
            rule.onNodeWithText("Use icon").performClick()
            rule.waitUntil(10000) {
                rule.onAllNodesWithText("Edit Web App").fetchSemanticsNodes().isNotEmpty()
            }
            rule.onNodeWithText("Save").performClick()
            saved(scenario)
            val image = bitmap(runBlocking { db.find(record.id)!!.icon!! })
            assertEquals(image.width, image.height)
            assertEquals(Color.BLUE, image.getPixel(image.width / 2, image.height / 2))
            assertEquals(Color.BLUE, image.getPixel(image.width / 2, 0))
            image.recycle()
            assertEquals(1, monitor.hits)
        } finally {
            instrumentation.removeMonitor(monitor)
        }
    }

    @Test
    fun translucentCropBackgroundStaysStableDuringPanAndZoomInBothThemes() {
        val originalMode = context.getSystemService(UiModeManager::class.java).nightMode
        fun night(mode: String) {
            instrumentation.uiAutomation.executeShellCommand("cmd uimode night $mode").use {
                java.io.FileInputStream(it.fileDescriptor).use { input -> input.readBytes() }
            }
            instrumentation.waitForIdleSync()
        }
        try {
            for (mode in listOf("yes", "no")) {
                night(mode)
                val intent =
                    Intent(context, IconCropActivity::class.java)
                        .setData(Uri.parse("content://com.mt.webnest.test.upload/image-alpha"))
                ActivityScenario.launch<IconCropActivity>(intent).use {
                    rule.waitUntil(10000) {
                        rule
                            .onAllNodes(hasText("Use icon") and isEnabled())
                            .fetchSemanticsNodes()
                            .isNotEmpty()
                    }
                    val node = rule.onNodeWithContentDescription("Square icon preview")
                    fun sample(): androidx.compose.ui.graphics.Color {
                        val image = node.captureToImage()
                        return image.toPixelMap()[image.width / 4, image.height / 4]
                    }
                    val before = sample()
                    node.performTouchInput {
                        pinch(
                            center - Offset(width * .15f, 0f),
                            center - Offset(width * .35f, 0f),
                            center + Offset(width * .15f, 0f),
                            center + Offset(width * .35f, 0f),
                        )
                    }
                    repeat(3) { node.performTouchInput { swipeLeft() } }
                    assertEquals(
                        "Transparent crop background changed in $mode theme",
                        before,
                        sample(),
                    )
                    assertEquals(1f, before.alpha)
                    if (
                        InstrumentationRegistry.getArguments().getString("captureScreenshots") ==
                            "true"
                    ) {
                        instrumentation.uiAutomation.takeScreenshot()?.let { image ->
                            val directory =
                                context.getExternalFilesDir("screenshots")!!.apply { mkdirs() }
                            java.io.File(directory, "crop-alpha-$mode.png").outputStream().use {
                                image.compress(Bitmap.CompressFormat.PNG, 100, it)
                            }
                            image.recycle()
                        }
                    }
                }
            }
        } finally {
            night(
                if (originalMode == UiModeManager.MODE_NIGHT_YES) "yes"
                else if (originalMode == UiModeManager.MODE_NIGHT_NO) "no" else "auto"
            )
        }
    }

    private fun editor(
        block: (ActivityScenario<AddWebAppActivity>, WebApp, MetadataFixtureServer) -> Unit
    ) {
        MetadataFixtureServer().use { server ->
            val icon =
                ByteArrayOutputStream().use { out ->
                    Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888).apply {
                        eraseColor(Color.MAGENTA)
                        compress(Bitmap.CompressFormat.PNG, 100, out)
                        recycle()
                    }
                    out.toByteArray()
                }
            val record = runBlocking {
                db.save(WebApp(name = "Personal", url = server.url + "/pwa", icon = icon))
            }
            try {
                ActivityScenario.launch<AddWebAppActivity>(
                        Intent(context, AddWebAppActivity::class.java)
                            .putExtra("edit_id", record.id)
                    )
                    .use { scenario ->
                        rule.waitUntil(10000) {
                            rule.onAllNodesWithText("Personal").fetchSemanticsNodes().isNotEmpty()
                        }
                        rule.onNodeWithText("Add to home screen").assertDoesNotExist()
                        block(scenario, record, server)
                    }
            } finally {
                runBlocking { db.delete(record.id) }
                WebAppShortcutManager.remove(context, record.id)
            }
        }
    }

    private fun fetched(server: MetadataFixtureServer) {
        rule.waitUntil(15000) {
            server.paths.contains("/assets/app.webmanifest") &&
                rule
                    .onAllNodesWithContentDescription("Fetching site details")
                    .fetchSemanticsNodes()
                    .isEmpty()
        }
    }

    private fun saved(scenario: ActivityScenario<*>) {
        rule.waitUntil(10000) { scenario.state == Lifecycle.State.DESTROYED }
    }

    private fun bitmap(bytes: ByteArray) = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)!!
}
