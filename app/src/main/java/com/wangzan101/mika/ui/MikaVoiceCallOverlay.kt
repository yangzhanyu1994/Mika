package com.wangzan101.mika.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
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
import androidx.core.view.WindowCompat
import com.wangzan101.mika.R
import com.wangzan101.mika.ui.theme.MikaIcons
import com.wangzan101.mika.ui.theme.MikaType
import java.util.Locale

internal val OrbMotion.callLabel: Int
    get() = when (this) {
        OrbMotion.Idle, OrbMotion.Listening -> R.string.mika_voice_call_speak
        OrbMotion.Speaking -> R.string.mika_voice_call_mika_speaking
        OrbMotion.Connecting -> R.string.calls_connecting
        OrbMotion.Thinking -> R.string.assistant_thinking
        OrbMotion.Searching -> R.string.assistant_searching
        OrbMotion.Observing -> R.string.mika_voice_call_observing
        OrbMotion.Working -> R.string.chat_working
        OrbMotion.Writing -> R.string.mika_voice_call_writing
        OrbMotion.Waiting -> R.string.screens_waiting
        OrbMotion.Complete -> R.string.result_completed
    }

private val CallBackground = Color(0xFF101013)
private val CallInk = Color(0xFFF0F0F3)
private val CallAccent = Color(0xFF74CEFF)

@Composable
internal fun MikaVoiceCallOverlay(
    active: Boolean,
    connected: Boolean,
    seconds: Long,
    motion: OrbMotion,
    connector: String?,
    needsTaskView: Boolean,
    input: Float,
    output: Float,
    message: Int,
    action: Int,
    showCancel: Boolean,
    onAction: () -> Unit,
    onDismiss: () -> Unit,
    speakerOn: Boolean = true,
    onSpeaker: () -> Unit = {},
) {
    val readable = MikaType.Body.copy(textAlign = TextAlign.Center)
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnClickOutside = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        val view = LocalView.current
        val window = (view.parent as? DialogWindowProvider)?.window
        SideEffect {
            window?.let {
                WindowCompat.getInsetsController(it, view).apply {
                    isAppearanceLightStatusBars = false
                    isAppearanceLightNavigationBars = false
                }
            }
        }
        val fontScale = LocalDensity.current.fontScale
        val status = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
        val navigation = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        Box(
            Modifier.fillMaxSize().background(CallBackground),
            contentAlignment = Alignment.Center,
        ) {
            if (active) {
                BoxWithConstraints(Modifier.widthIn(max = 480.dp).fillMaxSize()) {
                    val headerDp = status.value + CallLayout.headerDropDp(fontScale) + 56f + if (connected) 78f else 0f
                    val buttonDp = 96f
                    val lift = CallLayout.hangupLiftDp(maxHeight.value, navigation.value, headerDp, buttonDp)
                    val used = (headerDp + buttonDp + navigation.value + lift).dp
                    val diameter = minOf(maxWidth * 0.72f, (maxHeight - used).coerceAtLeast(96.dp), 320.dp)
                    Column(
                        Modifier.fillMaxSize().padding(horizontal = 12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Row(
                            Modifier.fillMaxWidth().padding(top = status + CallLayout.headerDropDp(fontScale).dp).heightIn(min = 48.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            IconButton(onClick = onDismiss, modifier = Modifier.testTag("mika-call-collapse")) {
                                Icon(MikaIcons.Chevron, stringResource(R.string.mika_voice_call_collapse), Modifier.rotate(90f), tint = CallInk)
                            }
                            Text(
                                stringResource(R.string.app_name),
                                Modifier.weight(1f),
                                color = CallInk, style = MikaType.Title, textAlign = TextAlign.Center, maxLines = 1,
                            )
                            IconButton(onClick = onSpeaker, modifier = Modifier.testTag("mika-call-speaker")) {
                                Icon(
                                    if (speakerOn) MikaIcons.Sound else MikaIcons.Mute,
                                    stringResource(if (speakerOn) R.string.mika_voice_call_speaker_on else R.string.mika_voice_call_speaker_off),
                                    tint = CallInk,
                                )
                            }
                        }
                        if (connected) {
                            Row(
                                Modifier.padding(top = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(7.dp),
                            ) {
                                Box(Modifier.size(7.dp).background(Color(0xFF58D698), CircleShape))
                                Text(stringResource(R.string.mika_voice_call_connected), color = CallInk, style = readable)
                            }
                            Text(
                                String.format(Locale.ROOT, "%02d:%02d", seconds / 60, seconds % 60),
                                Modifier.padding(top = 4.dp).testTag("mika-call-timer"),
                                color = CallInk,
                                style = readable.copy(fontFamily = FontFamily.Monospace, fontSize = 24.sp),
                            )
                        }
                        Column(
                            Modifier.weight(1f).verticalScroll(rememberScrollState()),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                        ) {
                            MikaCallOrb(diameter, motion, input, output)
                            Text(
                                text = if (connector != null) stringResource(R.string.progress_calling_mcp, connector)
                                else stringResource(message),
                                modifier = Modifier.padding(top = 8.dp).testTag("mika-call-state"),
                                color = CallInk, style = readable,
                            )
                            if (needsTaskView) {
                                TextButton(onClick = onDismiss) {
                                    Text(stringResource(R.string.mika_voice_call_open_task), color = CallAccent)
                                }
                            }
                        }
                        Column(
                            Modifier.padding(bottom = navigation + lift.dp)
                                .clip(CircleShape).clickable(role = Role.Button, onClick = onAction)
                                .padding(horizontal = 14.dp, vertical = 8.dp)
                                .testTag("mika-call-hangup"),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Box(Modifier.size(58.dp).background(Color(0xFFFF655F), CircleShape), contentAlignment = Alignment.Center) {
                                Icon(MikaIcons.Handset, null, Modifier.size(27.dp).rotate(135f), tint = Color.White)
                            }
                            Text(stringResource(action), Modifier.padding(top = 6.dp), color = CallInk, style = readable)
                        }
                    }
                }
            } else {
                Column(
                    Modifier.widthIn(max = 430.dp).verticalScroll(rememberScrollState())
                        .padding(start = 24.dp, end = 24.dp, top = status + 24.dp, bottom = navigation + 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(stringResource(R.string.mika_voice_call), color = CallInk, style = MikaType.Title)
                    Text(stringResource(message), Modifier.padding(top = 14.dp), color = CallInk, style = readable)
                    Row(Modifier.padding(top = 17.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (showCancel) TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel), color = CallAccent) }
                        TextButton(onClick = onAction) { Text(stringResource(action), color = CallAccent) }
                    }
                }
            }
        }
    }
}
