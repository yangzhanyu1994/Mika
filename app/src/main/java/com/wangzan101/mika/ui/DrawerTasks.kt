package com.wangzan101.mika.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.wangzan101.mika.MikaApp
import com.wangzan101.mika.R
import com.wangzan101.mika.automation.EnvironmentSceneStore
import com.wangzan101.mika.automation.SceneStore
import com.wangzan101.mika.calls.tasks.CallTaskStatus
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
    val id: String, val title: String, val detail: String, val source: DrawerTaskSource,
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
    val off = stringResource(R.string.chat_paused)
    val (scheduled, repeats) = plans.sortedWith(compareByDescending<TriggerDefinition> { it.enabled }.thenBy { it.nextAtMs })
        .partition { it.intervalMs == null && it.calendarTime == null && it.conditionGoal == null }
    fun planRow(plan: TriggerDefinition) = DrawerTask(
        plan.id, plan.reminderText ?: plan.goal,
        if (plan.enabled) formatPlanTime(plan.nextAtMs) else off, DrawerTaskSource.Plan,
    )
    return DrawerTasks(
        scheduled = scheduled.map(::planRow),
        automation = repeats.map(::planRow) + bluetoothRules.map {
            DrawerTask(it.id, it.title, if (it.enabled) it.deviceName else off, DrawerTaskSource.Bluetooth)
        } + environmentRules.map {
            DrawerTask(it.id, it.title, if (it.enabled) it.goal else off, DrawerTaskSource.Environment)
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
    expanded: Boolean,
    count: Int? = null,
    unread: Boolean = false,
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
        Text(stringResource(label), Modifier.weight(1f), color = c.ink, style = MikaType.Label)
        if (unread) Text(stringResource(R.string.drawer_calls_unread), color = c.accent, style = MikaType.Hint)
        if (count != null) Text(count.toString(), color = c.ink3, style = MikaType.Hint)
        Icon(MikaIcons.Chevron, null, Modifier.size(14.dp).rotate(if (expanded) 90f else 0f), tint = c.ink3)
    }
}

@Composable
internal fun DrawerTaskEntry(title: String, detail: String, onClick: () -> Unit) {
    val c = MikaTheme.colors
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp).heightIn(min = 48.dp).clip(MikaShapes.Tile)
            .clickable(role = Role.Button, onClick = onClick).padding(horizontal = 18.dp, vertical = 7.dp),
    ) {
        Text(title, color = c.ink, style = MikaType.Body, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (detail.isNotBlank()) Text(detail, color = c.ink3, style = MikaType.Hint, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
internal fun DrawerTaskEmpty() {
    Text(stringResource(R.string.drawer_task_empty), Modifier.padding(start = 30.dp, bottom = 8.dp),
        color = MikaTheme.colors.ink3, style = MikaType.Caption)
}
