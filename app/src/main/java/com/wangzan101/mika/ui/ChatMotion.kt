package com.wangzan101.mika.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import com.wangzan101.mika.ui.theme.MikaTheme
import kotlin.math.sin

@Composable
internal fun MessageRise(reduceMotion: Boolean, content: @Composable () -> Unit) {
    var entered by rememberSaveable { mutableStateOf(reduceMotion) }
    LaunchedEffect(Unit) { entered = true }
    val progress by animateFloatAsState(if (entered) 1f else 0f, if (reduceMotion) snap() else tween(240), label = "message-rise")
    Box(Modifier.graphicsLayer { alpha = progress; translationY = (1f - progress) * 8.dp.toPx() }) { content() }
}

@Composable
internal fun ThinkingDots(reduceMotion: Boolean) {
    val color = MikaTheme.colors.accent
    val phase = if (reduceMotion) 0f else rememberInfiniteTransition(label = "thinking-dots").animateFloat(
        0f, 6.2831855f, infiniteRepeatable(tween(1200), RepeatMode.Restart), label = "thinking-phase",
    ).value
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        repeat(3) { i ->
            val opacity = if (reduceMotion) 0.7f else 0.5f + 0.4f * sin(phase - i * 1.1f)
            Box(Modifier.size(5.dp).alpha(opacity).background(color, CircleShape))
        }
    }
}
