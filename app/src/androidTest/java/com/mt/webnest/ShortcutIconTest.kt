package com.mt.webnest

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.Icon
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mt.webnest.data.WebApp
import com.mt.webnest.shortcut.ShortcutIcon
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream

@RunWith(AndroidJUnit4::class)
class ShortcutIconTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private fun app(icon: ByteArray? = null, color: Int? = null) = WebApp(name = "Icon fixture", url = "https://example.com", icon = icon, themeColor = color)
    private fun bytes(image: Bitmap): ByteArray = ByteArrayOutputStream().use {
        image.compress(Bitmap.CompressFormat.PNG, 100, it); image.recycle(); it.toByteArray()
    }

    @Test fun transparentArtworkUsesOpaqueSiteColorThroughoutTheLauncherMask() {
        val bytes = bytes(Bitmap.createBitmap(24, 24, Bitmap.Config.ARGB_8888).apply { setPixel(12, 12, Color.RED) })
        val bitmap = ShortcutIcon.render(context, app(bytes, Color.BLUE))
        try {
            for (point in listOf(0 to 0, 36 to 36, 179 to 179, 215 to 215)) {
                assertEquals(Color.BLUE, bitmap.getPixel(point.first, point.second))
            }
            assertEquals(255, Color.alpha(bitmap.getPixel(108, 108)))
            if (android.os.Build.VERSION.SDK_INT >= 28) {
                assertEquals(Icon.TYPE_ADAPTIVE_BITMAP, ShortcutIcon.create(context, app(bytes)).type)
            }
        } finally { bitmap.recycle() }
    }

    @Test fun croppedArtworkRetainsItsVisibleViewportAndExtendsEdgesWithoutInsets() {
        val image = Bitmap.createBitmap(24, 24, Bitmap.Config.ARGB_8888)
        for (y in 0 until 24) for (x in 0 until 24) image.setPixel(x, y, if (x < 12) Color.RED else Color.GREEN)
        val bitmap = ShortcutIcon.render(context, app(bytes(image)))
        try {
            assertEquals(Color.RED, bitmap.getPixel(0, 108))
            assertEquals(Color.RED, bitmap.getPixel(40, 108))
            assertEquals(Color.GREEN, bitmap.getPixel(175, 108))
            assertEquals(Color.GREEN, bitmap.getPixel(215, 108))
        } finally { bitmap.recycle() }
    }

    @Test fun missingOrCorruptIconsHaveAnOpaqueVisibleGlobeOnLightAndDarkBackgrounds() {
        for (color in listOf(Color.WHITE, Color.BLACK)) for (icon in listOf(null, byteArrayOf(1, 2))) {
            val bitmap = ShortcutIcon.render(context, app(icon, color))
            try {
                assertEquals(color, bitmap.getPixel(0, 0))
                var contrasting = 0
                for (y in 60 until 156) for (x in 60 until 156) {
                    val pixel = bitmap.getPixel(x, y)
                    assertEquals(255, Color.alpha(pixel))
                    if (pixel != color) contrasting++
                }
                assertTrue("Fallback globe must remain visible", contrasting > 100)
            } finally { bitmap.recycle() }
        }
    }
}
