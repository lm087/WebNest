package com.mt.webnest

import com.mt.webnest.web.SiteMetadata
import com.mt.webnest.web.SiteColor
import org.junit.Assert.*
import org.junit.Test

class SiteMetadataTest {
    @Test fun themeColorSkipsInvalidAndPrefersUnconditionalMetadata() {
        val result = SiteMetadata.parse("""<meta name="theme-color" content="#000" media="(prefers-color-scheme: dark)">
            <meta name="theme-color" content="invalid"><meta name="theme-color" content="#123abc">""", "https://example.com")
        assertEquals(0xff123abc.toInt(), result.themeColor)
        assertEquals(0xffaabbcc.toInt(), SiteColor.parse("#abc"))
        assertEquals(0xff123456.toInt(), SiteColor.parse("#123456ff"))
        assertEquals(0xff010203.toInt(), SiteColor.parse("rgb(1, 2, 3)"))
        assertNull(SiteColor.parse("#12345600"))
        assertNull(SiteColor.parse("rgba(1, 2, 3, 0.5)"))
        assertNull(SiteColor.parse("rgb(400, 2, 3)"))
    }
    @Test fun extractsMultilineTitleAndResolvesRelativeIcons() {
        val result = SiteMetadata.parse("""
            <TITLE> Example &amp;
 More </TITLE>
            <link sizes="32x32" href='../assets/icon.png' rel='shortcut icon'>
            <link rel=icon href=//cdn.example.com/icon.png>
            <link rel="stylesheet" href="style.css">
        """.trimIndent(), "https://example.com/path/page")
        assertEquals("Example &amp;\nMore", result.title)
        assertEquals(listOf("https://example.com/assets/icon.png", "https://cdn.example.com/icon.png"), result.icons)
    }
    @Test fun emptyAttributesAndUnsafeIconsDoNotBreakFallback() {
        val result = SiteMetadata.parse("""
            <link rel="" href=""><link rel="icon" href="javascript:alert(1)">
            <link rel="apple-touch-icon" href="/touch.png"><link rel="icon" href="/touch.png">
        """, "https://example.com/")
        assertNull(result.title)
        assertEquals(listOf("https://example.com/touch.png"), result.icons)
    }
    @Test fun missingMetadataHasAnEmptyResult() {
        val result = SiteMetadata.parse("<html><body>Hello</body></html>", "https://example.com")
        assertNull(result.title)
        assertTrue(result.icons.isEmpty())
    }
    @Test fun resolvesPwaManifestThroughBaseAndFallsBackToSocialTitle() {
        val result = SiteMetadata.parse("""
            <base href="/assets/"><link rel="manifest" href="app.webmanifest">
            <meta property="og:site_name" content="Personal App">
        """, "https://example.com/deep/page")
        assertEquals("https://example.com/assets/app.webmanifest", result.manifest)
        assertEquals("Personal App", result.title)
    }

}
