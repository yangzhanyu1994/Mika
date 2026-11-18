package com.wangzan101.mika.assistant

import android.graphics.Bitmap
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.wangzan101.mika.R
import com.wangzan101.mika.task.TaskEdgeLight
import com.wangzan101.mika.ui.MarkdownText
import com.wangzan101.mika.ui.theme.MikaIcons
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.theme.edgeGlow

private val Panel = Color(0xF2192437)
private val Accent = Color(0xFF9EE8FF)
private val Foreground = Color(0xFFF4F8FF)
private val Muted = Color(0xFFB7C6DC)

internal data class AssistantReply(val title: String, val body: String = "", val running: Boolean = false)

@Composable
internal fun AssistantOverlay(
    text: String,
    partial: String,
    status: String?,
    listening: Boolean,
    converting: Boolean,
    level: () -> Float,
    screenshot: Bitmap?,
    photo: Bitmap?,
    sending: Boolean,
    onText: (String) -> Unit,
    onEdit: () -> Unit,
    onVoice: () -> Unit,
    onScreenshot: () -> Unit,
    onPhoto: () -> Unit,
    onRemoveScreenshot: () -> Unit,
    onRemovePhoto: () -> Unit,
    onClear: () -> Unit,
    onSend: () -> Unit,
    onClose: () -> Unit,
    reply: AssistantReply? = null,
    onOpenConversation: () -> Unit = {},
) {
    val glow by animateFloatAsState(if (listening) 1f else 0.48f, label = "assistant glow")
    val running = reply?.running == true
    val reducedMotion = MikaTheme.reduceMotion
    val taskGlow = remember { mutableFloatStateOf(1f) }
    LaunchedEffect(running, reducedMotion) {
        if (running && !reducedMotion) {
            val started = withFrameNanos { it }
            while (true) withFrameNanos { frame ->
                taskGlow.floatValue = TaskEdgeLight.breathingAlpha((frame - started) / 1_000_000)
            }
        }
    }
    val screenLabel = stringResource(R.string.assistant_preview_screen)
    val photoLabel = stringResource(R.string.assistant_preview_photo)
    var preview by remember { mutableStateOf<Pair<Bitmap, String>?>(null) }
    Box(Modifier.fillMaxSize().background(Color(0x24051731))) {
        Box(
            Modifier.fillMaxSize()
                .edgeGlow(Color(0xFF487DFF), Color(0xFFB9F9FF), reducedMotion, depth = 46.dp) {
                    if (running) {
                        if (reducedMotion) 0.48f else taskGlow.floatValue
                    } else (glow * (0.73f + level().coerceIn(0f, 1f) * 0.27f)).coerceAtMost(1f)
                }
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    enabled = !sending,
                    onClick = onClose,
                ).clearAndSetSemantics {},
        )
        Column(
            Modifier.align(Alignment.BottomCenter).windowInsetsPadding(WindowInsets.safeDrawing).imePadding()
                .padding(horizontal = 16.dp, vertical = 12.dp).widthIn(max = 540.dp).fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = Panel,
                contentColor = Foreground,
                shape = RoundedCornerShape(28.dp),
                shadowElevation = 10.dp,
            ) {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Icon(MikaIcons.Spark, null, Modifier.padding(start = 9.dp).size(16.dp), tint = Accent)
                        Text(
                            if (listening) stringResource(R.string.voice_listening) else if (converting)
                                stringResource(R.string.voice_converting) else stringResource(R.string.app_name),
                            Modifier.weight(1f).padding(start = 8.dp),
                            color = if (listening) Accent else Foreground,
                            style = MaterialTheme.typography.labelLarge,
                        )
                        IconButton(onClick = onClose, enabled = !sending) {
                            Icon(MikaIcons.Close, stringResource(R.string.cancel), tint = Muted)
                        }
                    }
                    if (screenshot != null || photo != null) {
                        Row(Modifier.padding(start = 8.dp, bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (screenshot != null) MediaPreview(screenshot, screenLabel, !sending, { preview = screenshot to screenLabel }, onRemoveScreenshot)
                            if (photo != null) MediaPreview(photo, photoLabel, !sending, { preview = photo to photoLabel }, onRemovePhoto)
                        }
                    }
                    if (reply != null) {
                        if (text.isNotBlank()) {
                            Text(
                                text, Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                                style = MaterialTheme.typography.bodyLarge, maxLines = 4,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    } else {
                        BasicTextField(
                            value = text,
                            onValueChange = onText,
                            enabled = !sending,
                            minLines = 1,
                            maxLines = 4,
                            textStyle = MaterialTheme.typography.bodyLarge.copy(color = Foreground),
                            cursorBrush = SolidColor(Accent),
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 7.dp)
                                .onFocusChanged { if (it.isFocused) onEdit() },
                            decorationBox = { field ->
                                Box {
                                    if (text.isEmpty()) Text(stringResource(R.string.assistant_input), color = Muted)
                                    field()
                                }
                            },
                        )
                        if (partial.isNotBlank()) {
                            Text(partial, Modifier.padding(horizontal = 10.dp, vertical = 4.dp), color = Accent,
                                maxLines = 3, style = MaterialTheme.typography.bodyMedium)
                        }
                        if (status != null || sending) {
                            Text(status ?: stringResource(R.string.attach_importing),
                                Modifier.padding(horizontal = 10.dp, vertical = 4.dp), color = Muted,
                                style = MaterialTheme.typography.bodySmall)
                        }
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = onVoice, enabled = !converting && !sending) {
                                Icon(
                                    if (listening) MikaIcons.Check else MikaIcons.Mic,
                                    stringResource(if (listening) R.string.assistant_stop_listening else R.string.voice_listening),
                                    tint = if (listening) Accent else Foreground,
                                )
                            }
                            IconButton(onClick = onScreenshot, enabled = !sending) {
                                Icon(MikaIcons.Camera, stringResource(R.string.assistant_take_screenshot))
                            }
                            IconButton(onClick = onPhoto, enabled = !sending) {
                                Icon(MikaIcons.PhotoCamera, stringResource(R.string.attach_camera))
                            }
                            IconButton(onClick = onClear, enabled = !sending &&
                                (listening || converting || text.isNotEmpty() || screenshot != null || photo != null)) {
                                Icon(MikaIcons.Trash, stringResource(R.string.assistant_clear))
                            }
                            Box(Modifier.weight(1f))
                            val canSend = !sending && (text.isNotBlank() || partial.isNotBlank() || screenshot != null || photo != null)
                            IconButton(
                                onClick = onSend,
                                enabled = canSend,
                                modifier = Modifier.size(44.dp).clip(CircleShape)
                                    .background(if (canSend) Accent else Color.White.copy(alpha = 0.12f)),
                            ) {
                                Icon(MikaIcons.Up, stringResource(R.string.chat_send), tint = if (canSend) Color(0xFF102239) else Muted)
                            }
                        }
                    }
                }
            }
            if (reply != null) AssistantReplyCard(reply, onOpenConversation)
        }
    }
    preview?.let { (image, label) ->
        AlertDialog(
            onDismissRequest = { preview = null },
            title = { Text(label) },
            text = {
                Image(remember(image) { image.asImageBitmap() }, label,
                    Modifier.fillMaxWidth().heightIn(max = 360.dp), contentScale = ContentScale.Fit)
            },
            confirmButton = { TextButton(onClick = { preview = null }) { Text(stringResource(R.string.done)) } },
        )
    }
}

