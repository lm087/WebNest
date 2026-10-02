package com.mt.webnest.web

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.MediaStore
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.io.File

class FileUploads(private val activity: ComponentActivity) {
    private var callback: ValueCallback<Array<Uri>>? = null
    private var source: WebView? = null
    private var capture: Uri? = null
    private var captureFile: File? = null
    private var captureType: String? = null
    private var inFlight = false
    private val permission =
        activity.registerForActivityResult(ActivityResultContracts.RequestPermission()) { allowed ->
            if (callback == null) {
                inFlight = false
                return@registerForActivityResult
            }
            if (allowed) launchCapture()
            else {
                inFlight = false
                finish(null)
            }
        }
    private val picker =
        activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            result ->
            inFlight = false
            val uris =
                if (result.resultCode != Activity.RESULT_OK) null
                else {
                    if (capture != null && captureFile?.length()?.let { it > 0 } == true)
                        arrayOf(capture!!)
                    else
                        result.data?.let { data ->
                            val selected =
                                data.clipData?.let { clip ->
                                    (0 until minOf(clip.itemCount, 50)).map {
                                        clip.getItemAt(it).uri
                                    }
                                } ?: listOfNotNull(data.data)
                            selected
                                .filter {
                                    it.scheme == "content" &&
                                        it.authority != "${activity.packageName}.files"
                                }
                                .distinct()
                                .takeIf { it.isNotEmpty() }
                                ?.toTypedArray()
                        }
                }
            finish(uris)
        }

    fun choose(
        view: WebView,
        result: ValueCallback<Array<Uri>>,
        params: WebChromeClient.FileChooserParams,
    ): Boolean {
        if (inFlight) {
            result.onReceiveValue(null)
            return true
        }
        cancel()
        callback = result
        source = view
        val types =
            params.acceptTypes
                .flatMap { it.split(',') }
                .mapNotNull { raw ->
                    val value = raw.trim().lowercase()
                    when {
                        Regex("[a-z0-9.+-]+/(?:[a-z0-9.+-]+|\\*)").matches(value) -> value
                        value.startsWith('.') ->
                            android.webkit.MimeTypeMap.getSingleton()
                                .getMimeTypeFromExtension(value.drop(1))
                        else -> null
                    }
                }
                .distinct()
        captureType =
            types.singleOrNull()?.takeIf {
                params.isCaptureEnabled && (it.startsWith("image/") || it.startsWith("video/"))
            }
        if (captureType != null) {
            inFlight = true
            if (
                ContextCompat.checkSelfPermission(activity, Manifest.permission.CAMERA) ==
                    PackageManager.PERMISSION_GRANTED
            )
                launchCapture()
            else permission.launch(Manifest.permission.CAMERA)
        } else {
            val intent =
                Intent(Intent.ACTION_OPEN_DOCUMENT)
                    .addCategory(Intent.CATEGORY_OPENABLE)
                    .setType(types.singleOrNull() ?: "*/*")
                    .putExtra(
                        Intent.EXTRA_ALLOW_MULTIPLE,
                        params.mode == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE,
                    )
            if (types.size > 1) intent.putExtra(Intent.EXTRA_MIME_TYPES, types.toTypedArray())
            try {
                inFlight = true
                picker.launch(intent)
            } catch (_: Exception) {
                inFlight = false
                finish(null)
            }
        }
        return true
    }

    private fun launchCapture() {
        try {
            val video = captureType?.startsWith("video/") == true
            val directory = File(activity.cacheDir, "captures").apply { mkdirs() }
            directory
                .listFiles()
                ?.filter { System.currentTimeMillis() - it.lastModified() > 24 * 60 * 60 * 1000 }
                ?.forEach { it.delete() }
            captureFile = File.createTempFile("capture-", if (video) ".mp4" else ".jpg", directory)
            capture =
                FileProvider.getUriForFile(activity, "${activity.packageName}.files", captureFile!!)
            val intent =
                Intent(
                        if (video) MediaStore.ACTION_VIDEO_CAPTURE
                        else MediaStore.ACTION_IMAGE_CAPTURE
                    )
                    .putExtra(MediaStore.EXTRA_OUTPUT, capture)
                    .addFlags(
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or
                            Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    )
            intent.clipData = ClipData.newRawUri("Capture", capture)
            picker.launch(intent)
        } catch (_: Exception) {
            inFlight = false
            finish(null)
        }
    }

    private fun finish(value: Array<Uri>?) {
        val old = callback
        callback = null
        source = null
        if (value == null) captureFile?.delete()
        capture = null
        captureFile = null
        captureType = null
        old?.onReceiveValue(value)
    }

    fun cancelFor(view: WebView) {
        if (source === view) cancel()
    }

    fun cancel() {
        finish(null)
    }
}
