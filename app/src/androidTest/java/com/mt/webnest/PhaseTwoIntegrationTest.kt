package com.mt.webnest

import android.Manifest
import android.app.Activity
import android.app.DownloadManager
import android.app.Instrumentation
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebView
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mt.webnest.data.AppDatabase
import com.mt.webnest.data.WebApp
import com.mt.webnest.notification.WebAppNotifications
import com.mt.webnest.ui.WebAppActivity
import com.mt.webnest.web.SiteIcons
import com.mt.webnest.web.SitePermissions
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONArray
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class PhaseTwoIntegrationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val db = AppDatabase.get(context)

    @Test fun settingsPersistAndNotificationControlsAreAlwaysAvailable() = fixture(desktop = true) { scenario, record, _ ->
        scenario.onActivity {
            val web = visibleWeb(it.window.decorView)!!
            assertFalse(web.settings.userAgentString.contains("Android"))
            assertTrue(CookieManager.getInstance().acceptThirdPartyCookies(web))
        }
        val viewport = AtomicReference<Int>()
        val measured = CountDownLatch(1)
        scenario.onActivity { visibleWeb(it.window.decorView)!!.evaluateJavascript("window.innerWidth") { value -> viewport.set(value.toInt()); measured.countDown() } }
        assertTrue(measured.await(5, TimeUnit.SECONDS))
        assertTrue("Desktop viewport", viewport.get() >= 980)
        assertTrue(runBlocking { db.find(record.id)!!.desktopMode })
        val manager = context.getSystemService(android.app.NotificationManager::class.java)
        assertTrue(manager.activeNotifications.any { it.tag == "webapp:${record.id}" })
    }

    @Test fun loginPopupKeepsOpenerAndClosesBackToOriginalPage() = fixture { scenario, _, _ ->
        tapElement(scenario, "popup")
        await { title(scenario) == "Sign in" }
        tapElement(scenario, "finish")
        await { title(scenario) == "Signed in" }
        scenario.onActivity { assertEquals(1, allWebs(it.window.decorView).size) }
    }

    @Test fun filePickerDeliversReadableFileToWebsite() = fixture { scenario, _, _ ->
        val result = Intent().setData(Uri.parse("content://com.mt.webnest.test.upload/file"))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val monitor = instrumentation.addMonitor(IntentFilter(Intent.ACTION_OPEN_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); addDataType("*/*") }, Instrumentation.ActivityResult(Activity.RESULT_OK, result), true)
        try {
            tapElement(scenario, "upload")
            await { title(scenario) == "Uploaded" }
            assertEquals(1, monitor.hits)
        } finally { instrumentation.removeMonitor(monitor) }
    }

    @Test fun captureInputWritesToScopedUriAndReturnsFile() = fixture { scenario, _, _ ->
        instrumentation.uiAutomation.executeShellCommand("pm grant ${context.packageName} ${Manifest.permission.CAMERA}").use { it.fileDescriptor.syncIfPossible() }
        val monitor = object : Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
                if (intent.action != android.provider.MediaStore.ACTION_IMAGE_CAPTURE) return null
                @Suppress("DEPRECATION")
                val uri = intent.getParcelableExtra<Uri>(android.provider.MediaStore.EXTRA_OUTPUT)!!
                assertEquals("${context.packageName}.files", uri.authority)
                context.contentResolver.openOutputStream(uri)!!.use { out ->
                    val image = android.graphics.Bitmap.createBitmap(8, 8, android.graphics.Bitmap.Config.ARGB_8888)
                    image.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, out); image.recycle()
                }
                return Instrumentation.ActivityResult(Activity.RESULT_OK, null)
            }
        }
        instrumentation.addMonitor(monitor)
        try {
            tapElement(scenario, "capture")
            await { title(scenario)?.startsWith("Captured:") == true }
            assertTrue(title(scenario)!!.substringAfter(':').toInt() > 0)
        } finally { instrumentation.removeMonitor(monitor) }
    }

    @Test fun websitePermissionsAreRememberedAndCrossOriginIsRejected() = fixture { scenario, record, server ->
        instrumentation.uiAutomation.executeShellCommand("pm grant ${context.packageName} ${Manifest.permission.CAMERA}").use { it.fileDescriptor.syncIfPossible() }
        instrumentation.uiAutomation.executeShellCommand("pm grant ${context.packageName} ${Manifest.permission.RECORD_AUDIO}").use { it.fileDescriptor.syncIfPossible() }
        val granted = AtomicReference<List<String>?>()
        val request = object : PermissionRequest() {
            override fun getOrigin() = Uri.parse(server.url)
            override fun getResources() = arrayOf(RESOURCE_VIDEO_CAPTURE, RESOURCE_AUDIO_CAPTURE, "unknown.resource")
            override fun grant(resources: Array<out String>) { granted.set(resources.toList()) }
            override fun deny() { granted.set(emptyList()) }
        }
        scenario.onActivity { visibleWeb(it.window.decorView)!!.webChromeClient!!.onPermissionRequest(request) }
        onView(withText("Allow")).inRoot(androidx.test.espresso.matcher.RootMatchers.isDialog()).perform(click())
        await { granted.get() != null }
        assertEquals(listOf(PermissionRequest.RESOURCE_VIDEO_CAPTURE, PermissionRequest.RESOURCE_AUDIO_CAPTURE), granted.get())
        granted.set(null)
        scenario.onActivity { visibleWeb(it.window.decorView)!!.webChromeClient!!.onPermissionRequest(request) }
        assertEquals(listOf(PermissionRequest.RESOURCE_VIDEO_CAPTURE, PermissionRequest.RESOURCE_AUDIO_CAPTURE), granted.get())
        val denied = AtomicReference<Boolean>(false)
        scenario.onActivity { activity ->
            visibleWeb(activity.window.decorView)!!.webChromeClient!!.onPermissionRequest(object : PermissionRequest() {
                override fun getOrigin() = Uri.parse("https://other.example")
                override fun getResources() = arrayOf(RESOURCE_VIDEO_CAPTURE)
                override fun grant(resources: Array<out String>) { fail("Cross-origin grant") }
                override fun deny() { denied.set(true) }
            })
        }
        assertTrue(denied.get())
        SitePermissions.clear(context, record.id)
    }

    @Test fun authenticatedDownloadContinuesInSystemDownloadManager() = fixture { scenario, _, server ->
        val manager = context.getSystemService(DownloadManager::class.java)
        val before = downloadIds(manager)
        scenario.onActivity {
            CookieManager.getInstance().setCookie(server.url, "session=fixture")
            CookieManager.getInstance().flush()
            visibleWeb(it.window.decorView)!!.loadUrl(server.url + "/download")
        }
        var newIds = emptySet<Long>()
        try {
            await {
                newIds = downloadIds(manager) - before
                newIds.any { id -> manager.query(DownloadManager.Query().setFilterById(id)).use {
                    it.moveToFirst() && it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)) == DownloadManager.STATUS_SUCCESSFUL
                } }
            }
            val id = newIds.first()
            val text = manager.openDownloadedFile(id).use { descriptor -> java.io.FileInputStream(descriptor.fileDescriptor).readBytes().toString(Charsets.UTF_8) }
            assertEquals(server.download.toString(Charsets.UTF_8), text)
            assertTrue(server.requests.any { it.contains("GET /download") && it.contains("session=fixture") })
        } finally { (downloadIds(manager) - before).forEach { manager.remove(it) } }
    }

    @Test fun malformedIcoIsIgnoredAndPngIconIsNormalized() {
        assertNull(SiteIcons.normalize(byteArrayOf(0, 0, 1, 0, 127, 127)))
        val bitmap = android.graphics.Bitmap.createBitmap(400, 200, android.graphics.Bitmap.Config.ARGB_8888)
        val bytes = java.io.ByteArrayOutputStream().use { out -> bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out); out.toByteArray() }
        val normalized = SiteIcons.normalize(bytes)!!
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeByteArray(normalized, 0, normalized.size, bounds)
        assertTrue(bounds.outWidth <= 256)
        bitmap.recycle()
    }

    private fun fixture(desktop: Boolean = false, block: (ActivityScenario<WebAppActivity>, WebApp, PhaseTwoServer) -> Unit) {
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            instrumentation.uiAutomation.executeShellCommand("pm grant ${context.packageName} ${Manifest.permission.POST_NOTIFICATIONS}").use { it.fileDescriptor.syncIfPossible() }
        }
        PhaseTwoServer().use { server ->
            val record = runBlocking { db.save(WebApp(name = "Phase two test", url = server.url, desktopMode = desktop, thirdPartyCookies = true)) }
            try {
                ActivityScenario.launch<WebAppActivity>(WebAppActivity.intent(context, record.id)).use { scenario ->
                    await {
                        var ready = false
                        scenario.onActivity { val view = visibleWeb(it.window.decorView); ready = view?.title == "Phase two" && view.progress == 100 && view.hasWindowFocus() }
                        ready
                    }
                    block(scenario, record, server)
                }
            } finally {
                instrumentation.runOnMainSync { WebAppNotifications.close(context, record.id) }
                SitePermissions.clear(context, record.id)
                runBlocking { db.delete(record.id) }
            }
        }
    }
    private fun title(scenario: ActivityScenario<WebAppActivity>): String? {
        var title: String? = null
        scenario.onActivity { title = visibleWeb(it.window.decorView)?.title }
        return title
    }
    private fun allWebs(view: View): List<WebView> = when (view) {
        is WebView -> listOf(view)
        is ViewGroup -> (0 until view.childCount).flatMap { allWebs(view.getChildAt(it)) }
        else -> emptyList()
    }
    private fun visibleWeb(view: View) = allWebs(view).lastOrNull { it.visibility == View.VISIBLE }
    private fun tapElement(scenario: ActivityScenario<WebAppActivity>, id: String) {
        val latch = CountDownLatch(1)
        var point = floatArrayOf()
        scenario.onActivity { activity ->
            val view = visibleWeb(activity.window.decorView)!!
            view.evaluateJavascript("(function(){let r=document.getElementById('$id').getBoundingClientRect(); return [r.x+r.width/2,r.y+r.height/2,window.innerWidth];})()") { json ->
                val data = JSONArray(json)
                val factor = view.width.toFloat() / data.getDouble(2).toFloat()
                point = floatArrayOf(data.getDouble(0).toFloat() * factor, data.getDouble(1).toFloat() * factor)
                latch.countDown()
            }
        }
        assertTrue(latch.await(5, TimeUnit.SECONDS))
        scenario.onActivity { activity ->
            val location = IntArray(2)
            visibleWeb(activity.window.decorView)!!.getLocationOnScreen(location)
            point[0] += location[0]; point[1] += location[1]
        }
        val now = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, point[0], point[1], 0).apply { source = android.view.InputDevice.SOURCE_TOUCHSCREEN }
        instrumentation.sendPointerSync(down)
        Thread.sleep(80)
        val up = MotionEvent.obtain(now, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, point[0], point[1], 0).apply { source = android.view.InputDevice.SOURCE_TOUCHSCREEN }
        instrumentation.sendPointerSync(up)
        down.recycle(); up.recycle()

    }
    private fun downloadIds(manager: DownloadManager) = manager.query(DownloadManager.Query()).use { cursor ->
        buildSet { while (cursor.moveToNext()) add(cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_ID))) }
    }
    private fun await(condition: () -> Boolean) {
        val until = SystemClock.uptimeMillis() + 15000
        while (SystemClock.uptimeMillis() < until) { if (condition()) return; Thread.sleep(100) }
        assertTrue("Timed out", condition())
    }
    private fun java.io.FileDescriptor.syncIfPossible() { runCatching { java.io.FileInputStream(this).use { it.readBytes() } } }
}
