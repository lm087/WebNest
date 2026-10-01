package com.mt.webnest.ui

import kotlin.math.min
import kotlin.math.roundToInt

object IconCrop {
    data class Window(val left: Int, val top: Int, val side: Int)
    fun window(width: Int, height: Int, centerX: Float, centerY: Float, zoom: Float): Window {
        require(width > 0 && height > 0)
        val side = (min(width, height) / zoom.coerceIn(1f, 6f)).roundToInt().coerceIn(1, min(width, height))
        return Window((centerX * width - side / 2f).roundToInt().coerceIn(0, width - side), (centerY * height - side / 2f).roundToInt().coerceIn(0, height - side), side)
    }
}