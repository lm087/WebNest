package com.mt.webnest.shortcut

import android.content.Context
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import com.mt.webnest.data.WebApp
import com.mt.webnest.ui.WebAppActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object WebAppShortcutManager {
    private fun info(context: Context, app: WebApp) = ShortcutInfo.Builder(context, app.id.toString()).setShortLabel(app.name.take(40)).setLongLabel(app.name).setIcon(ShortcutIcon.create(context, app)).setIntent(WebAppActivity.intent(context, app.id)).build()

    suspend fun sync(context: Context, apps: List<WebApp>): Boolean = withContext(Dispatchers.IO) { runCatching {
        val manager = context.getSystemService(ShortcutManager::class.java)
        val byId = apps.associateBy { it.id.toString()}
        val pinned = manager.pinnedShortcuts.mapNotNull { byId[it.id]}
        if (pinned.isNotEmpty()) manager.updateShortcuts(pinned.map { info(context, it)})
        manager.dynamicShortcuts = apps.take(manager.maxShortcutCountPerActivity).map { info(context, it)}
        !manager.isRateLimitingActive
    }.getOrDefault(false) }

    suspend fun refreshIconsIfNeeded(context: Context, apps: List<WebApp>) {
        val preferences = context.getSharedPreferences("shortcuts", Context.MODE_PRIVATE)
        if (preferences.getInt("icon_revision", 0) != 1 && sync(context, apps)) preferences.edit().putInt("icon_revision", 1).apply()
    }

    fun pin(context: Context, app: WebApp): Boolean = runCatching {
        val manager = context.getSystemService(ShortcutManager::class.java)
        manager.isRequestPinShortcutSupported && manager.requestPinShortcut(info(context, app), null)
    }.getOrDefault(false)

    fun remove(context: Context, id: Long) {
        val manager = context.getSystemService(ShortcutManager::class.java)
        runCatching {
            manager.disableShortcuts(listOf(id.toString()), "This Web App has been deleted")
            manager.removeDynamicShortcuts(listOf(id.toString()))
        }
    }
}