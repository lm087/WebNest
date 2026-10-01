package com.mt.webnest

import com.mt.webnest.data.WebApp
import com.mt.webnest.web.SitePermissions
import com.mt.webnest.web.WebDownloads
import com.mt.webnest.web.WebSettingsPolicy
import org.junit.Assert.*
import org.junit.Test

class PhaseTwoPolicyTest {
    @Test fun desktopAndCustomUserAgents() {
        val default = "Mozilla/5.0 (Linux; Android 17; Phone; wv) AppleWebKit/537.36 Chrome/140.0.0.0 Mobile Safari/537.36"
        val app = WebApp(name = "test", url = "https://example.com")
        assertEquals(default, WebSettingsPolicy.userAgent(default, app))
        val desktop = WebSettingsPolicy.userAgent(default, app.copy(desktopMode = true))
        assertFalse(desktop.contains("Android")); assertFalse(desktop.contains("Mobile"))
        assertTrue(desktop.contains("Chrome/140.0.0.0"))
        assertEquals("Custom/1", WebSettingsPolicy.userAgent(default, app.copy(desktopMode = true, userAgent = "Custom/1")))
        assertFalse(WebSettingsPolicy.validUserAgent("Agent\r\nCookie: secret"))
        assertFalse(WebSettingsPolicy.validUserAgent("a".repeat(513)))
    }
    @Test fun permissionsAreScopedBySchemeHostAndPort() {
        assertEquals("https://example.com", SitePermissions.origin("https://EXAMPLE.com:443/path"))
        assertEquals("https://example.com:8443", SitePermissions.origin("https://example.com:8443/path"))
        assertNotEquals(SitePermissions.origin("http://example.com"), SitePermissions.origin("https://example.com"))
        assertNull(SitePermissions.origin("file:///etc/passwd"))
        assertNull(SitePermissions.origin("https://user@example.com"))
    }
    @Test fun filenamesCannotEscapeDownloads() {
        assertEquals("report.pdf", WebDownloads.fileName("../../report.pdf"))
        assertEquals("file.txt", WebDownloads.fileName("..\\file.txt"))
        assertEquals("download", WebDownloads.fileName(".."))
        assertFalse(WebDownloads.fileName("bad\nname?.txt").contains('\n'))
    }
}
