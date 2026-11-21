package com.wangzan101.mika.proactive

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.wangzan101.mika.R
import com.wangzan101.mika.ui.components.GroupCard
import com.wangzan101.mika.ui.components.GroupLabel
import com.wangzan101.mika.ui.components.ListRow
import com.wangzan101.mika.ui.components.MikaDialog
import com.wangzan101.mika.ui.components.MikaSwitch

/** Three independent local summaries. Times are editable. Nothing is sent off the device. */
@Composable
fun NoticeBriefSection() {
    val context = LocalContext.current
    GroupLabel(stringResource(R.string.brief_title))
    Text(
        stringResource(R.string.brief_note),
        Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
    )
    GroupCard {
        BriefSlot.entries.forEachIndexed { index, slot ->
            BriefRow(slot, index == 0) { spec -> NoticeBriefRuntime.save(context, slot, spec) }
        }
    }
}

@Composable
private fun BriefRow(slot: BriefSlot, first: Boolean, onSave: (BriefSpec) -> Unit) {
    val context = LocalContext.current
    var spec by remember(slot) { mutableStateOf(NoticeBriefRuntime.spec(context, slot)) }
    var editing by remember { mutableStateOf(false) }
    val title = stringResource(when (slot) {
        BriefSlot.Morning -> R.string.brief_morning
        BriefSlot.Afternoon -> R.string.brief_afternoon
        BriefSlot.AllDay -> R.string.brief_all_day
    })
    val range = if (NoticeBrief.crossesMidnight(spec)) stringResource(R.string.brief_cross_day) else stringResource(R.string.brief_same_day)
    ListRow(null, title, "$range ${clock(spec.fromMinute)}–${clock(spec.toMinute)} · ${clock(spec.remindMinute)}", first = first, onClick = { editing = true }) {
        MikaSwitch(spec.enabled, {
            spec = spec.copy(enabled = it)
            onSave(spec)
        })
    }
    if (editing) {
        var remind by remember { mutableStateOf(clock(spec.remindMinute)) }
        var from by remember { mutableStateOf(clock(spec.fromMinute)) }
        var to by remember { mutableStateOf(clock(spec.toMinute)) }
        MikaDialog(
            onDismissRequest = { editing = false },
            title = { Text(title) },
            text = {
                Column {
                    Text(stringResource(R.string.brief_edit_help))
                    OutlinedTextField(remind, { remind = it }, label = { Text(stringResource(R.string.brief_remind)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(from, { from = it }, label = { Text(stringResource(R.string.brief_from)) }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                    OutlinedTextField(to, { to = it }, label = { Text(stringResource(R.string.brief_to)) }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                }
            },
            confirmButton = {
                TextButton({
                    val next = spec.copy(
                        remindMinute = parseClock(remind) ?: spec.remindMinute,
                        fromMinute = parseClock(from) ?: spec.fromMinute,
                        toMinute = parseClock(to) ?: spec.toMinute,
                    )
                    spec = next
                    onSave(next)
                    editing = false
                }) { Text(stringResource(R.string.done)) }
            },
            dismissButton = { TextButton({ editing = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

private fun clock(minute: Int): String = "%02d:%02d".format(minute / 60, minute % 60)

private fun parseClock(text: String): Int? {
    val parts = text.trim().split(":", "：")
    if (parts.size != 2) return null
    val hour = parts[0].toIntOrNull() ?: return null
    val minute = parts[1].toIntOrNull() ?: return null
    if (hour !in 0..23 || minute !in 0..59) return null
    return hour * 60 + minute
}
