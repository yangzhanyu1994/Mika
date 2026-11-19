package com.wangzan101.mika.ui

import android.widget.NumberPicker
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.wangzan101.mika.R

internal fun formatClockMinute(minute: Int) =
    "${(minute / 60).toString().padStart(2, '0')}:${(minute % 60).toString().padStart(2, '0')}"

@Composable
internal fun ClockWheelDialog(title: String, initialMinute: Int, onDismiss: () -> Unit, onSelect: (Int) -> Unit) {
    var hour by remember(initialMinute) { mutableIntStateOf(initialMinute / 60) }
    var minute by remember(initialMinute) { mutableIntStateOf(initialMinute % 60) }
    AlertDialog(
        title = { Text(title) },
        onDismissRequest = onDismiss,
        text = {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally)) {
                ClockWheel(stringResource(R.string.clock_hour), 23, hour) { hour = it }
                ClockWheel(stringResource(R.string.clock_minute), 59, minute) { minute = it }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSelect(hour * 60 + minute); onDismiss() }) { Text(stringResource(R.string.done)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun ClockWheel(label: String, max: Int, selected: Int, onSelect: (Int) -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label)
        AndroidView(
            factory = { context ->
                NumberPicker(context).apply {
                    minValue = 0
                    maxValue = max
                    wrapSelectorWheel = true
                    descendantFocusability = android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS
                    contentDescription = label
                    setFormatter { it.toString().padStart(2, '0') }
                    setOnValueChangedListener { _, _, next -> onSelect(next) }
                    value = selected
                }
            },
            update = { view -> if (view.value != selected) view.value = selected },
        )
    }
}
