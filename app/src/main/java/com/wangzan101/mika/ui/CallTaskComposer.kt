package com.wangzan101.mika.ui

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import androidx.compose.ui.platform.LocalContext
import java.time.Instant
import java.time.format.DateTimeFormatter
import com.wangzan101.mika.calls.tasks.DialScheduleTime
import com.wangzan101.mika.calls.tasks.ScheduledDial
import com.wangzan101.mika.task.TriggerRuntime

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Switch
import androidx.compose.ui.Alignment
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.wangzan101.mika.R
import com.wangzan101.mika.calls.CallPolicy
import com.wangzan101.mika.calls.CallRuntime
import com.wangzan101.mika.calls.CallRuntimeState
import com.wangzan101.mika.calls.CallTaskBrief
import com.wangzan101.mika.calls.PhoneContacts
import com.wangzan101.mika.calls.tasks.CallAssistantTask
import com.wangzan101.mika.calls.tasks.CallTaskParser
import com.wangzan101.mika.calls.tasks.CallTaskTexts
import com.wangzan101.mika.ui.components.GroupCard
import com.wangzan101.mika.ui.components.GroupLabel
import com.wangzan101.mika.ui.components.ListRow
import com.wangzan101.mika.ui.components.PageScaffold
import com.wangzan101.mika.ui.components.RowChevron
import com.wangzan101.mika.ui.theme.MikaIcons
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.voice.VoiceSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.ZoneId
import java.time.ZonedDateTime

