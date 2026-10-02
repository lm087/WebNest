package com.mt.webnest.ui

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.mt.webnest.R

class ManagerReorderState(val list: LazyListState) {
    var draggingId by mutableStateOf<Long?>(null)
        private set

    private var center by mutableFloatStateOf(0f)
    var onMove: (Long, Long) -> Unit = { _, _ -> }
    var onFinish: () -> Unit = {}

    fun start(id: Long) {
        val item = list.layoutInfo.visibleItemsInfo.firstOrNull { it.key == id } ?: return
        center = item.offset + item.size / 2f
        draggingId = id
    }

    fun drag(dy: Float) {
        center += dy
        move()
    }

    private fun move() {
        val id = draggingId ?: return
        val visible = list.layoutInfo.visibleItemsInfo
        val current = visible.firstOrNull { it.key == id } ?: return
        val target =
            if (center > current.offset + current.size / 2f)
                visible.lastOrNull { it.index > current.index && center > it.offset + it.size / 2f }
            else
                visible.firstOrNull {
                    it.index < current.index && center < it.offset + it.size / 2f
                }
        (target?.key as? Long)?.let { onMove(id, it) }
    }

    fun scrollStep(edge: Float) {
        val info = list.layoutInfo
        val delta =
            when {
                center < info.viewportStartOffset + edge ->
                    -((info.viewportStartOffset + edge - center) / 8).coerceAtMost(edge / 4)
                center > info.viewportEndOffset - edge ->
                    ((center - info.viewportEndOffset + edge) / 8).coerceAtMost(edge / 4)
                else -> 0f
            }
        if (delta != 0f) {
            list.dispatchRawDelta(delta)
            move()
        }
    }

    fun offset(id: Long): Float {
        if (draggingId != id) return 0f
        val item = list.layoutInfo.visibleItemsInfo.firstOrNull { it.key == id } ?: return 0f
        return center - item.offset - item.size / 2f
    }

    fun finish() {
        if (draggingId != null) {
            draggingId = null
            onFinish()
        }
    }
}

@Composable
fun ReorderHandle(id: Long, name: String, state: ManagerReorderState, enabled: Boolean) {
    Box(
        Modifier.size(48.dp).pointerInput(state, id, enabled) {
            if (enabled)
                detectDragGestures(
                    onDragStart = { state.start(id) },
                    onDragEnd = state::finish,
                    onDragCancel = state::finish,
                    onDrag = { change, amount ->
                        change.consume()
                        state.drag(amount.y)
                    },
                )
        },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painterResource(R.drawable.ic_drag),
            "Move $name",
            Modifier.size(24.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
