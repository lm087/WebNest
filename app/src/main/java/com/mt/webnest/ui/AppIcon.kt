package com.mt.webnest.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.material3.Icon
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import com.mt.webnest.R

@Composable
fun AppIcon(bytes: ByteArray?, modifier: Modifier = Modifier, size: Dp = 48.dp, themeColor: Int? = null) {
    val bitmap = remember(bytes) { bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() } }
    val background = themeColor?.let { Color(it) } ?: MaterialTheme.colorScheme.surfaceContainerHigh
    val style = modifier.size(size).clip(RoundedCornerShape(12.dp)).background(background)
    if (bitmap != null) Image(bitmap, contentDescription = null, modifier = style, contentScale = ContentScale.Crop)
    else Icon(painterResource(R.drawable.ic_globe), contentDescription = null, modifier = style.padding(10.dp), tint = if (themeColor == null) MaterialTheme.colorScheme.onSurfaceVariant else if (background.luminance() > .179f) Color.Black else Color.White)
}