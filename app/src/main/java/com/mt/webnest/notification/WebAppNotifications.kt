package com.mt.webnest.notification

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.mt.webnest.R
import com.mt.webnest.data.WebApp
import com.mt.webnest.ui.WebAppActivity
import java.lang.ref.WeakReference

object WebAppNotifications {
    private const val CHANNEL = "web_app_controls"

    private data class Details(val id: Long, val url: String)

    private val active = mutableMapOf<Long, WeakReference<WebAppActivity>>()
    private val pending = linkedMapOf<Long, Details>()
    private val handler = Handler(Looper.getMainLooper())
    private var application: Context? = null
    private var scheduled = false
    private val publishNext =
        object : Runnable {
            override fun run() {
                val details = pending.values.firstOrNull()
                if (details != null) {
                    pending.remove(details.id)
                    application?.let { publish(it, details) }
                }
                scheduled = pending.isNotEmpty()
                if (scheduled) handler.postDelayed(this, 500)
            }
        }

    fun register(id: Long, activity: WebAppActivity) {
        active[id] = WeakReference(activity)
    }

    fun unregister(id: Long, activity: WebAppActivity) {
        if (active[id]?.get() === activity) active.remove(id)
    }

    fun currentUrl(id: Long): String? = active[id]?.get()?.currentPageUrl() ?: pending[id]?.url

    fun close(context: Context, id: Long) {
        active.remove(id)?.get()?.closeWebApp()
        cancel(context, id)
        context.getSystemService(android.app.ActivityManager::class.java).appTasks.forEach { task ->
            val base = task.taskInfo?.baseIntent ?: return@forEach
            if (
                base.component?.className == WebAppActivity::class.java.name &&
                    base.data?.lastPathSegment == id.toString()
            )
                task.finishAndRemoveTask()
        }
    }

    fun cancel(context: Context, id: Long) {
        pending.remove(id)
        context.getSystemService(NotificationManager::class.java).cancel("webapp:$id", 1)
    }

    fun show(context: Context, app: WebApp, pageUrl: String? = null) {
        val details = Details(app.id, pageUrl?.takeIf { it.isNotBlank() } ?: app.url)
        if (pending[app.id] == details) return
        pending[app.id] = details
        application = context.applicationContext
        if (!scheduled) {
            scheduled = true
            handler.postDelayed(publishNext, 500)
        }
    }

    @SuppressLint("LaunchActivityFromNotification")
    private fun publish(context: Context, details: Details) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Web App controls", NotificationManager.IMPORTANCE_LOW)
                .apply {
                    description = "Copy the current URL, reload or close websites"
                    setShowBadge(false)
                }
        )
        if (
            Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS,
                ) != PackageManager.PERMISSION_GRANTED
        )
            return
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val copy =
            PendingIntent.getBroadcast(
                context,
                0,
                Intent(context, CopyUrlReceiver::class.java)
                    .setData(Uri.parse("webnest://app/${details.id}"))
                    .putExtra("url", details.url),
                flags,
            )
        val reload =
            PendingIntent.getActivity(
                context,
                1,
                WebAppActivity.intent(context, details.id)
                    .putExtra(WebAppActivity.COMMAND, "reload"),
                flags,
            )
        val close =
            PendingIntent.getBroadcast(
                context,
                2,
                Intent(context, CloseWebAppReceiver::class.java)
                    .setData(Uri.parse("webnest://app/${details.id}")),
                flags,
            )
        val notification =
            NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("WebNest")
                .setContentText("Tap to copy the URL")
                .setContentIntent(copy)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .addAction(0, "Reload", reload)
                .addAction(0, "Close", close)
                .build()
        manager.notify("webapp:${details.id}", 1, notification)
    }
}

class CopyUrlReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.data?.lastPathSegment?.toLongOrNull() ?: return
        val url =
            WebAppNotifications.currentUrl(id)
                ?: intent.getStringExtra("url")?.takeIf { it.isNotBlank() }
                ?: return
        context
            .getSystemService(ClipboardManager::class.java)
            .setPrimaryClip(ClipData.newPlainText("URL", url))
        if (Build.VERSION.SDK_INT < 33)
            android.widget.Toast.makeText(context, "URL copied", android.widget.Toast.LENGTH_SHORT)
                .show()
    }
}

class CloseWebAppReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.data?.lastPathSegment?.toLongOrNull() ?: return
        WebAppNotifications.close(context, id)
    }
}
