package com.mt.webnest.web

import android.webkit.WebStorage
import androidx.webkit.WebStorageCompat
import androidx.webkit.WebViewFeature
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

object SiteData {
    fun supported(): Boolean =
        WebViewFeature.isFeatureSupported(WebViewFeature.DELETE_BROWSING_DATA)

    suspend fun clear(url: String, onScheduled: (String) -> Unit = {}): Unit =
        suspendCancellableCoroutine { continuation ->
            check(supported()) { "Update Android System WebView to clear site data." }
            require(WebUrls.normalize(url) != null)
            val site =
                WebStorageCompat.deleteBrowsingDataForSite(WebStorage.getInstance(), url) {
                    if (continuation.isActive) continuation.resume(Unit)
                }
            onScheduled(site)
        }
}