@Composable
internal fun CallTaskComposer(
    runtime: CallRuntime,
    state: CallRuntimeState,
    voice: VoiceSource?,
    existing: CallAssistantTask?,
    onBack: () -> Unit,
    onPickSpeech: () -> Unit = {},
    onDial: (String, String?, CallTaskBrief, String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val book = rememberPhoneContacts()
    var kind by remember { mutableIntStateOf(if (existing?.kind == CallTaskTexts.KIND_DIAL) 0 else 1) }
    var target by remember { mutableStateOf(existing?.target.orEmpty()) }
    var window by remember { mutableStateOf(existing?.windowLabel.orEmpty()) }
    val wasPermanent = existing?.kind == CallTaskTexts.KIND_INCOMING && existing.endMs == null
    var permanent by remember { mutableStateOf(wasPermanent) }
    var say by remember { mutableStateOf(existing?.say.orEmpty()) }
    var cannot by remember { mutableStateOf(existing?.cannotPromise.orEmpty()) }
    var number by remember { mutableStateOf(existing?.number.orEmpty()) }
    var simId by remember { mutableStateOf(existing?.dialSimId) }
    var dialAt by remember { mutableStateOf(existing?.dialAtMs) }
    var incomingStart by remember { mutableStateOf(existing?.startMs) }
    var incomingEnd by remember { mutableStateOf(existing?.endMs) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var pick by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val failed = stringResource(R.string.calls_task_parse_failed)
    val kindName = if (kind == 0) CallTaskTexts.KIND_DIAL else CallTaskTexts.KIND_INCOMING

    fun analyze(words: String) {
        if (busy) return
        busy = true
        error = null
        scope.launch {
            val parsed = withContext(Dispatchers.IO) {
                val model = runtime.mainModel()
                val names = book.names()
                val draft = model?.let { CallTaskParser.parse(it, words, names, required = true) }
                    ?: CallTaskParser.fromJson(fallbackJson(words))
                draft?.let { CallTaskParser.withContacts(it, book.entries()) }
            }
            busy = false
            if (parsed == null) {
                error = failed
                if (say.isBlank()) say = words.take(800)
            } else {
                if (parsed.kind == CallTaskTexts.KIND_DIAL) kind = 0
                if (parsed.kind == CallTaskTexts.KIND_INCOMING) kind = 1
                if (parsed.target.isNotBlank()) target = parsed.target
                if (parsed.windowLabel.isNotBlank()) {
                    window = parsed.windowLabel
                    val now = ZonedDateTime.now()
                    if (parsed.kind == CallTaskTexts.KIND_DIAL) {
                        dialAt = DialScheduleTime.resolve(parsed.windowLabel, now)?.toInstant()?.toEpochMilli()
                    } else if (parsed.kind == CallTaskTexts.KIND_INCOMING) {
                        val interval = CallTaskTexts.resolveWindow(parsed.windowLabel, now)
                        incomingStart = interval.first ?: DialScheduleTime.resolve(parsed.windowLabel, now)?.toInstant()?.toEpochMilli()
                        incomingEnd = interval.second
                    }
                }
                say = parsed.say
                cannot = parsed.cannotPromise
                if (parsed.number.isNotBlank()) number = parsed.number
            }
        }
    }

    PageScaffold(
        stringResource(if (existing == null) R.string.calls_task_new_row else R.string.calls_edit_rule), onBack,
        Modifier.testTag("call-task-composer"),
        footer = {
            UtteranceBar(
                voice, InputHints.call, busy, ::analyze, onPickSpeech = onPickSpeech,
            )
        },
    ) {
        TabRow(selectedTabIndex = kind) {
            Tab(kind == 0, { kind = 0 }, modifier = Modifier.testTag("call-task-tab-dial")) {
                Text(stringResource(R.string.calls_task_kind_dial), Modifier.padding(vertical = 12.dp), textAlign = TextAlign.Center)
            }
            Tab(kind == 1, { kind = 1 }, modifier = Modifier.testTag("call-task-tab-incoming")) {
                Text(stringResource(R.string.calls_task_kind_incoming), Modifier.padding(vertical = 12.dp), textAlign = TextAlign.Center)
            }
        }
        GroupLabel(stringResource(R.string.calls_task_parsed))
        GroupCard {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TaskField(stringResource(R.string.calls_task_target), target, { target = it }, "call-task-target")
                TextButton(onClick = { pick = true }, modifier = Modifier.testTag("call-task-pick-contact")) {
                    Text(stringResource(R.string.calls_contacts_pick))
                }
                if (kind == 0) TaskField(stringResource(R.string.calls_number), number, { number = it }, "call-task-number")
                if (kind == 1) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.calls_task_permanent), Modifier.weight(1f))
                        Switch(checked = permanent, onCheckedChange = { permanent = it }, modifier = Modifier.testTag("call-task-permanent"))
                    }
                }
                if (kind == 0) {
                    Text(stringResource(R.string.calls_scheduled_time))
                    TextButton(onClick = { pickCallTime(context, dialAt) { dialAt = it; window = formatCallTime(it) } }) {
                        Text(dialAt?.let(::formatCallTime) ?: stringResource(R.string.calls_scheduled_choose))
                    }
                } else if (!permanent) {
                    Text(stringResource(R.string.calls_incoming_start))
                    TextButton(onClick = { pickCallTime(context, incomingStart) { incomingStart = it } }) {
                        Text(incomingStart?.let(::formatCallTime) ?: stringResource(R.string.calls_scheduled_choose))
                    }
                    Text(stringResource(R.string.calls_incoming_end))
                    TextButton(onClick = { pickCallTime(context, incomingEnd ?: incomingStart) { incomingEnd = it } }) {
                        Text(incomingEnd?.let(::formatCallTime) ?: stringResource(R.string.calls_scheduled_choose))
                    }
                }
                TaskField(stringResource(R.string.calls_task_say), say, { say = it }, "call-task-say", single = false)
                TaskField(stringResource(R.string.calls_task_cannot), cannot, { cannot = it }, "call-task-cannot", single = false)
            }
        }
        var showSims by remember { mutableStateOf(false) }
        if (kind == 0 && state.simOptions.size > 1) {
            val sim = CallPolicy.chooseOutgoingSim(state.simOptions, simId, runtime.settings.state.value.defaultOutgoingSimId)
            ListRow(MikaIcons.Phone, stringResource(R.string.calls_sim), sim?.label ?: stringResource(R.string.calls_task_pick_sim),
                first = true, onClick = { showSims = true }) { RowChevron() }
            if (showSims) CallTaskSimDialog(state.simOptions, runtime.settings.state.value.defaultOutgoingSimId, simId,
                onChoose = { simId = it; showSims = false }, onDismiss = { showSims = false })
        }
        if (kind == 0 && !TriggerRuntime.canScheduleExactly(context)) {
            TextButton(onClick = { runCatching { context.startActivity(TriggerRuntime.exactAlarmSettings(context)) } }) {
                Text(stringResource(R.string.scene_alarm_permission))
            }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        val simError = stringResource(R.string.calls_task_pick_sim)
        val dialTimeError = stringResource(R.string.calls_scheduled_invalid)
        val incomingTimeError = stringResource(R.string.calls_incoming_invalid)
        CallPrimaryButton(stringResource(R.string.calls_task_save), !busy && say.isNotBlank(), {
            val time = dialAt
            val start = incomingStart
            val end = incomingEnd
            val plannedSim = CallPolicy.chooseOutgoingSim(state.simOptions, simId, runtime.settings.state.value.defaultOutgoingSimId)
            when {
                kind == 0 && (time == null || time <= System.currentTimeMillis() || !TriggerRuntime.canScheduleExactly(context) || CallPolicy.outgoingNumber(number) == null) -> error = dialTimeError
                kind == 0 && plannedSim == null -> error = simError
                kind == 1 && !permanent && (start == null || end == null || end <= start) -> error = incomingTimeError
                else -> {
                    busy = true
                    scope.launch {
                        val ok = withContext(Dispatchers.IO) {
                            runCatching {
                                if (kind == 0) CallTaskBrief(say, emptyList(), target, cannot).validatedSnapshot()
                                val label = if (kind == 0) formatCallTime(time!!) else if (permanent) "" else formatCallTime(start!!) + " – " + formatCallTime(end!!)
                                val parsed = parsedFromFields(kindName, target, label, say, cannot, number, existing?.title)
                                val parsedTask = CallTaskParser.toTask(parsed, id = existing?.id.orEmpty())
                                val timed = if (kind == 1) parsedTask.copy(startMs = if (permanent) null else start, endMs = if (permanent) null else end)
                                    else parsedTask.copy(dialAtMs = time, dialSimId = plannedSim?.id, dialScheduleState = "scheduled")
                                val saved = runtime.tasks.save(timed.copy(dialed = false, createdAtMs = existing?.createdAtMs ?: 0L))
                                if (kind == 0) {
                                    try { ScheduledDial.schedule(context, saved) }
                                    catch (e: Exception) { runtime.tasks.save(saved.copy(dialScheduleState = "failed", dialScheduleMessage = "无法设置定时拨号")); throw e }
                                } else if (existing?.kind == CallTaskTexts.KIND_DIAL) ScheduledDial.cancel(context, existing.id)
                            }.isSuccess
                        }
                        busy = false
                        if (ok) onBack() else error = failed
                    }
                }
            }
        }, Modifier.testTag("call-task-save"))
        if (kind == 0 && CallPolicy.outgoingNumber(number) != null && say.isNotBlank()) {
            TextButton(onClick = {
                val brief = runCatching {
                    CallTaskBrief(say, emptyList(), target, cannot).validatedSnapshot()
                }.getOrNull() ?: return@TextButton
                val parsed = parsedFromFields(kindName, target, window, say, cannot, number, existing?.title)
                scope.launch {
                    val saved = withContext(Dispatchers.IO) {
                        runCatching {
                            val saved = runtime.tasks.save(CallTaskParser.toTask(parsed, id = existing?.id.orEmpty()).copy(dialScheduleState = "none"))
                            ScheduledDial.cancel(context, saved.id)
                            saved
                        }.getOrNull()
                    }
                    if (saved != null) onDial(CallPolicy.outgoingNumber(number)!!, simId, brief, saved.id) else error = failed
                }
            }, modifier = Modifier.fillMaxWidth().testTag("call-task-dial")) {
                Text(stringResource(R.string.calls_task_dial_now))
            }
        }
        if (existing != null) {
            TextButton(onClick = { confirmDelete = true }, modifier = Modifier.fillMaxWidth().testTag("call-task-delete")) {
                Text(stringResource(R.string.calls_task_delete), color = MaterialTheme.colorScheme.error)
            }
        }
    }
    if (pick) ContactSinglePicker(onDismiss = { pick = false }) { entry ->
        target = entry.name
        if (kind == 0) number = entry.number
        pick = false
    }
    if (confirmDelete && existing != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.calls_task_delete)) },
            text = { Text(stringResource(R.string.calls_task_delete_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        val removed = withContext(Dispatchers.IO) { runCatching { runtime.tasks.remove(existing.id); ScheduledDial.cancel(context, existing.id) }.isSuccess }
                        if (removed) onBack() else error = failed
                    }
                }) { Text(stringResource(R.string.calls_task_delete)) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.calls_cancel)) } },
        )
    }
    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { runCatching { runtime.tasks.load() } } }
}

