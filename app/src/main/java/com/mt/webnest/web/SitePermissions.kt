package com.mt.webnest.web

import android.Manifest
import android.app.AlertDialog
import android.content.Context
import android.content.pm.PackageManager
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import java.net.URI

class SitePermissions(private val activity: ComponentActivity, private val appId: () -> Long) {
    private data class Pending(val origin: String, val resources: List<String>, val source: WebView,
        val request: PermissionRequest?, val reply: (Set<String>) -> Unit)
    private var pending: Pending? = null
    private var dialog: AlertDialog? = null
    private var runtimeInFlight = false
    private val launcher = activity.registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        runtimeInFlight = false
        complete()
    }

    fun media(view: WebView, request: PermissionRequest) {
        val known = request.resources.filter { it == PermissionRequest.RESOURCE_VIDEO_CAPTURE || it == PermissionRequest.RESOURCE_AUDIO_CAPTURE }
        ask(view, request.origin.toString(), known, request) { granted ->
            if (granted.isEmpty()) request.deny() else request.grant(granted.toTypedArray())
        }
    }
    fun location(view: WebView, origin: String, callback: GeolocationPermissions.Callback) {
        ask(view, origin, listOf(LOCATION), null) { granted -> callback.invoke(origin, LOCATION in granted, false) }
    }
    private fun ask(view: WebView, rawOrigin: String, resources: List<String>, request: PermissionRequest?, reply: (Set<String>) -> Unit) {
        val origin = origin(rawOrigin)
        if (origin == null || origin != origin(view.url.orEmpty()) || !trusted(origin) || resources.isEmpty() || runtimeInFlight) {
            reply(emptySet()); return
        }
        cancel()
        val item = Pending(origin, resources, view, request, reply)
        pending = item
        val saved = preferences(activity).getStringSet(key(appId(), origin), emptySet()).orEmpty()
        if (saved.containsAll(resources)) requestRuntime(item)
        else {
            val names = resources.joinToString(" and ") { when (it) {
                PermissionRequest.RESOURCE_VIDEO_CAPTURE -> "camera"
                PermissionRequest.RESOURCE_AUDIO_CAPTURE -> "microphone"
                else -> "location"
            } }
            dialog = AlertDialog.Builder(activity).setTitle("Allow $names?").setMessage(origin)
                .setNegativeButton("Deny") { _, _ -> cancel() }
                .setPositiveButton("Allow") { _, _ -> requestRuntime(item) }
                .setOnCancelListener { cancel() }.show()
        }
    }
    private fun requestRuntime(item: Pending) {
        if (pending !== item) return
        val needed = item.resources.flatMap { androidPermissions(it) }.distinct().filter { !has(it) }
        if (needed.isEmpty()) complete()
        else { runtimeInFlight = true; launcher.launch(needed.toTypedArray()) }
    }
    private fun complete() {
        val item = pending ?: return
        pending = null
        dialog?.dismiss(); dialog = null
        if (origin(item.source.url.orEmpty()) != item.origin || activity.isFinishing || activity.isDestroyed) {
            item.reply(emptySet()); return
        }
        val granted = item.resources.filter { resource ->
            if (resource == LOCATION) has(Manifest.permission.ACCESS_COARSE_LOCATION) || has(Manifest.permission.ACCESS_FINE_LOCATION)
            else androidPermissions(resource).all(::has)
        }.toSet()
        val prefs = preferences(activity)
        val key = key(appId(), item.origin)
        prefs.edit().putStringSet(key, prefs.getStringSet(key, emptySet()).orEmpty() + granted).apply()
        item.reply(granted)
    }
    fun canceled(request: PermissionRequest) {
        if (pending?.request === request) { pending = null; dialog?.dismiss(); dialog = null }
    }
    fun cancelFor(view: WebView) { if (pending?.source === view) cancel() }
    fun cancel() {
        val old = pending; pending = null
        dialog?.dismiss(); dialog = null
        old?.reply?.invoke(emptySet())
    }
    private fun has(permission: String) = ContextCompat.checkSelfPermission(activity, permission) == PackageManager.PERMISSION_GRANTED
    private fun androidPermissions(resource: String) = when (resource) {
        PermissionRequest.RESOURCE_VIDEO_CAPTURE -> listOf(Manifest.permission.CAMERA)
        PermissionRequest.RESOURCE_AUDIO_CAPTURE -> listOf(Manifest.permission.RECORD_AUDIO)
        else -> listOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION)
    }
    companion object {
        const val LOCATION = "location"
        private fun preferences(context: Context) = context.getSharedPreferences("site_permissions", Context.MODE_PRIVATE)
        private fun key(id: Long, origin: String) = "$id|$origin"
        fun clear(context: Context, id: Long) {
            val prefs = preferences(context)
            prefs.edit().apply { prefs.all.keys.filter { it.startsWith("$id|") }.forEach(::remove) }.apply()
        }
        fun origin(url: String): String? = runCatching {
            val uri = URI(url)
            val scheme = uri.scheme?.lowercase() ?: return null
            val host = uri.host?.lowercase() ?: return null
            if (scheme !in setOf("http", "https") || uri.userInfo != null) return null
            val port = uri.port.takeUnless { it == -1 || it == if (scheme == "https") 443 else 80 }
            "$scheme://$host" + (port?.let { ":$it" } ?: "")
        }.getOrNull()
        private fun trusted(origin: String) = origin.startsWith("https://") || URI(origin).host in setOf("localhost", "127.0.0.1", "[::1]")
    }
}
