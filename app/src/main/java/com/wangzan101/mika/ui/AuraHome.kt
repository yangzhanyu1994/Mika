package com.wangzan101.mika.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.wangzan101.mika.R
import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.task.TaskCenterState
import com.wangzan101.mika.ui.components.BarIconButton
import com.wangzan101.mika.ui.components.MikaLogo
import com.wangzan101.mika.ui.components.DirectionalIcon
import com.wangzan101.mika.ui.components.ProgressDots
import com.wangzan101.mika.ui.components.pageBackground
import com.wangzan101.mika.ui.theme.MikaIcons
import com.wangzan101.mika.ui.theme.MikaShapes
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.theme.MikaType

data class AuraTask(val conversation: String, val goal: String, val state: TaskState, val runId: String?) {
    val needsYou: Boolean get() = when (val current = state) {
        is TaskState.WaitingConfirmation, is TaskState.WaitingTakeover, is TaskState.WaitingInput -> true
        is TaskState.Held -> current.reason.needsYou
        else -> false
    }
}

@Composable
internal fun auraTasks(center: TaskCenterState): List<AuraTask> {
    val runs by center.runs.collectAsState()
    val tasks = ArrayList<AuraTask>(runs.size)
    for (run in runs) key(run.key) {
        val state by run.state.collectAsState()
        val runId by run.runId.collectAsState()
        if (!run.conditionCheck && state != TaskState.Idle && state !is TaskState.Terminal) {
            tasks += AuraTask(run.conversationId, run.goal, state, runId)
        }
    }
    return tasks
}

@Composable
internal fun AuraHeader(openDrawer: () -> Unit, newTask: () -> Unit, canStart: Boolean, hasTasks: Boolean) {
    val c = MikaTheme.colors
    Row(
        Modifier.fillMaxWidth().pageBackground(c.bg).windowInsetsPadding(WindowInsets.statusBars)
            .heightIn(min = 56.dp).padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        BarIconButton(MikaIcons.Menu, stringResource(R.string.chat_menu), openDrawer, badge = hasTasks)
        MikaLogo(29.dp)
        Text(stringResource(R.string.app_name), Modifier.weight(1f), style = MikaType.Title, color = c.ink)
        BarIconButton(MikaIcons.Plus, stringResource(R.string.aura_new), newTask, enabled = canStart)
    }
}

