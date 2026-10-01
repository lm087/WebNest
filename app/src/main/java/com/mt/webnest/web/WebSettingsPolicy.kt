package com.mt.webnest.web

import com.mt.webnest.data.WebApp

object WebSettingsPolicy {
    fun userAgent(default: String, app: WebApp): String {
        if (app.userAgent.isNotBlank()) return app.userAgent
        if (!app.desktopMode) return default
        return default.replace(Regex("\\([^)]*Android[^)]*\\)"), "(X11; Linux x86_64)")
            .replace(" Mobile", "").replace("; wv", "")
    }
    fun validUserAgent(value: String): Boolean = value.length <= 512 && value.all { it.code in 32..126 }
}
