package com.wangzan101.mika.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import com.wangzan101.mika.R
import com.wangzan101.mika.ui.theme.MikaIcons
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.theme.MikaType
import java.util.Locale

@Composable
internal fun MikaVoiceCallOverlay(
    active: Boolean,
    connected: Boolean,
    seconds: Long,
    speaking: Boolean,
    input: Float,
    output: Float,
    message: Int,
    action: Int,
    showCancel: Boolean,
    onAction: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = MikaTheme.colors
    val readable = MikaType.Body.copy(
        textAlign = TextAlign.Center,
        shadow = Shadow(color = colors.bg.copy(alpha = 0.82f), blurRadius = 8f),
    )
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false),
    ) {
        val window = (LocalView.current.parent as? DialogWindowProvider)?.window
        SideEffect { window?.setDimAmount(0f) }
        BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            val diameter = minOf(maxWidth * 0.56f, maxHeight * 0.42f, 260.dp)
            Column(
                modifier = Modifier.widthIn(max = 430.dp).verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp, vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (connected) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(Modifier.size(8.dp).background(colors.ok, CircleShape))
                        Text(stringResource(R.string.mika_voice_call_connected), color = colors.ink, style = readable)
                    }
                    Text(
                        String.format(Locale.ROOT, "%02d:%02d", seconds / 60, seconds % 60),
                        Modifier.padding(top = 6.dp).testTag("mika-call-timer"),
                        color = colors.ink, style = readable.copy(fontFamily = FontFamily.Monospace, fontSize = 22.sp),
                    )
                } else if (active) {
                    Text(stringResource(message), color = colors.ink, style = readable)
                    Spacer(Modifier.height(31.dp))
                } else {
                    Text(stringResource(R.string.mika_voice_call), color = colors.ink, style = MikaType.Title)
                    Text(stringResource(message), Modifier.padding(top = 14.dp), color = colors.ink, style = readable)
                }

                if (active) {
                    MikaCallOrb(diameter, speaking, input, output)
                    Text(
                        stringResource(if (connected) message else R.string.mika_voice_call_transcript_note),
                        color = colors.ink, style = readable,
                    )
                    Column(
                        modifier = Modifier.padding(top = 22.dp).clip(CircleShape)
                            .clickable(role = Role.Button, onClick = onAction).padding(horizontal = 14.dp, vertical = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Box(Modifier.size(58.dp).background(colors.bad, CircleShape), contentAlignment = Alignment.Center) {
                            Icon(MikaIcons.Handset, null, Modifier.size(27.dp).rotate(135f), tint = Color.White)
                        }
                        Text(stringResource(action), Modifier.padding(top = 7.dp), color = colors.ink, style = readable)
                    }
                } else {
                    Row(Modifier.padding(top = 17.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (showCancel) TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
                        TextButton(onClick = onAction) { Text(stringResource(action)) }
                    }
                }
            }
        }
    }
}
