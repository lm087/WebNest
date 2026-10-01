package com.mt.webnest.web

import android.Manifest
import android.app.DownloadManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.webkit.CookieManager
import android.webkit.URLUtil
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat

class WebDownloads(private val activity: ComponentActivity) {
    private data class Download(val url: String, val userAgent: String, val disposition: String?, val mime: String?, val referer: String?)
    private var pending: Download? = null
    private val permission = activity.registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val item = pending; pending = null
        if (granted && item != null) enqueue(item)
    }
    fun download(url: String, userAgent: String, disposition: String?, mime: String?, referer: String?) {
        val normalized = WebUrls.normalize(url)
        if (normalized == null || !url.startsWith("http", true)) {
            Toast.makeText(activity, "This download type is not supported", Toast.LENGTH_SHORT).show(); return
        }
        val item = Download(normalized, userAgent, disposition, mime, referer)
        if (Build.VERSION.SDK_INT <= 28 && ContextCompat.checkSelfPermission(activity, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            if (pending != null) return
            pending = item; permission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else enqueue(item)
    }
    private fun enqueue(item: Download) {
        try {
            val name = fileName(URLUtil.guessFileName(item.url, item.disposition, item.mime))
            val request = DownloadManager.Request(Uri.parse(item.url))
                .setTitle(name).setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "${System.currentTimeMillis()}-$name")
            item.mime?.takeIf { it.contains('/') }?.let(request::setMimeType)
            if (WebSettingsPolicy.validUserAgent(item.userAgent)) request.addRequestHeader("User-Agent", item.userAgent)
            CookieManager.getInstance().getCookie(item.url)?.let { request.addRequestHeader("Cookie", it) }
            item.referer?.let(SitePermissions::origin)?.let { request.addRequestHeader("Referer", "$it/") }
            activity.getSystemService(DownloadManager::class.java).enqueue(request)
        } catch (_: Exception) { Toast.makeText(activity, "Couldn't start download", Toast.LENGTH_SHORT).show() }
    }
    companion object {
        fun fileName(value: String): String = value.substringAfterLast('/').substringAfterLast('\\')
            .replace(Regex("[\\p{Cntrl}:*?\"<>|]"), "_").trim().trim('.').take(120).ifBlank { "download" }
    }
}
