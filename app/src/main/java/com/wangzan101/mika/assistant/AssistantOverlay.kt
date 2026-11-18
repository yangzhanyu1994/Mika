package com.wangzan101.mika.assistant

import android.graphics.Bitmap
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.RepeatMode
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.border
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
import androidx.compose.ui.graphics.graphicsLayer
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

internal data class AssistantReply(
    val title: String,
    val body: String = "",
    val running: Boolean = false,
    val waiting: Boolean = false,
)

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
    avatar: Bitmap? = null,
    displayName: String = "",
    animateAvatar: Boolean = true,
) {
    val c = MikaTheme.colors
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
                color = c.surface.copy(alpha = 0.97f),
                contentColor = c.ink,
                shape = RoundedCornerShape(28.dp),
                shadowElevation = 10.dp,
            ) {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                    Box(Modifier.fillMaxWidth()) {
                        Column(
                            Modifier.padding(horizontal = 48.dp, vertical = 6.dp).align(Alignment.TopCenter),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(3.dp),
                        ) {
                            AssistantAvatar(avatar, 48.dp)
                            Text(displayName.ifBlank { stringResource(R.string.app_name) },
                                style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            val currentStatus = reply?.title ?: when {
                                listening -> stringResource(R.string.voice_listening)
                                converting -> stringResource(R.string.voice_converting)
                                else -> null
                            }
                            if (currentStatus != null) Text(currentStatus, color = c.accent, style = MaterialTheme.typography.bodySmall)
                        }
                        IconButton(onClick = onClose, enabled = !sending, modifier = Modifier.align(Alignment.TopEnd)) {
                            Icon(MikaIcons.Close, stringResource(R.string.cancel), tint = c.ink2)
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
                                text, Modifier.align(Alignment.End).widthIn(max = 300.dp)
                                    .padding(top = 6.dp).background(c.accentSoft, RoundedCornerShape(18.dp))
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                style = MaterialTheme.typography.bodyMedium, maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        if (reply.waiting) {
                            AssistantWaiting(avatar, animateAvatar && !reducedMotion)
                        }
                        if (reply.body.isNotBlank()) {
                            MarkdownText(
                                reply.body.take(6_000).let { if (reply.body.length > it.length) "$it…" else it },
                                MaterialTheme.typography.bodyMedium.copy(color = c.ink),
                                Modifier.fillMaxWidth().padding(top = 8.dp).heightIn(max = 144.dp)
                                    .verticalScroll(rememberScrollState())
                                    .background(c.fill, RoundedCornerShape(16.dp)).padding(10.dp),
                            )
                        }
                        TextButton(onClick = onOpenConversation) {
                            Text(stringResource(R.string.assistant_open_conversation), color = c.accent)
                        }
                    } else {
                        if (text.isNotBlank() || screenshot != null || photo != null) {
                            IconButton(onClick = onClear, enabled = !sending,
                                modifier = Modifier.align(Alignment.End).size(36.dp)) {
                                Icon(MikaIcons.Trash, stringResource(R.string.assistant_clear), tint = c.ink2)
                            }
                        }
                        if (partial.isNotBlank()) {
                            Text(partial, Modifier.padding(horizontal = 10.dp, vertical = 4.dp), color = c.accent,
                                maxLines = 3, style = MaterialTheme.typography.bodyMedium)
                        }
                        if (status != null || sending) {
                            Text(status ?: stringResource(R.string.attach_importing),
                                Modifier.padding(horizontal = 10.dp, vertical = 4.dp), color = c.ink2,
                                style = MaterialTheme.typography.bodySmall)
                        }
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 6.dp)
                                .background(c.fill, RoundedCornerShape(24.dp)).padding(horizontal = 3.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            IconButton(onClick = onPhoto, enabled = !sending, modifier = Modifier.size(42.dp)) {
                                Icon(MikaIcons.PhotoCamera, stringResource(R.string.attach_camera))
                            }
                            IconButton(onClick = onScreenshot, enabled = !sending, modifier = Modifier.size(42.dp)) {
                                Icon(MikaIcons.Camera, stringResource(R.string.assistant_take_screenshot))
                            }
                            BasicTextField(
                                value = text,
                                onValueChange = onText,
                                enabled = !sending,
                                minLines = 1,
                                singleLine = true,
                                textStyle = MaterialTheme.typography.bodyLarge.copy(color = c.ink),
                                cursorBrush = SolidColor(c.accent),
                                modifier = Modifier.weight(1f).padding(horizontal = 4.dp, vertical = 7.dp)
                                    .onFocusChanged { if (it.isFocused) onEdit() },
                                decorationBox = { field ->
                                    Box {
                                        if (text.isEmpty()) Text(stringResource(R.string.assistant_input), color = c.ink2)
                                        field()
                                    }
                                },
                            )
                            IconButton(onClick = onVoice, enabled = !converting && !sending, modifier = Modifier.size(42.dp)) {
                                Icon(
                                    if (listening) MikaIcons.Check else MikaIcons.Mic,
                                    stringResource(if (listening) R.string.assistant_stop_listening else R.string.voice_listening),
                                    tint = if (listening) c.accent else c.ink,
                                )
                            }
                            val canSend = !sending && (text.isNotBlank() || partial.isNotBlank() || screenshot != null || photo != null)
                            IconButton(
                                onClick = onSend,
                                enabled = canSend,
                                modifier = Modifier.size(42.dp).clip(CircleShape)
                                    .background(if (canSend) c.accent else c.fill),
                            ) {
                                Icon(MikaIcons.Up, stringResource(R.string.chat_send), tint = if (canSend) c.onAccent else c.ink2)
                            }
                        }
                    }
                }
            }
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
private fun AssistantWaiting(avatar: Bitmap?, animate: Boolean) {
    val c = MikaTheme.colors
    val lift = if (animate) rememberInfiniteTransition(label = "assistant waiting avatar").animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(720), RepeatMode.Reverse), label = "avatar lift",
    ) else null
    Row(
        Modifier.padding(top = 8.dp).clearAndSetSemantics {},
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AssistantAvatar(avatar, 30.dp, Modifier.graphicsLayer {
            translationY = -(lift?.value ?: 0f) * 3.dp.toPx()
        }.border(1.dp, c.accent.copy(alpha = 0.6f), CircleShape))
        repeat(3) { index ->
            Box(Modifier.size(4.dp).graphicsLayer {
                alpha = if (lift == null) 0.65f else (0.3f + 0.7f *
                    (1f - kotlin.math.abs(lift.value - index / 2f) * 1.25f).coerceAtLeast(0f))
            }.background(c.accent, CircleShape))
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
