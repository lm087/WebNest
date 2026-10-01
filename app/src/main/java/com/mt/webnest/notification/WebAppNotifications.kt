package com.mt.webnest.notification

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.mt.webnest.R
import com.mt.webnest.data.WebApp
import com.mt.webnest.ui.WebAppActivity
import java.lang.ref.WeakReference

object WebAppNotifications {
    private const val CHANNEL = "web_app_controls"
    private val active = mutableMapOf<Long, WeakReference<WebAppActivity>>()
    fun register(id: Long, activity: WebAppActivity) { active[id] = WeakReference(activity) }
    fun unregister(id: Long, activity: WebAppActivity) { if (active[id]?.get() === activity) active.remove(id) }
    fun close(context: Context, id: Long) {
        active.remove(id)?.get()?.closeWebApp()
        cancel(context, id)
        context.getSystemService(android.app.ActivityManager::class.java).appTasks.forEach { task ->
            val base = task.taskInfo?.baseIntent ?: return@forEach
            if (base.component?.className == WebAppActivity::class.java.name && base.data?.lastPathSegment == id.toString()) task.finishAndRemoveTask()
        }
    }
    fun cancel(context: Context, id: Long) { context.getSystemService(NotificationManager::class.java).cancel("webapp:$id", 1)}
    fun show(context: Context, app: WebApp) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Web App controls", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Reopen or reload or close websites from notifications"
            setShowBadge(false)
        })
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val open = PendingIntent.getActivity(context, 0, WebAppActivity.intent(context, app.id), flags)
        val reload = PendingIntent.getActivity(context, 1, WebAppActivity.intent(context, app.id).putExtra(WebAppActivity.COMMAND, "reload"), flags)
        val close = PendingIntent.getBroadcast(context, 2, Intent(context, CloseWebAppReceiver::class.java).setData(Uri.parse("webnest://app/${app.id}")), flags)
        val notification = NotificationCompat.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_notification).setContentTitle(app.name).setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true).setCategory(NotificationCompat.CATEGORY_SERVICE).setVisibility(NotificationCompat.VISIBILITY_PRIVATE).addAction(0, "Reload", reload).addAction(0, "Close", close).build()
        manager.notify("webapp:${app.id}", 1, notification)
    }
}

class CloseWebAppReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.data?.lastPathSegment?.toLongOrNull() ?: return
        WebAppNotifications.close(context, id)
    }
}