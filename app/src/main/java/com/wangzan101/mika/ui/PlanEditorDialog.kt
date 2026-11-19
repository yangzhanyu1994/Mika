package com.wangzan101.mika.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.wangzan101.mika.R
import com.wangzan101.mika.core.trigger.ConditionNotify
import com.wangzan101.mika.core.trigger.PlanDraft
import com.wangzan101.mika.core.trigger.PlanRepeat
import com.wangzan101.mika.core.trigger.PlanValidation
import com.wangzan101.mika.core.trigger.PlanValidationException
import com.wangzan101.mika.core.trigger.TriggerDefinition
import com.wangzan101.mika.core.trigger.editedTrigger
import com.wangzan101.mika.core.trigger.formatPlanEditorTime
import com.wangzan101.mika.core.trigger.newPlanDraft
import com.wangzan101.mika.core.trigger.toPlanDraft
import com.wangzan101.mika.core.trigger.upcoming
import com.wangzan101.mika.i18n.tr
import com.wangzan101.mika.i18n.uiLocale
import com.wangzan101.mika.ui.components.ButtonKind
import com.wangzan101.mika.ui.components.ButtonSize
import com.wangzan101.mika.ui.components.KeyboardDismissArea
import com.wangzan101.mika.ui.components.MikaButton
import com.wangzan101.mika.ui.components.MikaDialog
import com.wangzan101.mika.ui.components.MikaSwitch
import com.wangzan101.mika.ui.components.SegmentedControl
import com.wangzan101.mika.ui.components.keepsKeyboard
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.theme.MikaType
import java.time.DayOfWeek
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.UUID

private enum class PlanClockPick { First, Recurring, Add }

