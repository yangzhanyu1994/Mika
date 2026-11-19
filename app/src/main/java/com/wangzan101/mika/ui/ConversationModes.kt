package com.wangzan101.mika.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wangzan101.mika.MikaApp
import com.wangzan101.mika.R
import com.wangzan101.mika.chat.ResearchDraft
import com.wangzan101.mika.chat.ResearchPhase
import com.wangzan101.mika.core.agent.Turn
import com.wangzan101.mika.ui.components.PageBackground
import com.wangzan101.mika.ui.components.MikaCard
import com.wangzan101.mika.ui.components.pageBackground
import com.wangzan101.mika.core.record.ConversationTokens
import com.wangzan101.mika.ui.components.BarIconButton
import com.wangzan101.mika.ui.theme.MikaIcons
import com.wangzan101.mika.ui.theme.MikaShapes
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.theme.MikaType
import com.wangzan101.mika.voice.HoldToTalk
import kotlinx.coroutines.launch
import java.time.LocalTime

@Composable
internal fun ConversationModeHeader(
    chat: Boolean,
    model: String?,
    usage: ConversationTokens?,
    onMode: (Boolean) -> Unit,
    onModel: () -> Unit,
    onOpenDrawer: () -> Unit,
    onNew: (() -> Unit)? = null,
    runningElsewhere: Boolean = false,
    profile: (@Composable () -> Unit)? = null,
    title: String? = null,
    reasoning: (@Composable () -> Unit)? = null,
    conversationId: String? = null,
) {
    val c = MikaTheme.colors
    var menu by remember { mutableStateOf(false) }
    var showUsage by rememberSaveable(conversationId) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().pageBackground(c.bg).statusBarsPadding().padding(bottom = 6.dp)) {
        Box(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp)) {
            Box(Modifier.align(Alignment.CenterStart)) {
                BarIconButton(MikaIcons.Menu, stringResource(R.string.chat_menu), onOpenDrawer, badge = runningElsewhere)
            }
            Box(Modifier.align(Alignment.Center).fillMaxWidth(0.55f), contentAlignment = Alignment.Center) {
                Box {
                    Row(
                        Modifier.clip(MikaShapes.Pill).background(c.surface).border(1.dp, c.hair, MikaShapes.Pill)
                            .clickable(role = Role.Button) { menu = true }
                            .heightIn(min = 44.dp).padding(horizontal = 14.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(title?.takeIf(String::isNotBlank) ?: "Mika", Modifier.weight(1f, fill = false),
                            color = c.ink, style = MikaType.BodyStrong, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Icon(MikaIcons.Chevron, null, Modifier.rotate(90f).heightIn(max = 16.dp), tint = c.ink2)
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, containerColor = c.surface) {
                        listOf(true to R.string.mode_chat, false to R.string.mode_work).forEach { (value, label) ->
                            DropdownMenuItem(text = { Text(stringResource(label)) },
                                trailingIcon = if (value == chat) ({ Icon(MikaIcons.Check, null, tint = c.accent) }) else null,
                                onClick = { menu = false; onMode(value) })
                        }
                        DropdownMenuItem(
                            text = { Text(currentConversationTokenText(usage)) },
                            leadingIcon = { Icon(MikaIcons.Chart, null) },
                            onClick = { menu = false; showUsage = true },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.mode_current_model, model ?: stringResource(R.string.settings_not_set))) },
                            leadingIcon = { Icon(MikaIcons.Sliders, null) },
                            onClick = { menu = false; onModel() },
                        )
                        if (reasoning != null) Box(Modifier.padding(horizontal = 12.dp)) { reasoning() }
                    }
                }
            }
            if (onNew != null) Box(Modifier.align(Alignment.CenterEnd)) {
                BarIconButton(MikaIcons.New, stringResource(R.string.mode_new_thread), onNew)
            }
        }
        profile?.invoke()
    }
    if (showUsage) ConversationUsageSheet(conversationId, usage?.tokens) { showUsage = false }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ConversationModeGuidance(chat: Boolean, onPrompt: (String) -> Unit) {
    val c = MikaTheme.colors
    val hour = remember { LocalTime.now().hour }
    val prompts = if (chat) listOf(R.string.mode_chat_prompt1, R.string.mode_chat_prompt2, R.string.mode_chat_prompt3)
        else listOf(R.string.mode_work_prompt1, R.string.mode_work_prompt2, R.string.mode_work_prompt3)
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(ChatCopy.greeting(hour), style = MikaType.LargeTitle, color = c.ink, textAlign = TextAlign.Center)
        Text(
            stringResource(if (chat) R.string.mode_chat_description else R.string.mode_work_description),
            color = c.ink2, style = MikaType.Body, textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 12.dp, bottom = 24.dp),
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            prompts.forEach { res ->
                val prompt = stringResource(res)
                Text(
                    prompt, Modifier.clip(MikaShapes.Pill).background(c.surface).border(1.dp, c.hair, MikaShapes.Pill)
                        .clickable(role = Role.Button) { onPrompt(prompt) }.padding(horizontal = 14.dp, vertical = 10.dp),
                    color = c.ink2, style = MikaType.Caption, textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
internal fun ResearchConversationScreen(
    app: MikaApp,
    id: String?,
    model: String?,
    onOpenDrawer: () -> Unit,
    onNew: () -> Unit,
    onMode: (Boolean) -> Unit,
    onModel: () -> Unit,
    onSend: suspend (String) -> Boolean,
    onTransfer: (Turn) -> Unit,
    onPickSpeech: () -> Unit,
) {
    val c = MikaTheme.colors
    val loaded = rememberModeTurns(app, id)
    val stored = loaded.orEmpty().filter { it.research }
    val pending by app.researchChat.pending.collectAsState()
    val working = id?.let(pending::get)?.takeUnless { live ->
        stored.lastOrNull()?.atMs == live.startedAt
    }
    ResearchReplyHaptics(id, working?.text.orEmpty())
    val scroll = rememberLazyListState()
    var share by remember { mutableStateOf<ShareRequest?>(null) }
    if (share != null) ShareCardHost(share, onDone = { share = null })
    LaunchedEffect(working?.startedAt) {
        if (working != null) scroll.scrollToItem(0)
    }
    var draft by rememberSaveable { mutableStateOf("") }
    var routing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    fun send() {
        val submitted = draft
        if (submitted.isBlank() || routing || id?.let { it in app.researchChat.pending.value } == true) return
        routing = true
        scope.launch {
            try {
                if (onSend(submitted.trim()) && draft == submitted) draft = ""
            } finally {
                routing = false
            }
        }
    }
    PageBackground {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.navigationBars).imePadding()) {
            val usage = rememberModeConversationUsage(app, id, loaded)
            ConversationModeHeader(true, model, usage, onMode, onModel, onOpenDrawer, onNew,
                title = id?.let(app.conversations::title), conversationId = id, reasoning = {
                    ConversationReasoning(app.providers, com.wangzan101.mika.core.model.ModelRole.Chat)
                })
            LazyColumn(
                Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp),
                state = scroll,
                reverseLayout = true,
                verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.Top),
            ) {
                if (working != null) item {
                    ResearchPendingReply(working)
                }
                if (stored.isEmpty() && working == null) item {
                    Box(Modifier.fillParentMaxHeight().fillMaxWidth(), contentAlignment = Alignment.Center) {
                        ConversationModeGuidance(true) { draft = it }
                    }
                }
                items(stored.asReversed()) { turn ->
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        ResearchQuestion(turn.userText, Modifier.align(Alignment.End))
                        ResearchResultCard(app, id, turn, onShare = { share = ShareRequest(turn.userText, turn.outcome) })
                        if (turn.status == "succeeded") {
                            TextButton(onClick = { onTransfer(turn) }) {
                                Text(stringResource(R.string.mode_transfer), color = c.accent)
                            }
                        }
                    }
                }
            }
            Composer(
                modifier = Modifier,
                text = draft,
                onText = { draft = it },
                mode = ComposerMode.Idle,
                hasHistory = stored.isNotEmpty(),
                idlePlaceholder = stringResource(R.string.mode_chat_input),
                onSend = ::send,
                onStop = {},
                reduceMotion = MikaTheme.reduceMotion,
                voice = app.voice,
                onVoiceText = { draft = HoldToTalk.join(draft, it) },
                onVoiceSend = { words ->
                    draft = HoldToTalk.join(draft, words)
                    send()
                },
                onPickSpeech = onPickSpeech,
                draftKey = id,
                sendEnabled = !routing && id?.let(pending::containsKey) != true,
            )
        }
    }
}

