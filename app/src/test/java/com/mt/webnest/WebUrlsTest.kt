package com.mt.webnest

import com.mt.webnest.web.WebUrls
import org.junit.Assert.*
import org.junit.Test

class WebUrlsTest {
    @Test fun acceptsWebAddressesAndPreservesPathQueryAndFragment() {
        assertEquals("https://example.com/a?q=hello%20world#section", WebUrls.normalize(" example.com/a?q=hello%20world#section "))
        assertEquals("http://localhost:8080/path", WebUrls.normalize("http://localhost:8080/path"))
        assertEquals("https://xn--bcher-kva.de/", WebUrls.normalize("https://bücher.de/"))
        assertEquals("http://[::1]:8080/", WebUrls.normalize("http://[::1]:8080/"))
    }
    @Test fun rejectsUnsafeOrMalformedInput() {
        listOf("", "not a URL", "javascript:alert(1)", "file:///etc/passwd", "intent://test", "https://user:password@example.com",
            "https://", "https://example.com:99999", "https://example.com:0", "https://example.com\\evil").forEach {
            assertNull(it, WebUrls.normalize(it))
        }
    }
    @Test fun extractsUrlFromBrowserShareWithoutEatingBalancedParentheses() {
        assertEquals("https://example.com/a", WebUrls.fromSharedText("Read this: https://example.com/a\nExample title"))
        assertEquals("https://example.com/wiki/Test_(one)", WebUrls.fromSharedText("(https://example.com/wiki/Test_(one))."))
        assertEquals("https://example.com", WebUrls.fromSharedText("看看 https://example.com。"))
        assertNull(WebUrls.fromSharedText("Some text with no address"))
    }
}
