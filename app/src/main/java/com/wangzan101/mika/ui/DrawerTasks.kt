package com.wangzan101.mika.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.wangzan101.mika.MikaApp
import com.wangzan101.mika.R
import com.wangzan101.mika.automation.EnvironmentSceneStore
import com.wangzan101.mika.automation.SceneStore
import com.wangzan101.mika.calls.tasks.CallTaskStatus
import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.core.run.RunOrigin
import com.wangzan101.mika.core.trigger.TriggerDefinition
import com.wangzan101.mika.task.TriggerRuntime
import com.wangzan101.mika.ui.theme.MikaIcons
import com.wangzan101.mika.ui.theme.MikaShapes
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.theme.MikaType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

internal enum class DrawerTaskSource { Plan, Bluetooth, Environment, Call }

internal data class DrawerTask(
    val id: String, val title: String, val detail: String, val source: DrawerTaskSource, val badge: DrawerBadge? = null,
)

internal data class DrawerTasks(
    val scheduled: List<DrawerTask> = emptyList(),
    val automation: List<DrawerTask> = emptyList(),
    val calls: List<DrawerTask> = emptyList(),
)

@Composable
internal fun rememberDrawerTasks(open: Boolean): DrawerTasks {
    val context = LocalContext.current
    val app = context.applicationContext as? MikaApp ?: return DrawerTasks()
    if (!open) return DrawerTasks()
    val bluetooth = remember { SceneStore.get(app) }
    val environment = remember { EnvironmentSceneStore.get(app) }
    val bluetoothRules by bluetooth.rules.collectAsState()
    val environmentRules by environment.rules.collectAsState()
    val callTasks by app.calls.tasks.items.collectAsState()
    val runs by app.taskState.runs.collectAsState()
    val running = runs.filter { run ->
        val state by run.state.collectAsState()
        state is TaskState.Running
    }
    val runningPlans = running.mapNotNull { it.triggerWindowKey?.substringBeforeLast(':') }.toSet()
    val runningThreads = running.filter { it.provenance.origin == RunOrigin.Trigger }.map { it.conversationId }.toSet()
    LaunchedEffect(app) {
        withContext(Dispatchers.IO) {
            bluetooth.load()
            environment.load()
            app.calls.tasks.load()
        }
    }
    val snapshot by produceState(emptyList<TriggerDefinition>() to System.currentTimeMillis(), app) {
        while (true) {
            val plans = withContext(Dispatchers.IO) { runCatching { TriggerRuntime.list(app) }.getOrNull() }
            value = (plans ?: value.first) to System.currentTimeMillis()
            delay(10_000)
        }
    }
    val (plans, now) = snapshot
    val (scheduled, repeats) = plans.sortedWith(compareByDescending<TriggerDefinition> { it.enabled }.thenBy { it.nextAtMs })
        .partition { it.intervalMs == null && it.calendarTime == null && it.conditionGoal == null }
    fun planRow(plan: TriggerDefinition) = DrawerTask(
        plan.id, plan.reminderText ?: plan.goal,
        if (plan.enabled) formatPlanTime(plan.nextAtMs) else "", DrawerTaskSource.Plan,
        taskBadge(plan.enabled, plan.id in runningPlans),
    )
    return DrawerTasks(
        scheduled = scheduled.map(::planRow),
        automation = repeats.map(::planRow) + bluetoothRules.map {
            DrawerTask(it.id, it.title, it.deviceName, DrawerTaskSource.Bluetooth,
                taskBadge(it.enabled, it.lastThreadId?.let(runningThreads::contains) == true))
        } + environmentRules.map {
            DrawerTask(it.id, it.title, it.goal, DrawerTaskSource.Environment,
                taskBadge(it.enabled, it.lastThreadId?.let(runningThreads::contains) == true))
        },
        calls = callTasks.sortedWith(compareBy({ it.statusAt(now) == CallTaskStatus.DONE }, { it.dialAtMs ?: it.startMs ?: it.createdAtMs }))
            .map {
                val status = when (it.statusAt(now)) {
                    CallTaskStatus.ACTIVE -> R.string.calls_task_status_active
                    CallTaskStatus.PENDING -> R.string.calls_task_status_pending
                    CallTaskStatus.DONE -> R.string.calls_task_status_done
                    CallTaskStatus.LATER -> R.string.calls_task_status_later
                }
                val detail = it.dialAtMs?.let(::formatPlanTime)
                    ?: it.windowLabel.ifBlank { stringResource(status) }
                DrawerTask(it.id, it.title, detail, DrawerTaskSource.Call)
            },
    )
}

@Composable
internal fun DrawerTaskLabel(
    @StringRes label: Int,
    icon: ImageVector,
    expanded: Boolean,
    count: Int? = null,
    unread: Boolean = false,
    badge: DrawerBadge? = null,
    onToggle: () -> Unit,
) {
    val c = MikaTheme.colors
    val state = stringResource(if (expanded) R.string.drawer_task_expanded else R.string.drawer_task_collapsed)
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp)
            .heightIn(min = 48.dp).clip(MikaShapes.Tile)
            .clickable(role = Role.Button, onClick = onToggle)
            .semantics { stateDescription = state }.padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(icon, null, Modifier.size(18.dp), tint = c.ink2)
        Text(stringResource(label), Modifier.weight(1f), color = c.ink, style = MikaType.Title.copy(fontWeight = FontWeight.Bold))
        badge?.let { DrawerStatusBadge(it) }
        if (unread) Text(stringResource(R.string.drawer_calls_unread), color = c.accent, style = MikaType.Hint)
        if (count != null) Text(count.toString(), color = c.ink3, style = MikaType.Hint)
        Icon(MikaIcons.Chevron, null, Modifier.size(14.dp).rotate(if (expanded) 90f else 0f), tint = c.ink3)
    }
}

internal enum class DrawerBadge(@StringRes val label: Int, val positive: Boolean) {
    Configured(R.string.drawer_config_active, true),
    NoKey(R.string.drawer_config_missing_key, false),
    NotConfigured(R.string.settings_not_set, false),
    Disabled(R.string.plugins_disabled, false),
    Enabled(R.string.plugins_enabled, true),
    Running(R.string.task_running, true),
}

private fun taskBadge(enabled: Boolean, running: Boolean) = when {
    running -> DrawerBadge.Running
    enabled -> DrawerBadge.Enabled
    else -> DrawerBadge.Disabled
}

@Composable
internal fun DrawerStatusBadge(badge: DrawerBadge) {
    val colors = MikaTheme.colors
    val tone = if (badge.positive) colors.ok else colors.bad
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        Box(Modifier.size(7.dp).background(tone, CircleShape))
        Text(stringResource(badge.label), style = MikaType.Hint, color = tone, maxLines = 2)
    }
}

@Composable
internal fun DrawerTaskEntry(title: String, detail: String, badge: DrawerBadge? = null, onClick: () -> Unit) {
    val c = MikaTheme.colors
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp).heightIn(min = 48.dp).clip(MikaShapes.Tile)
            .clickable(role = Role.Button, onClick = onClick).padding(horizontal = 18.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = c.ink, style = MikaType.Caption, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (detail.isNotBlank()) Text(detail, color = c.ink3, style = MikaType.Hint, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        badge?.let { DrawerStatusBadge(it) }
    }
}

@Composable
internal fun DrawerTaskEmpty() {
    Text(stringResource(R.string.drawer_task_empty), Modifier.padding(start = 30.dp, bottom = 8.dp),
        color = MikaTheme.colors.ink3, style = MikaType.Caption)
}
