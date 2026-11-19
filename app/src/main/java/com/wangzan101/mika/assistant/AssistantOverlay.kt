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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import com.wangzan101.mika.ui.theme.MikaShapes
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.wangzan101.mika.R
import com.wangzan101.mika.chat.advanceResearchText
import com.wangzan101.mika.ui.ResearchReplyHaptics
import com.wangzan101.mika.ui.ResearchThinkingOrb
import com.wangzan101.mika.ui.OrbMotion
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
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
    val completed: Boolean = false,
    val motion: OrbMotion = OrbMotion.Idle,
    val progress: String? = null,
    val collapsedBody: String? = null,
)

internal data class AssistantExchange(val question: String, val answer: String, val collapsedAnswer: String? = null)

internal enum class AssistantMode(val label: Int) {
    Auto(R.string.assistant_mode_auto),
    Chat(R.string.assistant_mode_chat),
    Work(R.string.assistant_mode_work),
}

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
    displayName: String = "",
    animateAvatar: Boolean = true,
    history: List<AssistantExchange> = emptyList(),
    pendingQuestion: String? = null,
    revealTurn: Int? = null,
    awaitingReply: Boolean = false,
    sendQueued: Boolean = false,
    mode: AssistantMode = AssistantMode.Auto,
    resolvedMode: AssistantMode? = null,
    onMode: (AssistantMode) -> Unit = {},
) {
    val c = MikaTheme.colors
    val glow by animateFloatAsState(if (listening) 1f else 0.48f, label = "assistant glow")
    val running = reply?.running == true
    val reducedMotion = MikaTheme.reduceMotion
    val revealingWorkDetails = revealTurn?.let { history.getOrNull(it)?.collapsedAnswer != null } == true
    val target = revealTurn?.let { index ->
        history.getOrNull(index)?.answer ?: reply?.body.orEmpty()
    }.orEmpty()
    var revealed by remember(revealTurn) { mutableStateOf("") }
    LaunchedEffect(revealTurn, target, reducedMotion, revealingWorkDetails) {
        if (reducedMotion || revealingWorkDetails) revealed = target
        else while (target != revealed) {
            revealed = advanceResearchText(revealed, target)
            if (target != revealed) delay(24)
        }
    }
    if (!revealingWorkDetails) ResearchReplyHaptics(revealTurn?.toString(), revealed)
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
                .padding(horizontal = 16.dp, vertical = 12.dp).widthIn(max = 540.dp).fillMaxWidth(),
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
                            ResearchThinkingOrb(
                                phase = when {
                                    listening -> OrbMotion.Listening
                                    converting || sending -> OrbMotion.Connecting
                                    reply?.completed == true -> OrbMotion.Complete
                                    else -> reply?.motion ?: OrbMotion.Idle
                                },
                                level = level,
                                diameter = 48.dp,
                                animated = animateAvatar,
                            )
                            Text(displayName.ifBlank { stringResource(R.string.app_name) },
                                style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            val currentStatus = when {
                                listening -> stringResource(R.string.voice_listening)
                                converting -> stringResource(R.string.voice_converting)
                                else -> reply?.title
                            }
                            if (currentStatus != null) Text(currentStatus, color = c.accent, style = MaterialTheme.typography.bodySmall)
                            reply?.progress?.let {
                                Text(it, color = c.ink2, style = MaterialTheme.typography.bodySmall,
                                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
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
                    if (reply != null || history.isNotEmpty()) {
                        val scroll = rememberScrollState()
                        var followLatest by remember { mutableStateOf(true) }
                        val userScroll = remember(scroll) {
                            object : NestedScrollConnection {
                                override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                                    if (source == NestedScrollSource.UserInput && consumed.y != 0f) {
                                        followLatest = !scroll.canScrollForward
                                    }
                                    return Offset.Zero
                                }

                                override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                                    if (consumed.y < 0f && !scroll.canScrollForward) followLatest = true
                                    return Velocity.Zero
                                }
                            }
                        }
                        LaunchedEffect(pendingQuestion) {
                            if (pendingQuestion != null) followLatest = true
                        }
                        LaunchedEffect(scroll) {
                            snapshotFlow { scroll.maxValue to followLatest }.collectLatest { (end, following) ->
                                if (following && end != Int.MAX_VALUE && scroll.value != end) scroll.scrollTo(end)
                            }
                        }
                        Column(
                            Modifier.weight(1f, fill = false).fillMaxWidth().nestedScroll(userScroll).verticalScroll(scroll),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            history.forEachIndexed { index, turn ->
                                AssistantQuestion(turn.question, Modifier.align(Alignment.End))
                                if (turn.collapsedAnswer != null) AssistantWorkAnswer(turn.answer, turn.collapsedAnswer)
                                else if (index == revealTurn) AssistantAnswer(revealed, streaming = revealed != target)
                                else AssistantAnswer(turn.answer)
                            }
                            if (pendingQuestion != null) {
                                AssistantQuestion(pendingQuestion, Modifier.align(Alignment.End))
                                val collapsed = reply?.collapsedBody
                                if (collapsed != null) AssistantWorkAnswer(reply.body, collapsed)
                                else {
                                    val body = if (revealTurn != null) revealed else reply?.body.orEmpty()
                                    AssistantAnswer(body, streaming = reply?.running == true || body != reply?.body.orEmpty())
                                }
                            }
                        }
                        TextButton(onClick = onOpenConversation) {
                            Text(stringResource(R.string.assistant_open_conversation), color = c.accent)
                        }
                    }
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val hasMedia = screenshot != null || photo != null
                        var selectMode by remember { mutableStateOf(false) }
                        Box {
                            TextButton(
                                onClick = { selectMode = true },
                                enabled = !sending && !awaitingReply && !hasMedia,
                            ) {
                                val selected = if (hasMedia) AssistantMode.Work else mode
                                val label = stringResource(selected.label)
                                Text(if (selected == AssistantMode.Auto && resolvedMode != null) {
                                    "$label → ${stringResource(resolvedMode.label)}"
                                } else label)
                            }
                            DropdownMenu(expanded = selectMode, onDismissRequest = { selectMode = false }) {
                                AssistantMode.entries.forEach { option ->
                                    DropdownMenuItem(
                                        text = { Text(stringResource(option.label)) },
                                        onClick = { selectMode = false; onMode(option) },
                                    )
                                }
                            }
                        }
                        if (text.isNotBlank() || hasMedia) {
                            IconButton(onClick = onClear, enabled = !sending, modifier = Modifier.size(36.dp)) {
                                Icon(MikaIcons.Trash, stringResource(R.string.assistant_clear), tint = c.ink2)
                            }
                        }
                    }
                    if (partial.isNotBlank()) {
                        Text(partial, Modifier.padding(horizontal = 10.dp, vertical = 4.dp), color = c.accent,
                            maxLines = 3, style = MaterialTheme.typography.bodyMedium)
                    }
                    if (status != null || sending || sendQueued) {
                        Text(when {
                            sendQueued -> stringResource(R.string.assistant_follow_up_queued)
                            status != null -> status
                            else -> stringResource(R.string.attach_importing)
                        },
                            Modifier.padding(horizontal = 10.dp, vertical = 4.dp), color = c.ink2,
                            style = MaterialTheme.typography.bodySmall)
                    }
                    val canSend = !sending && !sendQueued &&
                        (text.isNotBlank() || partial.isNotBlank() || screenshot != null || photo != null)
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 6.dp)
                            .background(c.fill, RoundedCornerShape(24.dp)).padding(horizontal = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(onClick = onPhoto, enabled = !sending && !sendQueued, modifier = Modifier.size(42.dp)) {
                            Icon(MikaIcons.PhotoCamera, stringResource(R.string.attach_camera))
                        }
                        IconButton(onClick = onScreenshot, enabled = !sending && !sendQueued, modifier = Modifier.size(42.dp)) {
                            Icon(MikaIcons.Camera, stringResource(R.string.assistant_take_screenshot))
                        }
                        BasicTextField(
                            value = text,
                            onValueChange = onText,
                            enabled = !sending && !sendQueued,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                            keyboardActions = KeyboardActions(onSend = { if (canSend) onSend() }),
                            minLines = 1,
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodyLarge.copy(color = c.ink),
                            cursorBrush = SolidColor(c.accent),
                            modifier = Modifier.weight(1f).heightIn(min = 42.dp).padding(horizontal = 4.dp)
                                .onFocusChanged { if (it.isFocused) onEdit() },
                            decorationBox = { field ->
                                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
                                    if (text.isEmpty()) Text(stringResource(R.string.assistant_input), color = c.ink2)
                                    field()
                                }
                            },
                        )
                        IconButton(onClick = onVoice, enabled = !converting && !sending && !sendQueued, modifier = Modifier.size(42.dp)) {
                            Icon(
                                if (listening) MikaIcons.Check else MikaIcons.Mic,
                                stringResource(if (listening) R.string.assistant_stop_listening else R.string.voice_listening),
                                tint = if (listening) c.accent else c.ink,
                            )
                        }
                        IconButton(
                            onClick = onSend,
                            enabled = canSend,
                            modifier = Modifier.size(42.dp),
                        ) {
                            Box(Modifier.size(32.dp).background(if (canSend) c.accent else c.fill, CircleShape),
                                contentAlignment = Alignment.Center) {
                                Icon(MikaIcons.Up, stringResource(R.string.chat_send), Modifier.size(18.dp),
                                    tint = if (canSend) c.onAccent else c.ink2)
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
private fun AssistantQuestion(question: String, modifier: Modifier = Modifier) {
    if (question.isBlank()) return
    val c = MikaTheme.colors
    Text(
        question, modifier.widthIn(max = 300.dp).padding(top = 6.dp)
            .background(c.accent.copy(alpha = 0.14f), MikaShapes.UserBubble).padding(horizontal = 12.dp, vertical = 8.dp),
        style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun AssistantWorkAnswer(answer: String, summary: String) {
    var expanded by rememberSaveable(answer, summary) { mutableStateOf(true) }
    AssistantAnswer(if (expanded) answer else summary)
    TextButton(onClick = { expanded = !expanded }) {
        Text(stringResource(if (expanded) R.string.collapse else R.string.expand))
    }
}

@Composable
private fun AssistantAnswer(answer: String, streaming: Boolean = false) {
    if (answer.isBlank()) return
    val c = MikaTheme.colors
    MarkdownText(
        answer, MaterialTheme.typography.bodyMedium.copy(color = c.ink),
        Modifier.fillMaxWidth().background(c.answer, MikaShapes.AnswerBubble).padding(horizontal = 14.dp, vertical = 12.dp),
        streaming = streaming,
    )
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