/** 新建和编辑共用一份草稿；预览不另猜时间，直接显示将要保存的计划。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PlanEditorDialog(
    original: TriggerDefinition?,
    newThreadId: String,
    onDismiss: () -> Unit,
    onSave: (TriggerDefinition) -> Unit,
    voice: com.wangzan101.mika.voice.VoiceSource? = null,
    onParse: (suspend (String, PlanDraft) -> PlanDraft?)? = null,
    onPickSpeech: () -> Unit = {},
) {
    val newId = remember(original?.id) { original?.id ?: UUID.randomUUID().toString() }
    var draft by remember(original?.id) {
        mutableStateOf(original?.toPlanDraft() ?: newPlanDraft(System.currentTimeMillis()))
    }
    var sendWechat by remember(original?.id) { mutableStateOf(original?.sendWechat ?: false) }
    var attempted by remember(original?.id) { mutableStateOf(false) }
    var parsing by remember { mutableStateOf(false) }
    var parseNote by remember { mutableStateOf<String?>(null) }
    var pickClock by remember { mutableStateOf<PlanClockPick?>(null) }
    val scope = rememberCoroutineScope()
    val candidate = runCatching {
        editedTrigger(draft, original, newId, newThreadId, System.currentTimeMillis())
    }
    val preview = candidate.getOrNull()

    pickClock?.let { pick ->
        val times = draft.clock.split(',', '，', '、', ';', '；', '،', '\n').map(String::trim).filter(String::isNotEmpty)
        val first = draft.firstAt.substringAfterLast(' ', "")
        val currentText = if (pick == PlanClockPick.First) first else times.firstOrNull().orEmpty()
        val current = runCatching { LocalTime.parse(currentText) }.getOrDefault(LocalTime.of(9, 0))
        ClockWheelDialog(tr(R.string.clock_pick), current.hour * 60 + current.minute, { pickClock = null }) { minute ->
            val chosen = formatClockMinute(minute)
            draft = when (pick) {
                PlanClockPick.First -> {
                    val date = draft.firstAt.substringBefore(' ').takeIf { it.matches(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}")) }
                        ?: newPlanDraft(System.currentTimeMillis(), runCatching { ZoneId.of(draft.zone) }
                            .getOrDefault(ZoneId.systemDefault())).firstAt.substringBefore(' ')
                    draft.copy(firstAt = "$date $chosen")
                }
                PlanClockPick.Recurring -> draft.copy(clock = (listOf(chosen) + times.drop(1)).distinct().joinToString(", "))
                PlanClockPick.Add -> draft.copy(clock = (times + chosen).distinct().joinToString(", "))
            }
        }
    }

    DialogFollowsIme()
    MikaDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.windowInsetsPadding(
            WindowInsets.statusBars.union(WindowInsets.displayCutout)
                .union(WindowInsets.navigationBars).union(WindowInsets.ime),
        ),
        properties = DialogProperties(decorFitsSystemWindows = false),
        title = { Text(tr(if (original == null) R.string.plans_create_title else R.string.plans_edit_title)) },
        text = {
            Column(Modifier.fillMaxWidth()) {
                KeyboardDismissArea(Modifier.weight(1f, fill = false)) {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                    parseNote?.let {
                        Text(it, style = MikaType.Caption, color = MikaTheme.colors.ink3)
                        Spacer(Modifier.height(10.dp))
                    }
                    SegmentedControl(
                        listOf(tr(R.string.plans_task_mode), tr(R.string.plans_reminder_mode)),
                        if (draft.reminder) 1 else 0,
                        onSelect = { selected ->
                            draft = draft.copy(
                                reminder = selected == 1,
                                condition = if (selected == 1) "" else draft.condition,
                                cooldownMinutes = if (selected == 1) "" else draft.cooldownMinutes,
                            )
                        },
                        tags = listOf("plan-editor-task", "plan-editor-reminder"),
                    )
                    Spacer(Modifier.height(10.dp))
                    PlanField(
                        draft.content,
                        { draft = draft.copy(content = it) },
                        tr(if (draft.reminder) R.string.plans_content_reminder else R.string.plans_content_task),
                        "plan-editor-content",
                        singleLine = false,
                    )
                    Spacer(Modifier.height(10.dp))
                    SegmentedControl(
                        listOf(
                            tr(R.string.plans_once), tr(R.string.plans_repeat_interval),
                            tr(R.string.plans_every_day), tr(R.string.plans_repeat_weekly),
                        ),
                        draft.repeat.ordinal,
                        onSelect = { selected ->
                            val repeat = PlanRepeat.entries[selected]
                            val today = runCatching { ZoneId.of(draft.zone) }.getOrElse { ZoneId.systemDefault() }
                                .let { Instant.now().atZone(it).dayOfWeek.value }
                            draft = draft.copy(
                                repeat = repeat,
                                weekdays = if (repeat == PlanRepeat.Weekly && draft.weekdays.isEmpty()) setOf(today)
                                else draft.weekdays,
                            )
                        },
                        tags = listOf("plan-editor-once", "plan-editor-interval", "plan-editor-daily", "plan-editor-weekly"),
                    )
                    Spacer(Modifier.height(10.dp))
                    if (draft.repeat in setOf(PlanRepeat.Once, PlanRepeat.Interval)) {
                        PlanField(
                            draft.firstAt, { draft = draft.copy(firstAt = it) },
                            tr(R.string.plans_edit_label), "plan-editor-first-at",
                        )
                        TextButton(onClick = { pickClock = PlanClockPick.First }) { Text(tr(R.string.clock_pick)) }
                        Spacer(Modifier.height(10.dp))
                    }
                    if (draft.repeat == PlanRepeat.Interval) {
                        PlanField(
                            draft.intervalMinutes, { draft = draft.copy(intervalMinutes = it) },
                            tr(R.string.plans_interval_minutes), "plan-editor-interval-minutes", KeyboardType.Number,
                        )
                        Spacer(Modifier.height(10.dp))
                    }
                    if (draft.repeat in setOf(PlanRepeat.Daily, PlanRepeat.Weekly)) {
                        PlanField(
                            draft.clock, { draft = draft.copy(clock = it) },
                            tr(R.string.plans_clock), "plan-editor-clock",
                            placeholder = "08:00, 12:30, 20:00",
                        )
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { pickClock = PlanClockPick.Recurring }) { Text(tr(R.string.clock_pick)) }
                            TextButton(onClick = { pickClock = PlanClockPick.Add }) { Text(tr(R.string.clock_add)) }
                        }
                        Spacer(Modifier.height(10.dp))
                    }
                    if (draft.repeat == PlanRepeat.Weekly) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            (1..7).forEach { day ->
                                MikaButton(
                                    DayOfWeek.of(day).getDisplayName(TextStyle.SHORT, uiLocale()),
                                    onClick = {
                                        draft = draft.copy(weekdays = draft.weekdays.toMutableSet().apply {
                                            if (!add(day)) remove(day)
                                        })
                                    },
                                    modifier = Modifier.testTag("plan-editor-weekday-$day"),
                                    kind = if (day in draft.weekdays) ButtonKind.Primary else ButtonKind.Tonal,
                                    size = ButtonSize.Small,
                                )
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                    }
                    PlanField(
                        draft.zone, { draft = draft.copy(zone = it) },
                        tr(R.string.plans_zone), "plan-editor-zone",
                    )
                    if (!draft.reminder) {
                        Spacer(Modifier.height(10.dp))
                        PlanField(
                            draft.condition, { draft = draft.copy(condition = it) },
                            tr(R.string.plans_condition_optional), "plan-editor-condition", singleLine = false,
                        )
                        if (draft.condition.isNotBlank()) {
                            Spacer(Modifier.height(10.dp))
                            SegmentedControl(
                                listOf(tr(R.string.plans_notify_change), tr(R.string.plans_notify_each)),
                                if (draft.notifyWhen == ConditionNotify.BecomesMet) 0 else 1,
                                onSelect = {
                                    draft = draft.copy(notifyWhen = if (it == 0) ConditionNotify.BecomesMet else ConditionNotify.EachTimeMet)
                                },
                                tags = listOf("plan-editor-notify-change", "plan-editor-notify-each"),
                            )
                            Spacer(Modifier.height(10.dp))
                            PlanField(
                                draft.cooldownMinutes, { draft = draft.copy(cooldownMinutes = it) },
                                tr(R.string.plans_cooldown_minutes), "plan-editor-cooldown", KeyboardType.Number,
                            )
                        }
                    }
                    if (!draft.reminder) PlanSwitch(tr(R.string.scene_send_wechat), sendWechat, { sendWechat = it }, "plan-editor-wechat")
                    PlanSwitch(tr(R.string.plans_when_idle), draft.whenIdle, { draft = draft.copy(whenIdle = it) }, "plan-editor-idle")
                    PlanSwitch(tr(R.string.plans_enabled), draft.enabled, { draft = draft.copy(enabled = it) }, "plan-editor-enabled")

                    preview?.let { plan ->
                        Spacer(Modifier.height(8.dp))
                        Text(tr(R.string.plans_preview), style = MikaType.Caption, color = MikaTheme.colors.ink3)
                        val zone = plan.calendarZone ?: draft.zone
                        upcoming(plan, System.currentTimeMillis(), 3).forEachIndexed { index, at ->
                            Text(
                                "${index + 1}  ${formatPlanEditorTime(at, zone)} ($zone)",
                                Modifier.testTag("plan-editor-preview-$index"),
                                style = MikaType.Caption,
                                color = MikaTheme.colors.ink3,
                            )
                        }
                    }
                    if (attempted && preview == null) {
                        val reason = (candidate.exceptionOrNull() as? PlanValidationException)?.reason
                        Spacer(Modifier.height(8.dp))
                        Text(
                            reason?.let { planValidationText(it) } ?: tr(R.string.op_failed),
                            Modifier.testTag("plan-editor-error"),
                            style = MikaType.Caption,
                            color = MikaTheme.colors.bad,
                        )
                    }
                }
            }
                if (voice != null || onParse != null) {
                    UtteranceBar(
                        voice,
                        InputHints.plan,
                        parsing,
                        onUtterance = { words ->
                            val parse = onParse ?: return@UtteranceBar
                            parsing = true
                            parseNote = null
                            scope.launch {
                                val next = runCatching { parse(words, draft) }.getOrNull()
                                parsing = false
                                if (next == null) parseNote = tr(R.string.plans_parse_failed)
                                else {
                                    draft = next
                                    parseNote = tr(R.string.plans_parsed_note)
                                }
                            }
                        },
                        onPickSpeech = onPickSpeech,
                        dockInsets = false,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                modifier = Modifier.testTag("plan-editor-save"),
                onClick = {
                    val saved = runCatching {
                        editedTrigger(draft, original, newId, newThreadId, System.currentTimeMillis())
                    }
                    if (saved.isFailure) attempted = true
                    else onSave(saved.getOrThrow().copy(sendWechat = !draft.reminder && sendWechat))
                },
            ) { Text(tr(R.string.save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr(R.string.cancel)) } },
    )
}

@Composable
private fun PlanField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    tag: String,
    keyboardType: KeyboardType = KeyboardType.Text,
    singleLine: Boolean = true,
    placeholder: String? = null,
) {
    OutlinedTextField(
        value,
        onValueChange,
        Modifier.fillMaxWidth().testTag(tag).keepsKeyboard(),
        label = { Text(label) },
        placeholder = placeholder?.let { { Text(it) } },
        singleLine = singleLine,
        minLines = if (singleLine) 1 else 2,
        maxLines = if (singleLine) 1 else 5,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
    )
}

@Composable
private fun PlanSwitch(label: String, checked: Boolean, onChange: (Boolean) -> Unit, tag: String) {
    Row(
        Modifier.fillMaxWidth().height(48.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MikaType.Body, color = MikaTheme.colors.ink2)
        MikaSwitch(checked, onChange, Modifier.testTag(tag))
    }
}

@Composable
private fun planValidationText(reason: PlanValidation): String = tr(when (reason) {
    PlanValidation.MissingContent -> R.string.plans_error_content
    PlanValidation.ContentTooLong -> R.string.plans_error_content_long
    PlanValidation.ConditionTooLong -> R.string.plans_error_condition_long
    PlanValidation.ReminderWithCondition -> R.string.plans_error_reminder_condition
    PlanValidation.InvalidFirstTime -> R.string.plans_error_time
    PlanValidation.TimeNotFuture -> R.string.plans_need_future
    PlanValidation.InvalidInterval -> R.string.plans_error_interval
    PlanValidation.InvalidClock -> R.string.plans_error_clock
    PlanValidation.InvalidZone -> R.string.plans_error_zone
    PlanValidation.InvalidWeekdays -> R.string.plans_error_weekdays
    PlanValidation.InvalidCooldown -> R.string.plans_error_cooldown
})
