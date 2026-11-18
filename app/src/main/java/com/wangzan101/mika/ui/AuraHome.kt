package com.wangzan101.mika.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
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
import com.wangzan101.mika.ui.components.DirectionalIcon
import com.wangzan101.mika.ui.components.ProgressDots
import com.wangzan101.mika.ui.components.pageBackground
import com.wangzan101.mika.ui.theme.MikaIcons
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
        Text(stringResource(R.string.app_name), Modifier.weight(1f), style = MikaType.Title, color = c.ink)
        BarIconButton(MikaIcons.Plus, stringResource(R.string.aura_new), newTask, enabled = canStart)
    }
}

@Composable
internal fun AuraHomeContent(
    tasks: List<AuraTask>,
    recent: Exchange?,
    recentSuccess: Boolean,
    modelReady: Boolean,
    reduceMotion: Boolean,
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
    val current = tasks.firstOrNull { it.needsYou } ?: tasks.firstOrNull { it.state is TaskState.Running } ?: tasks.firstOrNull()
    val result = recent?.status?.takeUnless { it.isActive() }
    val mood = when {
        current?.state is TaskState.Running || (current == null && recent?.status == ExchangeStatus.Working) -> AuraOrbMood.Working
        current == null && recentSuccess -> AuraOrbMood.Succeeded
        else -> AuraOrbMood.Idle
    }
    Column(
        Modifier.fillMaxWidth().padding(top = 26.dp, bottom = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        AuraOrb(mood, reduceMotion)
        when {
            current != null -> {
                TaskOverview(current, reduceMotion) { onOpenTask(current) }
                if (tasks.size > 1) TextButton(onClick = onAll) { Text(stringResource(R.string.aura_all)) }
            }
            result != null -> {
                val label = when (result) {
                    is ExchangeStatus.Done -> if (result.unverifiedLine == null) R.string.aura_completed else R.string.aura_result_unconfirmed
                    is ExchangeStatus.Cancelled -> R.string.aura_stopped
                    else -> R.string.aura_incomplete
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(stringResource(label), style = MikaType.Title, color = c.ink, textAlign = TextAlign.Center)
                    TextButton(onClick = onOpenRecent) { Text(stringResource(R.string.aura_finished), color = c.ink2) }
                }
            }
            recent?.status == ExchangeStatus.Working -> {
                Text(ChatCopy.plainTitle(recent.goal), style = MikaType.Title, color = c.ink, textAlign = TextAlign.Center)
                TextButton(onClick = onOpenRecent) { Text(stringResource(R.string.aura_progress), color = c.ink2) }
            }
            recentSuccess -> Text(stringResource(R.string.aura_completed), style = MikaType.Title, color = c.ink)
            else -> {
                Text(stringResource(R.string.aura_standby), style = MikaType.Title, color = c.ink)
                if (!modelReady) TextButton(onClick = onSetup) { Text(stringResource(R.string.aura_model_setup)) }
            }
        }
        Spacer(Modifier.height(8.dp))
        AuraExamples(onExample, onCalls, onScenes)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AuraShortcut(MikaIcons.Clock, R.string.drawer_plans, Modifier.weight(1f), onPlan)
            AuraShortcut(MikaIcons.Layers, R.string.scene_title, Modifier.weight(1f), onScenes)
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
        Modifier.fillMaxWidth().testTag("aura-status"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (state is TaskState.Running) Spinner(Modifier.size(18.dp), !reduceMotion, c.hair, c.accent)
            Text(stringResource(label), style = MikaType.Label, color = accent)
        }
        Text(title, style = MikaType.Title, color = ink, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
        if (detail.isNotBlank()) Text(detail, style = MikaType.Caption, color = ink.copy(alpha = 0.76f),
            maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
        TextButton(onClick = onOpen, contentPadding = PaddingValues(horizontal = 4.dp)) {
            Text(stringResource(action), color = accent)
        }
    }
}

@Composable
private fun AuraRow(label: String, text: String, action: String? = null, onOpen: () -> Unit) {
    val c = MikaTheme.colors
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(15.dp)).background(c.surface)
            .border(1.dp, c.edge, RoundedCornerShape(15.dp))
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
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier.clip(shape).background(c.surface).border(1.dp, c.edge, shape)
            .clickable(role = Role.Button, onClick = onClick).heightIn(min = 44.dp).padding(6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp, Alignment.CenterHorizontally),
    ) {
        Icon(icon, null, Modifier.size(16.dp), tint = c.ink)
        Text(stringResource(name), style = MikaType.Hint, color = c.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
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
