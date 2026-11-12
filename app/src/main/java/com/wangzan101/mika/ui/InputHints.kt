package com.wangzan101.mika.ui

import androidx.annotation.StringRes
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import com.wangzan101.mika.R
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.theme.MikaType
import kotlinx.coroutines.delay

/** Gray examples for an input. Each entry uses lines that match what that entry creates. */
internal object InputHints {
    val home = listOf(R.string.hint_home_remind, R.string.hint_home_courier, R.string.hint_home_property, R.string.hint_home_ask)
    val plan = listOf(R.string.hint_plan_morning, R.string.hint_plan_rain, R.string.hint_plan_friday)
    val call = listOf(R.string.hint_call_courier, R.string.hint_call_property, R.string.hint_call_delivery)
    val brief = listOf(R.string.hint_brief_busy, R.string.hint_brief_courier, R.string.hint_brief_unknown)

    fun next(index: Int, count: Int): Int = if (count <= 1) 0 else (index + 1) % count
}

/** Fade between [hints] every few seconds. Focusing or typing sets [active] false and the current line stays. */
@Composable
internal fun RotatingPlaceholder(
    hints: List<String>,
    active: Boolean,
    reduceMotion: Boolean,
    modifier: Modifier = Modifier,
    intervalMs: Long = 3_200L,
) {
    if (hints.isEmpty()) return
    var index by remember(hints) { mutableIntStateOf(0) }
    LaunchedEffect(active, hints, intervalMs) {
        if (!active || hints.size < 2) return@LaunchedEffect
        while (true) {
            delay(intervalMs)
            index = InputHints.next(index, hints.size)
        }
    }
    val line = hints[index.coerceIn(0, hints.lastIndex)]
    Crossfade(
        line,
        modifier,
        animationSpec = if (reduceMotion) snap() else tween(420),
        label = "input-hint",
    ) { text ->
        Text(
            text,
            Modifier.fillMaxWidth(),
            style = MikaType.Body,
            color = MikaTheme.colors.ink3,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
internal fun hintLines(@StringRes ids: List<Int>): List<String> = ids.map { androidx.compose.ui.res.stringResource(it) }
