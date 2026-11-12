package com.wangzan101.mika.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.wangzan101.mika.R
import com.wangzan101.mika.ui.theme.MikaIcons
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.voice.VoiceSource

/** Hold-to-talk, or a typed sentence, then hand the words to the caller to analyze. */
@Composable
internal fun UtteranceCapture(
    voice: VoiceSource?,
    holdLabel: String,
    example: String,
    busy: Boolean,
    onUtterance: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var typing by rememberSaveable { mutableStateOf(false) }
    var text by rememberSaveable { mutableStateOf("") }
    val vh = rememberVoiceHold(voice, onText = { spoken ->
        val words = spoken.trim()
        if (words.isNotEmpty()) onUtterance(words)
    })
    val colors = MikaTheme.colors
    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (!typing) {
            Box(
                Modifier.size(96.dp).clip(CircleShape).background(colors.accent)
                    .then(if (vh != null && !busy) Modifier.holdToTalk(vh) {} else Modifier)
                    .testTag("utterance-mic"),
                contentAlignment = Alignment.Center,
            ) {
                if (busy) CircularProgressIndicator(color = colors.onAccent, modifier = Modifier.size(28.dp))
                else Icon(MikaIcons.Mic, holdLabel, Modifier.size(36.dp), tint = colors.onAccent)
            }
            Text(holdLabel, style = androidx.compose.material3.MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
            Text(example, style = androidx.compose.material3.MaterialTheme.typography.bodySmall, color = colors.ink3, textAlign = TextAlign.Center)
            TextButton(onClick = { typing = true }, enabled = !busy, modifier = Modifier.testTag("utterance-type")) {
                Text(stringResource(R.string.calls_task_use_text))
            }
        } else {
            OutlinedTextField(
                text, { text = it },
                modifier = Modifier.fillMaxWidth().testTag("utterance-field"),
                label = { Text(holdLabel) },
                placeholder = { Text(example) },
                minLines = 2, maxLines = 4, enabled = !busy,
            )
            TextButton(
                onClick = { val words = text.trim(); if (words.isNotEmpty()) onUtterance(words) },
                enabled = !busy && text.isNotBlank(),
                modifier = Modifier.testTag("utterance-analyze"),
            ) { Text(stringResource(if (busy) R.string.calls_task_analyzing else R.string.calls_task_analyze)) }
            TextButton(onClick = { typing = false }, enabled = !busy) { Text(stringResource(R.string.calls_task_use_voice)) }
        }
        if (vh != null) VoiceSheetHost(vh) {}
    }
}
