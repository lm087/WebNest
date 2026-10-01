package com.mt.webnest

import android.app.NotificationManager
import android.content.Intent
import android.content.pm.ShortcutManager
import android.graphics.Bitmap
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mt.webnest.data.AppDatabase
import com.mt.webnest.data.WebApp
import com.mt.webnest.notification.WebAppNotifications
import com.mt.webnest.shortcut.WebAppShortcutManager
import com.mt.webnest.ui.WebAppActivity
import com.mt.webnest.web.SiteMetadata
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

@RunWith(AndroidJUnit4::class)
class WebNestIntegrationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val db = AppDatabase.get(context)

    @Test fun databasePersistsUpdatesAndNeverReusesDeletedIds() = runBlocking<Unit> {
        val first = db.save(WebApp(name = "First", url = "https://example.com", icon = byteArrayOf(1, 2)))
        try {
            assertArrayEquals(byteArrayOf(1, 2), db.find(first.id)?.icon)
            db.save(first.copy(name = "Edited", url = "https://example.org"))
            db.markOpened(first.id)
            assertEquals("Edited", db.find(first.id)?.name)
            assertNotNull(db.find(first.id)?.lastOpenedAt)
            db.delete(first.id)
            assertNull(db.find(first.id))
            val next = db.save(WebApp(name = "Next", url = "https://example.com"))
            assertTrue(next.id > first.id)
            db.delete(next.id)
        } finally { db.delete(first.id) }
    }

    @Test fun metadataFetchHandlesRedirectEntitiesIconsAndOfflineFallback() = runBlocking<Unit> {
        FixtureServer().use { server ->
            val info = SiteMetadata.fetch(server.url + "/redirect")
            assertEquals("Fixture & WebNest", info.name)
            assertTrue(info.titleFound)
            assertNotNull(info.icon)
            val missing = SiteMetadata.fetch(server.url + "/missing")
            assertEquals("127.0.0.1", missing.name)
            assertFalse(missing.titleFound)
        }
    }

    @Test fun documentNavigationReloadRestorationShortcutsAndNotificationClose() = runBlocking<Unit> {
        if (Build.VERSION.SDK_INT >= 33) {
            instrumentation.uiAutomation.executeShellCommand("pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS").close()
        }
        context.getSharedPreferences("webnest", 0).edit().putBoolean("asked_notifications", true).commit()
        FixtureServer().use { server ->
            val app = db.save(WebApp(name = "Runtime fixture", url = server.url + "/start"))
            try {
                ActivityScenario.launch<WebAppActivity>(WebAppActivity.intent(context, app.id)).use { scenario ->
                    eventually {
                        var ready = false
                        scenario.onActivity { ready = webView(it.window.decorView)?.title == "Fixture & WebNest" }
                        ready
                    }
                    assertTrue(WebAppShortcutManager.sync(context, db.all()))
                    assertTrue(context.getSystemService(ShortcutManager::class.java).dynamicShortcuts.any { it.id == app.id.toString() })
                    scenario.onActivity { activity ->
                        val web = webView(activity.window.decorView)!!
                        assertTrue(web.settings.javaScriptEnabled)
                        assertTrue(web.settings.domStorageEnabled)
                        assertFalse(web.settings.allowFileAccess)
                        web.loadUrl(server.url + "/next")
                    }
                    eventually {
                        var ready = false
                        scenario.onActivity { ready = webView(it.window.decorView)?.url?.endsWith("/next") == true && webView(it.window.decorView)?.canGoBack() == true }
                        ready
                    }
                    scenario.recreate()
                    eventually {
                        var ready = false
                        scenario.onActivity { ready = webView(it.window.decorView)?.url?.endsWith("/next") == true }
                        ready
                    }
                    val manager = context.getSystemService(NotificationManager::class.java)
                    eventually { manager.activeNotifications.any { it.tag == "webapp:${app.id}" } }
                    val notification = manager.activeNotifications.first { it.tag == "webapp:${app.id}" }.notification
                    assertEquals(listOf("Reload", "Close"), notification.actions.map { it.title.toString() })
                    notification.actions.first().actionIntent.send()
                    eventually {
                        var ready = false
                        scenario.onActivity { ready = webView(it.window.decorView)?.url?.endsWith("/next") == true }
                        ready
                    }
                    scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
                    eventually {
                        var ready = false
                        scenario.onActivity { ready = webView(it.window.decorView)?.url?.endsWith("/start") == true }
                        ready
                    }
                    notification.actions.last().actionIntent.send()
                    eventually { manager.activeNotifications.none { it.tag == "webapp:${app.id}" } }
                }
            } finally {
                instrumentation.runOnMainSync { WebAppNotifications.close(context, app.id) }
                WebAppShortcutManager.remove(context, app.id)
                db.delete(app.id)
            }
        }
    }

    private fun webView(view: View): WebView? {
        if (view is WebView) return view
        if (view is ViewGroup) for (i in 0 until view.childCount) webView(view.getChildAt(i))?.let { return it }
        return null
    }
    private fun eventually(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 15000
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(100)
        }
        assertTrue("Timed out waiting for the runtime", condition())
    }

    private class FixtureServer : AutoCloseable {
        private val server = ServerSocket(0)
        private val running = AtomicBoolean(true)
        val url = "http://127.0.0.1:${server.localPort}"
        private val icon = ByteArrayOutputStream().use { out ->
            Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).apply {
                eraseColor(android.graphics.Color.GREEN)
                compress(Bitmap.CompressFormat.PNG, 100, out)
                recycle()
            }
            out.toByteArray()
        }
        private val worker = thread(isDaemon = true) {
            while (running.get()) {
                runCatching {
                    server.accept().use { socket ->
                        socket.soTimeout = 3000
                        val path = socket.getInputStream().bufferedReader().readLine().split(" ").getOrElse(1) { "/" }
                        val response = when (path) {
                            "/redirect" -> "HTTP/1.1 302 Found\r\nLocation: /start\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray()
                            "/icon.png" -> "HTTP/1.1 200 OK\r\nContent-Type: image/png\r\nContent-Length: ${icon.size}\r\nConnection: close\r\n\r\n".toByteArray() + icon
                            "/missing", "/favicon.ico", "/" -> "HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray()
                            else -> {
                                val body = "<html><head><title>Fixture &amp; WebNest</title><link rel='icon' href='/icon.png'></head><body><h1>WebNest fixture</h1><a href='/next'>Next page</a><a href='/popup' target='_blank'>New window</a></body></html>".toByteArray()
                                "HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=UTF-8\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray() + body
                            }
                        }
                        socket.getOutputStream().write(response)
                    }
                }
            }
        }
        override fun close() { running.set(false); server.close(); worker.join(1000) }
    }
}