@Composable
private fun AssistantReplyCard(reply: AssistantReply, onOpenConversation: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = Panel,
        contentColor = Foreground,
        shape = RoundedCornerShape(28.dp),
        shadowElevation = 10.dp,
    ) {
        Column(
            Modifier.padding(start = 20.dp, top = 16.dp, end = 20.dp, bottom = 6.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(reply.title, color = Accent, style = MaterialTheme.typography.labelLarge)
            if (reply.body.isNotBlank()) {
                MarkdownText(
                    reply.body.take(6_000).let { if (reply.body.length > it.length) "$it…" else it },
                    MaterialTheme.typography.bodyLarge.copy(color = Foreground),
                    Modifier.fillMaxWidth().heightIn(max = 180.dp).verticalScroll(rememberScrollState()),
                )
            }
            TextButton(onClick = onOpenConversation) {
                Text(stringResource(R.string.assistant_open_conversation), color = Accent)
            }
        }
    }
}

@Composable
private fun MediaPreview(bitmap: Bitmap, label: String, enabled: Boolean, onPreview: () -> Unit, onRemove: () -> Unit) {
    Box(Modifier.size(68.dp).clip(RoundedCornerShape(12.dp))) {
        Image(
            remember(bitmap) { bitmap.asImageBitmap() }, label,
            Modifier.fillMaxSize().clickable(enabled = enabled, onClick = onPreview), contentScale = ContentScale.Crop,
        )
        IconButton(
            onClick = onRemove, enabled = enabled,
            modifier = Modifier.align(Alignment.TopEnd).size(32.dp).clip(CircleShape).background(Color(0xC0000000)),
        ) {
            Icon(MikaIcons.Close, stringResource(R.string.attach_remove, label), Modifier.size(16.dp), tint = Color.White)
        }
    }
}
