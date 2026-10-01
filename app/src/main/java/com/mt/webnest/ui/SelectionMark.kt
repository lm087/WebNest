package com.mt.webnest.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.mt.webnest.R

@Composable
fun SelectionMark(checked: Boolean, modifier: Modifier = Modifier) {
    val ink = MaterialTheme.colorScheme.onSurface
    val alpha by animateFloatAsState(if (checked) .10f else .03f, label = "Selection wash")
    Box(modifier, contentAlignment = Alignment.CenterStart) {
        Canvas(Modifier.fillMaxHeight().width(80.dp)) { drawRect(Brush.horizontalGradient(listOf(ink.copy(alpha = alpha), Color.Transparent)))}
        Icon(painterResource(R.drawable.ic_check), null, Modifier.padding(start = 16.dp).size(24.dp), tint = ink.copy(alpha = if (checked) 1f else .25f))
    }
}