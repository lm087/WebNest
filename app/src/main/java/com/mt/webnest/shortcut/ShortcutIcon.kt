package com.mt.webnest.shortcut

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.drawable.Icon
import androidx.core.content.ContextCompat
import com.mt.webnest.R
import com.mt.webnest.data.WebApp

object ShortcutIcon {
    fun create(context: Context, app: WebApp): Icon =
        Icon.createWithAdaptiveBitmap(render(context, app))

    internal fun render(context: Context, app: WebApp): Bitmap {
        val side = 216
        val result = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        val background = app.themeColor?.or(0xff000000.toInt()) ?: 0xffeeeeee.toInt()
        canvas.drawColor(background)
        val source =
            app.icon?.let { bytes ->
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                val options =
                    BitmapFactory.Options().apply {
                        inSampleSize = 1
                        while (
                            maxOf(bounds.outWidth, bounds.outHeight) / inSampleSize > 512
                        ) inSampleSize *= 2
                    }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            }
        if (source != null) {
            val scale = (side * 2f / 3f) / minOf(source.width, source.height)
            val shader = BitmapShader(source, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            shader.setLocalMatrix(
                Matrix().apply {
                    setScale(scale, scale)
                    postTranslate(
                        (side - source.width * scale) / 2f,
                        (side - source.height * scale) / 2f,
                    )
                }
            )
            canvas.drawRect(
                0f,
                0f,
                side.toFloat(),
                side.toFloat(),
                Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
                    this.shader = shader
                },
            )
            source.recycle()
        } else {
            val brightness =
                Color.red(background) * .299 +
                    Color.green(background) * .587 +
                    Color.blue(background) * .114
            ContextCompat.getDrawable(context, R.drawable.ic_globe)?.mutate()?.apply {
                setTint(if (brightness < 150) Color.WHITE else 0xff37474f.toInt())
                val inset = side * 32 / 108
                setBounds(inset, inset, side - inset, side - inset)
                draw(canvas)
            }
        }
        return result
    }
}