@Composable
internal fun ResearchPendingReply(draft: ResearchDraft) {
    val c = MikaTheme.colors
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        ResearchQuestion(draft.question, Modifier.align(Alignment.End))
        if (draft.text.isEmpty()) {
            val searching = draft.phase == ResearchPhase.Searching
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ResearchThinkingOrb(draft.phase.orbMotion)
                Text(
                    stringResource(if (searching) R.string.assistant_searching else R.string.assistant_thinking),
                    style = MikaType.Caption, color = c.ink2,
                )
            }
        } else {
            ResearchThinkingOrb(draft.phase.orbMotion, 20.dp)
            ResearchAnswer(draft.text, streaming = true)
        }
    }
}

@Composable
private fun ResearchResultCard(app: MikaApp, conversationId: String?, turn: Turn, onShare: () -> Unit) {
    val c = MikaTheme.colors
    val clipboard = LocalClipboardManager.current
    MikaCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 11.dp)) {
            MarkdownText(turn.outcome, MikaType.Body.copy(color = c.ink, lineHeight = 24.sp))
            OutputRefs(turn.outcome, conversationId)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                BarIconButton(MikaIcons.Copy, stringResource(R.string.chat_copy),
                    { clipboard.setText(AnnotatedString(turn.outcome)) })
                BarIconButton(MikaIcons.Share, stringResource(R.string.share), onShare)
                ReadAloudIcon(turn.outcome, app.chat)
            }
        }
    }
}

@Composable
private fun ResearchAnswer(text: String, streaming: Boolean = false) {
    val c = MikaTheme.colors
    MarkdownText(
        text, MikaType.Body.copy(color = c.ink, lineHeight = 24.sp),
        Modifier.fillMaxWidth().background(c.answer, MikaShapes.AnswerBubble)
            .padding(horizontal = 14.dp, vertical = 11.dp),
        streaming = streaming,
    )
}

@Composable
private fun ResearchQuestion(question: String, modifier: Modifier = Modifier) {
    val c = MikaTheme.colors
    Text(question, modifier.background(c.accent.copy(alpha = 0.14f), MikaShapes.UserBubble).padding(10.dp),
        style = MikaType.Body, color = c.ink)
}
