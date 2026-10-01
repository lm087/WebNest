package com.mt.webnest

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mt.webnest.data.*
import com.mt.webnest.shortcut.WebAppShortcutManager
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ManagerIntegrationTest {
    @get:Rule val rule = createEmptyComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val db = AppDatabase.get(context)
    private val alphaName = "Manager Alpha"
    private val betaName = "Manager Beta"

    @Test fun blankTapExitsSelectionButRowTapKeepsBatchMode() = manager { _, _, _ ->
        rule.onNodeWithText(alphaName).performTouchInput { longClick() }
        rule.onNodeWithText(betaName).performClick()
        rule.onNodeWithText("2 selected").assertExists()
        rule.onRoot().performTouchInput { click(androidx.compose.ui.geometry.Offset(width / 2f, height - 80f)) }
        rule.onNodeWithText("WebNest").assertExists()
        rule.onNodeWithContentDescription("Exit selection").assertDoesNotExist()
    }

    @Test fun sortReversesAndSurvivesActivityRecreation() = manager { scenario, _, _ ->
        sort("Name")
        assertAbove(alphaName, betaName)
        sort("Name")
        assertAbove(betaName, alphaName)
        scenario.recreate()
        rule.waitUntil { rule.onAllNodesWithText(alphaName).fetchSemanticsNodes().isNotEmpty() }
        assertAbove(betaName, alphaName)
        rule.onNodeWithContentDescription("Settings").performClick()
        rule.onNodeWithText("Sort by").performClick()
        rule.onNodeWithContentDescription("Descending order").assertExists()
    }

    @Test fun rightSwipePinsAndUnpinsWithoutChangingModificationTime() = manager { _, alpha, _ ->
        sort("Name"); sort("Name")
        assertAbove(betaName, alphaName)
        rule.onNodeWithText(alphaName).performTouchInput { swipeRight() }
        rule.waitUntil(10000) { runBlocking { db.find(alpha.id)?.pinned == true } }
        assertAbove(alphaName, betaName)
        rule.waitForIdle()
        rule.onNodeWithText(alphaName).performTouchInput { swipeRight() }
        rule.waitUntil(10000) { runBlocking { db.find(alpha.id)?.pinned == false } }
        assertEquals(alpha.modifiedAt, runBlocking { db.find(alpha.id)!!.modifiedAt })
        assertAbove(betaName, alphaName)
    }

    @Test fun leftSwipeDeletesAndUndoRestoresOriginalDefinitionAndId() = manager { scenario, alpha, _ ->
        repeat(3) { cycle ->
            rule.onNodeWithText(alphaName).performTouchInput { swipeLeft() }
            rule.waitUntil(10000) { runBlocking { db.find(alpha.id) == null } }
            rule.onNodeWithText("Undo").performClick()
            rule.waitUntil(10000) { rule.onAllNodesWithText(alphaName).fetchSemanticsNodes().isNotEmpty()}
            rule.mainClock.advanceTimeBy(1000)
            rule.waitForIdle()
            Thread.sleep(350)
            assertNotNull("Undo immediately deleted the restored row (cycle $cycle)", runBlocking { db.find(alpha.id) })
            rule.onNodeWithText("Undo").assertDoesNotExist()
            if (cycle == 0) {
                scenario.recreate()
                rule.waitUntil(10000) { rule.onAllNodesWithText(alphaName).fetchSemanticsNodes().isNotEmpty() }
            }
        }
        val restored = runBlocking { db.find(alpha.id)!! }
        assertEquals(alpha.name, restored.name)
        assertEquals(alpha.modifiedAt, restored.modifiedAt)
        assertEquals(alpha.createdAt, restored.createdAt)
        assertEquals(alpha.url, restored.url)
    }

    @Test fun longListScrollsToBothEndsAndBottomSwipeUndoRemainsRestored() = manager { scenario, _, _ ->
        val extra = runBlocking { (0 until 24).map { index ->
            db.save(WebApp(name = "Scroll fixture %02d".format(index), url = "https://scroll.test/$index"))
        } }
        try {
            scenario.recreate()
            rule.waitUntil(10000) { rule.onAllNodesWithText(extra.last().name).fetchSemanticsNodes().isNotEmpty() }
            sort("Name")
            val list = rule.onNode(hasScrollToIndexAction())
            fun capture(name: String) {
                if (InstrumentationRegistry.getArguments().getString("captureScreenshots") == "true") {
                    instrumentation.uiAutomation.takeScreenshot()?.let { image ->
                        val directory = context.getExternalFilesDir("screenshots")!!.apply { mkdirs() }
                        java.io.File(directory, "$name.png").outputStream().use { image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                        image.recycle()
                    }
                }
            }
            list.performScrollToIndex(0)
            list.performTouchInput { down(center); moveTo(center.copy(y = height * .85f), delayMillis = 300) }
            capture("long-list-top")
            list.performTouchInput { up() }
            list.performScrollToNode(hasText(extra.last().name))
            list.performTouchInput { down(center); moveTo(center.copy(y = height * .15f), delayMillis = 300) }
            capture("long-list-bottom")
            list.performTouchInput { up() }
            rule.onNodeWithText(extra.last().name).assertIsDisplayed().performTouchInput { swipeLeft() }
            rule.waitUntil(10000) { runBlocking { db.find(extra.last().id) == null } }
            rule.onNodeWithText("Undo").performClick()
            rule.waitUntil(10000) { rule.onAllNodesWithText(extra.last().name).fetchSemanticsNodes().isNotEmpty() }
            rule.mainClock.advanceTimeBy(1000)
            rule.waitForIdle()
            Thread.sleep(350)
            assertNotNull(runBlocking { db.find(extra.last().id) })
            rule.onNodeWithText("Undo").assertDoesNotExist()
        } finally {
            runBlocking { db.deleteAll(extra.map { it.id }) }
            extra.forEach { WebAppShortcutManager.remove(context, it.id) }
        }
    }

    @Test fun longPressBatchDeletionUsesUndoAndRestoresEverySelectedId() = manager { scenario, alpha, beta ->
        rule.onNodeWithText(alphaName).performTouchInput { longClick() }
        rule.onNodeWithText(betaName).performClick()
        scenario.recreate()
        rule.waitUntil { rule.onAllNodesWithText("2 selected").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithContentDescription("Delete selected").performClick()
        rule.waitUntil(10000) { runBlocking { db.find(alpha.id) == null && db.find(beta.id) == null } }
        rule.onNodeWithText("Undo").performClick()
        rule.waitUntil(10000) { runBlocking { db.find(alpha.id) != null && db.find(beta.id) != null } }
        assertEquals(alpha.position, runBlocking { db.find(alpha.id)!!.position })
        assertEquals(beta.position, runBlocking { db.find(beta.id)!!.position })
    }

    @Test fun menuDeleteUsesUndoAndPinMenuToggles() = manager { _, alpha, _ ->
        fun menu() = rule.onNodeWithContentDescription("Options for $alphaName").performClick()
        menu(); rule.onNodeWithText("Pin").performClick()
        rule.waitUntil { runBlocking { db.find(alpha.id)!!.pinned } }
        rule.waitForIdle()
        menu(); rule.onNodeWithText("Unpin").performClick()
        rule.waitUntil { runBlocking { !db.find(alpha.id)!!.pinned } }
        rule.waitForIdle()
        menu(); rule.onNodeWithText("Delete").performClick()
        rule.waitUntil { runBlocking { db.find(alpha.id) == null } }
        rule.onNodeWithText("Undo").performClick()
        rule.waitUntil { runBlocking { db.find(alpha.id) != null } }
    }

    @Test fun selectionCanPinAndUnpinMultipleRecords() = manager { _, alpha, beta ->
        rule.onNodeWithText(alphaName).performTouchInput { longClick() }
        rule.onNodeWithText(betaName).performClick()
        rule.onNodeWithContentDescription("Pin selected").performClick()
        rule.waitUntil { runBlocking { db.find(alpha.id)!!.pinned && db.find(beta.id)!!.pinned } }
        rule.waitUntil { rule.onAllNodesWithText("WebNest").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText(alphaName).performTouchInput { longClick() }
        rule.onNodeWithText(betaName).performClick()
        rule.onNodeWithContentDescription("Unpin selected").performClick()
        rule.waitUntil { runBlocking { !db.find(alpha.id)!!.pinned && !db.find(beta.id)!!.pinned } }
    }

    @Test fun handleDragSelectsCustomOrderAndPersistsAcrossRecreation() = manager { scenario, alpha, beta ->
        sort("Name")
        assertAbove(alphaName, betaName)
        rule.onNodeWithText(alphaName).performTouchInput { longClick() }
        val handle = rule.onNodeWithContentDescription("Move $betaName", useUnmergedTree = true)
        val first = rule.onNodeWithContentDescription("Move $alphaName", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val second = handle.fetchSemanticsNode().boundsInRoot
        handle.performTouchInput {
            swipe(center, center.copy(y = center.y - (second.center.y - first.center.y) - 30), durationMillis = 800)
        }
        rule.waitForIdle()
        rule.waitUntil(10000) { runBlocking { db.find(beta.id)!!.position >= 0 && db.find(beta.id)!!.position < db.find(alpha.id)!!.position } }
        assertAbove(betaName, alphaName)
        scenario.recreate()
        assertAbove(betaName, alphaName)
        rule.onNodeWithContentDescription("Exit selection").performClick()
        rule.onNodeWithContentDescription("Settings").performClick()
        rule.onNodeWithText("Sort by").performClick()
        rule.onNodeWithText("Custom").assertExists()
        rule.onNodeWithContentDescription("Selected").assertExists()
    }

    @Test fun longPressAndDragWithoutLiftingSelectsAndReordersTheWholeRow() = manager { _, alpha, beta ->
        sort("Name")
        assertAbove(alphaName, betaName)
        val first = rule.onNodeWithText(alphaName).fetchSemanticsNode().boundsInRoot
        val second = rule.onNodeWithText(betaName).fetchSemanticsNode().boundsInRoot
        rule.onNodeWithText(betaName).performTouchInput {
            down(center)
            advanceEventTime(700)
            moveTo(center)
            moveTo(center.copy(y = center.y - (second.center.y - first.center.y) - 40), delayMillis = 500)
            up()
        }
        rule.waitForIdle()
        rule.waitUntil(10000) { runBlocking { db.find(beta.id)!!.position >= 0 && db.find(beta.id)!!.position < db.find(alpha.id)!!.position } }
        rule.onNodeWithText("1 selected").assertExists()
        assertAbove(betaName, alphaName)
    }

    @Test fun shareUsesNativeChooserWithOnlyTheUrl() = manager { _, alpha, _ ->
        var shared: Intent? = null
        val monitor = object : Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
                if (intent.action != Intent.ACTION_CHOOSER) return null
                @Suppress("DEPRECATION")
                shared = intent.getParcelableExtra(Intent.EXTRA_INTENT)
                return Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null)
            }
        }
        instrumentation.addMonitor(monitor)
        try {
            rule.onNodeWithContentDescription("Options for $alphaName").performClick()
            rule.onNodeWithText("Open in browser").assertDoesNotExist()
            rule.onNodeWithText("Share").performClick()
            instrumentation.waitForIdleSync()
            assertEquals(Intent.ACTION_SEND, shared?.action)
            assertEquals("text/plain", shared?.type)
            assertEquals(alpha.url, shared?.getStringExtra(Intent.EXTRA_TEXT))
            assertFalse(shared!!.hasExtra(Intent.EXTRA_SUBJECT))
        } finally { instrumentation.removeMonitor(monitor) }
    }

    @Test fun currentSchemaUpgradeRemovesUnusedPreferenceAndPreservesDefinitions() = runBlocking<Unit> {
        SQLiteDatabase.create(null).use { sql ->
            db.onCreate(sql)
            sql.execSQL("ALTER TABLE web_apps ADD COLUMN notifications_enabled INTEGER NOT NULL DEFAULT 1")
            sql.execSQL("INSERT INTO web_apps (id,name,url,created_at,last_opened_at,modified_at,pinned,position,theme_color) VALUES (7,'Existing','https://example.com',100,200,300,1,4,-13408615)")
            sql.execSQL("UPDATE sqlite_sequence SET seq=99 WHERE name='web_apps'")
            db.onUpgrade(sql, 4, 5)
            sql.rawQuery("SELECT * FROM web_apps WHERE id=7", null).use {
                assertTrue(it.moveToFirst()); assertEquals(-1, it.getColumnIndex("notifications_enabled"))
                assertEquals(4L, it.getLong(it.getColumnIndexOrThrow("position")))
                assertEquals(-13408615, it.getInt(it.getColumnIndexOrThrow("theme_color")))
                assertEquals(1, it.getInt(it.getColumnIndexOrThrow("pinned")))
                assertEquals(200L, it.getLong(it.getColumnIndexOrThrow("last_opened_at")))
                assertEquals(300L, it.getLong(it.getColumnIndexOrThrow("modified_at")))
            }
            sql.execSQL("INSERT INTO web_apps (name,url,created_at,modified_at) VALUES ('New','https://example.com',400,400)")
            sql.rawQuery("SELECT id FROM web_apps WHERE name='New'", null).use { assertTrue(it.moveToFirst()); assertEquals(100L,it.getLong(0)) }
        }
        val app = db.save(WebApp(name = "Manager edit fixture", url = "https://example.com", createdAt = 10, lastOpenedAt = 20))
        try {
            db.setPinned(app.id, true)
            db.markOpened(app.id)
            val opened = db.find(app.id)!!.lastOpenedAt
            val saved = db.save(app.copy(name = "Changed"))
            assertTrue(saved.pinned)
            assertEquals(10L, saved.createdAt)
            assertEquals(opened, saved.lastOpenedAt)
            assertTrue(saved.modifiedAt > app.modifiedAt)
        } finally { db.delete(app.id) }
    }

    @Test fun customOrderAndThemeColorSurviveEditsAndUndo() = runBlocking<Unit> {
        val first = db.save(WebApp(name = "Color fixture 1", url = "https://example.com", themeColor = 0xff336699.toInt()))
        val second = db.save(WebApp(name = "Color fixture 2", url = "https://example.com"))
        try {
            db.reorder(listOf(first.id, second.id))
            val edited = db.save(second.copy(name = "Edited", themeColor = 0xffabcdef.toInt()))
            assertEquals(1L, edited.position)
            assertEquals(0xffabcdef.toInt(), edited.themeColor)
            val original = db.find(first.id)!!
            db.deleteAll(listOf(first.id, second.id))
            db.restoreAll(listOf(original, edited))
            assertEquals(original.position, db.find(first.id)!!.position)
            assertEquals(original.themeColor, db.find(first.id)!!.themeColor)
        } finally { db.deleteAll(listOf(first.id, second.id)) }
    }
    private fun manager(block: (ActivityScenario<MainActivity>, WebApp, WebApp) -> Unit) {
        val prefs = context.getSharedPreferences("manager", 0)
        val previousField = prefs.getString("sort_field", null)
        val previousAscending = prefs.getBoolean("sort_ascending", false)
        prefs.edit().putString("sort_field", "NAME").putBoolean("sort_ascending", false).commit()
        val (alpha, beta) = runBlocking {
            db.save(WebApp(name = alphaName, url = "https://alpha.test/path", createdAt = System.currentTimeMillis() + 1000)) to
                db.save(WebApp(name = betaName, url = "https://beta.test/", createdAt = System.currentTimeMillis() + 2000))
        }
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                rule.waitUntil(10000) { rule.onAllNodesWithText(alphaName).fetchSemanticsNodes().isNotEmpty() }
                block(scenario, alpha, beta)
            }
        } finally {
            runBlocking { db.deleteAll(listOf(alpha.id, beta.id)) }
            WebAppShortcutManager.remove(context, alpha.id); WebAppShortcutManager.remove(context, beta.id)
            prefs.edit().putString("sort_field", previousField).putBoolean("sort_ascending", previousAscending).commit()
        }
    }
    private fun sort(label: String) {
        rule.onNodeWithContentDescription("Settings").performClick()
        rule.onNodeWithText("Sort by").performClick()
        rule.onNodeWithText(label).performClick()
        rule.waitForIdle()
    }
    private fun assertAbove(first: String, second: String) {
        rule.waitForIdle()
        rule.waitUntil(10000) {
            runCatching { rule.onNodeWithText(first).fetchSemanticsNode().boundsInRoot.top <
                rule.onNodeWithText(second).fetchSemanticsNode().boundsInRoot.top }.getOrDefault(false)
        }
    }
}