@Composable
internal fun DefaultBriefEditor(
    runtime: CallRuntime, voice: VoiceSource?, onBack: () -> Unit, onPickSpeech: () -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val book = rememberPhoneContacts()
    var text by remember { mutableStateOf("") }
    var ready by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val failed = stringResource(R.string.calls_action_failed)
    LaunchedEffect(Unit) {
        text = withContext(Dispatchers.IO) {
            runCatching {
                com.wangzan101.mika.calls.CallDefaultBrief.migrate(runtime.settings, runtime.instructions)
                val config = runtime.settings.state.value
                if (config.defaultBriefMigrated) config.defaultBrief
                else com.wangzan101.mika.calls.CallDefaultBrief.compose(config.systemPrompt, runtime.instructions.items.value)
            }.getOrDefault(runtime.settings.state.value.systemPrompt)
        }
        ready = true
    }
    PageScaffold(
        stringResource(R.string.calls_default_brief), onBack, Modifier.testTag("call-default-brief"),
        footer = {
            UtteranceBar(
                voice, InputHints.brief, busy, { words ->
                    busy = true
                    scope.launch {
                        val next = withContext(Dispatchers.IO) {
                            val model = runtime.mainModel()
                            val parsed = model?.let { CallTaskParser.parse(it, words, book.names(), required = true) }
                            parsed?.say?.takeIf { it.isNotBlank() }
                        }
                        busy = false
                        text = (next ?: words).take(com.wangzan101.mika.calls.CallDefaultBrief.MAX_CHARS)
                    }
                },
                onPickSpeech = onPickSpeech,
            )
        },
    ) {
        Text(stringResource(R.string.calls_default_brief_sub), color = MikaTheme.colors.ink3)
        if (ready) {
            OutlinedTextField(text, { text = it.take(com.wangzan101.mika.calls.CallDefaultBrief.MAX_CHARS) },
                modifier = Modifier.fillMaxWidth().testTag("call-default-brief-field"),
                label = { Text(stringResource(R.string.calls_default_brief)) }, minLines = 4, maxLines = 10)
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        CallPrimaryButton(stringResource(R.string.calls_save), ready && !busy, {
            busy = true
            scope.launch {
                val ok = withContext(Dispatchers.IO) {
                    runCatching {
                        runtime.settings.save(runtime.settings.state.value.copy(
                            defaultBrief = text.trim(), defaultBriefMigrated = true,
                        ))
                        runtime.instructions.load()
                        runtime.instructions.items.value.toList().forEach { runtime.instructions.remove(it.id) }
                    }.isSuccess
                }
                busy = false
                if (ok) onBack() else error = failed
            }
        })
    }
}

private fun parsedFromFields(kind: String, target: String, window: String, say: String, cannot: String, number: String, title: String?) =
    com.wangzan101.mika.calls.tasks.ParsedCallTask(
        kind = kind,
        title = title?.ifBlank { null } ?: target.ifBlank { say.take(24) },
        target = target,
        windowLabel = window,
        say = say,
        cannotPromise = cannot,
        number = number,
    )

/** Local hint when the model is unavailable, so the fields can still be filled from an obvious sentence. */
private fun fallbackJson(words: String): String {
    val kind = if (words.contains("打给") || words.contains("拨打") || words.contains("帮我打")) "dial" else "incoming"
    val escaped = words.replace("\\", "\\\\").replace("\"", "\\\"")
    return """{"is_task":true,"kind":"$kind","title":"","target":"","window":"","say":"$escaped","cannot_promise":"","number":""}"""
}

@Composable
private fun TaskField(label: String, value: String, onValue: (String) -> Unit, tag: String, single: Boolean = true) {
    OutlinedTextField(
        value, onValue, modifier = Modifier.fillMaxWidth().testTag(tag),
        label = { Text(label) }, singleLine = single, minLines = if (single) 1 else 2, maxLines = if (single) 1 else 4,
    )
}

private fun formatCallTime(epochMs: Long): String =
    Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))

/** Initialize both selectors with the actual system clock or the existing setting. */
private fun pickCallTime(context: Context, current: Long?, onPicked: (Long) -> Unit) {
    val initial = current?.let { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()) } ?: ZonedDateTime.now()
    DatePickerDialog(context, { _, year, month, day ->
        TimePickerDialog(context, android.R.style.Theme_Holo_Light_Dialog_NoActionBar, { _, hour, minute ->
            val local = java.time.LocalDateTime.of(year, month + 1, day, hour, minute)
            val zone = ZoneId.systemDefault()
            val offsets = zone.rules.getValidOffsets(local)
            if (offsets.size == 1) onPicked(ZonedDateTime.ofStrict(local, offsets.single(), zone).toInstant().toEpochMilli())
        }, initial.hour, initial.minute, true).show()
    }, initial.year, initial.monthValue - 1, initial.dayOfMonth).show()
}
