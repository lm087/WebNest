package com.mt.webnest

import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mt.webnest.web.SiteData
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SiteDataIntegrationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test
    fun clearingOneSiteRemovesItsCookiesAndStorageButKeepsAnotherSite() {
        var supported = false
        instrumentation.runOnMainSync { supported = SiteData.supported() }
        assertTrue("Test emulator must support DELETE_BROWSING_DATA", supported)
        val cookies = CookieManager.getInstance()
        val cookieReady = CountDownLatch(2)
        instrumentation.runOnMainSync {
            cookies.setCookie(
                "https://alpha.webnest-fixture.example/private",
                "target=1; Domain=webnest-fixture.example; Path=/private; Secure; HttpOnly",
            ) {
                cookieReady.countDown()
            }
            cookies.setCookie(
                "https://other.webnest-fixture.test/",
                "other=1; Path=/; Secure; HttpOnly",
            ) {
                cookieReady.countDown()
            }
        }
        assertTrue(cookieReady.await(5, TimeUnit.SECONDS))
        assertTrue(
            cookies.getCookie("https://alpha.webnest-fixture.example/private").contains("target=1")
        )
        val target = storage("https://alpha.webnest-fixture.example/")
        val other = storage("https://other.webnest-fixture.test/")
        try {
            assertEquals("\"present\"", readStorage(target))
            assertEquals("\"present\"", readStorage(other))
            runBlocking {
                withContext(Dispatchers.Main) {
                    SiteData.clear("https://alpha.webnest-fixture.example/")
                }
            }
            assertTrue(
                cookies.getCookie("https://alpha.webnest-fixture.example/private").isNullOrBlank()
            )
            assertTrue(cookies.getCookie("https://other.webnest-fixture.test/").contains("other=1"))
            assertEquals("null", readStorage(target))
            assertEquals("\"present\"", readStorage(other))
        } finally {
            instrumentation.runOnMainSync {
                target.destroy()
                other.destroy()
            }
            runBlocking {
                withContext(Dispatchers.Main) {
                    SiteData.clear("https://other.webnest-fixture.test/")
                }
            }
        }
    }

    private fun storage(url: String): WebView {
        val ready = CountDownLatch(1)
        val result = AtomicReference<WebView>()
        instrumentation.runOnMainSync {
            val web = WebView(context)
            result.set(web)
            web.settings.javaScriptEnabled = true
            web.settings.domStorageEnabled = true
            web.webViewClient =
                object : WebViewClient() {
                    override fun onPageFinished(view: WebView, finished: String) {
                        view.evaluateJavascript("localStorage.setItem('fixture', 'present')") {
                            ready.countDown()
                        }
                    }
                }
            web.loadDataWithBaseURL(
                url,
                "<html><title>Storage fixture</title></html>",
                "text/html",
                "UTF-8",
                url,
            )
        }
        assertTrue(ready.await(10, TimeUnit.SECONDS))
        return result.get()
    }

    private fun readStorage(web: WebView): String {
        val ready = CountDownLatch(1)
        val result = AtomicReference<String>()
        instrumentation.runOnMainSync {
            web.evaluateJavascript("localStorage.getItem('fixture')") {
                result.set(it)
                ready.countDown()
            }
        }
        assertTrue(ready.await(5, TimeUnit.SECONDS))
        return result.get()
    }
}