@Composable
internal fun AuraHomeContent(
    tasks: List<AuraTask>,
    recent: Exchange?,
    voiceAvailable: Boolean,
    modelReady: Boolean,
    reduceMotion: Boolean,
    onVoice: () -> Unit,
    onOpenTask: (AuraTask) -> Unit,
    onOpenRecent: () -> Unit,
    onAll: () -> Unit,
    onPlan: () -> Unit,
    onScenes: () -> Unit,
    onCalls: () -> Unit,
    onSetup: () -> Unit,
    onExample: (String) -> Unit,
) {
    val c = MikaTheme.colors
    Column(Modifier.fillMaxWidth().padding(top = 22.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        val current = tasks.firstOrNull { it.needsYou } ?: tasks.firstOrNull { it.state is TaskState.Running } ?: tasks.firstOrNull()
        when {
            current != null -> {
                TaskOverview(current, reduceMotion) { onOpenTask(current) }
                if (tasks.size > 1) {
                    TextButton(onClick = onAll, modifier = Modifier.align(Alignment.End)) {
                        Text(stringResource(R.string.aura_all))
                    }
                }
            }
            recent != null && !recent.status.isActive() -> {
                val label = when (val status = recent.status) {
                    is ExchangeStatus.Done -> if (status.unverifiedLine == null) R.string.aura_finished else R.string.aura_result_unconfirmed
                    is ExchangeStatus.Cancelled -> R.string.aura_stopped
                    else -> R.string.aura_incomplete
                }
                AuraRow(stringResource(label), ChatCopy.plainTitle(recent.goal), onOpen = onOpenRecent)
            }
            else -> {
                Column(
                    Modifier.fillMaxWidth().padding(vertical = 13.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    MikaLogo(58.dp)
                    Text(stringResource(R.string.aura_home_title), style = MikaType.LargeTitle, color = c.ink, textAlign = TextAlign.Center)
                    if (modelReady && voiceAvailable) {
                        Row(
                            Modifier.clip(MikaShapes.Pill).background(c.accent)
                                .clickable(role = Role.Button, onClick = onVoice)
                                .heightIn(min = 48.dp).padding(horizontal = 24.dp)
                                .testTag("aura-voice"),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(MikaIcons.Mic, null, Modifier.size(20.dp), tint = c.onAccent)
                            Text(stringResource(R.string.aura_speak), style = MikaType.BodyStrong, color = c.onAccent)
                        }
                    } else if (!modelReady) {
                        TextButton(onClick = onSetup) { Text(stringResource(R.string.aura_model_setup)) }
                    }
                }
                AuraExamples(onExample, onCalls, onScenes)
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AuraShortcut(MikaIcons.Clock, R.string.drawer_plans, Modifier.weight(1f), onPlan)
            AuraShortcut(MikaIcons.Screens, R.string.scene_title, Modifier.weight(1f), onScenes)
            AuraShortcut(MikaIcons.Phone, R.string.calls_title, Modifier.weight(1f), onCalls)
        }
    }
}

@Composable
private fun TaskOverview(task: AuraTask, reduceMotion: Boolean, onOpen: () -> Unit) {
    val c = MikaTheme.colors
    val state = task.state
    val label = when (state) {
        is TaskState.WaitingInput -> R.string.input_title
        is TaskState.WaitingConfirmation -> R.string.aura_confirm
        is TaskState.WaitingTakeover -> R.string.takeover_title
        is TaskState.Held -> if (state.reason.needsYou) R.string.takeover_title else if (state.reason.queued) R.string.aura_queued else R.string.chat_paused
        else -> R.string.drawer_running
    }
    val detail = when (state) {
        is TaskState.Running -> state.narration?.takeIf(String::isNotBlank)
            ?: (state.progress as? com.wangzan101.mika.core.agent.RunProgress.Task)?.display
            ?: state.reason?.takeIf(String::isNotBlank) ?: stringResource(R.string.drawer_running)
        is TaskState.WaitingInput -> state.prompt
        is TaskState.WaitingTakeover -> state.message
        is TaskState.Held -> state.reason.why
        else -> ""
    }
    val action = when (state) {
        is TaskState.WaitingInput -> R.string.aura_reply
        is TaskState.WaitingConfirmation -> R.string.aura_confirm
        is TaskState.WaitingTakeover -> R.string.aura_takeover
        else -> R.string.aura_progress
    }
    val title = ChatCopy.plainTitle(task.goal).ifBlank { stringResource(R.string.drawer_running_task) }
    val high = task.needsYou
    val ink = if (high) Color(0xFFF1F7FF) else c.ink
    val accent = if (high) Color(0xFF9EDAFF) else c.accent
    Column(
        Modifier.fillMaxWidth().clip(MikaShapes.Card)
            .background(if (high) Color(0xFF112743) else c.surface)
            .padding(18.dp).testTag("aura-status"),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (state is TaskState.Running) Spinner(Modifier.size(18.dp), !reduceMotion, c.hair, c.accent)
            Text(stringResource(label), style = MikaType.Label, color = accent)
        }
        Text(title, style = MikaType.Title, color = ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (detail.isNotBlank()) Text(detail, style = MikaType.Caption, color = ink.copy(alpha = 0.76f),
            maxLines = 2, overflow = TextOverflow.Ellipsis)
        TextButton(onClick = onOpen, contentPadding = PaddingValues(horizontal = 4.dp)) {
            Text(stringResource(action), color = accent)
        }
    }
}

@Composable
private fun AuraRow(label: String, text: String, action: String? = null, onOpen: () -> Unit) {
    val c = MikaTheme.colors
    Row(
        Modifier.fillMaxWidth().clip(MikaShapes.Card).background(c.surface)
            .clickable(role = Role.Button, onClick = onOpen).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MikaType.Label, color = c.ink2)
            Text(text, style = MikaType.BodyStrong, color = c.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (action != null) Text(action, style = MikaType.Caption, color = c.accent, modifier = Modifier.padding(top = 4.dp))
        }
        DirectionalIcon(MikaIcons.Chevron, null, Modifier.size(16.dp), tint = c.ink2)
    }
}

@Composable
private fun AuraShortcut(icon: ImageVector, name: Int, modifier: Modifier, onClick: () -> Unit) {
    val c = MikaTheme.colors
    Column(
        modifier.clip(RoundedCornerShape(16.dp)).background(c.surface)
            .clickable(role = Role.Button, onClick = onClick).heightIn(min = 70.dp).padding(horizontal = 2.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically),
    ) {
        Icon(icon, null, Modifier.size(21.dp), tint = c.ink2)
        Text(stringResource(name), style = MikaType.Caption, color = c.ink, textAlign = TextAlign.Center, maxLines = 2)
    }
}

@Composable
private fun AuraExamples(onExample: (String) -> Unit, onCalls: () -> Unit, onScenes: () -> Unit) {
    val pager = rememberPagerState(pageCount = { 4 })
    val c = MikaTheme.colors
    Column(Modifier.fillMaxWidth().testTag("aura-examples"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(stringResource(R.string.aura_examples), style = MikaType.BodyStrong, color = c.ink)
            Text(stringResource(R.string.aura_swipe), style = MikaType.Hint, color = c.ink2)
        }
        HorizontalPager(state = pager, contentPadding = PaddingValues(end = 30.dp), pageSpacing = 8.dp) { page ->
            val title = stringResource(when (page) {
                0 -> R.string.aura_phone
                1, 2 -> R.string.calls_title
                else -> R.string.scene_title
            })
            val words = stringResource(when (page) {
                0 -> R.string.aura_phone_example
                1 -> R.string.aura_call_example
                2 -> R.string.aura_answer_example
                else -> R.string.aura_scene_example
            })
            val label = stringResource(when (page) {
                2 -> R.string.aura_setup
                3 -> R.string.aura_setup
                else -> R.string.aura_try
            })
            AuraRow(title, words, action = label) {
                when (page) {
                    2 -> onCalls()
                    3 -> onScenes()
                    else -> onExample(words)
                }
            }
        }
        ProgressDots(4, pager.currentPage)
    }
}
