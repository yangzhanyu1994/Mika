package com.wangzan101.mika.ui

import com.wangzan101.mika.ui.theme.LocalSkinBackdrop
import androidx.compose.ui.graphics.Color
import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.wangzan101.mika.R
import com.wangzan101.mika.calls.*
import com.wangzan101.mika.calls.tasks.CallAssistantTask
import com.wangzan101.mika.calls.tasks.CallTaskStatus
import com.wangzan101.mika.calls.tasks.CallTaskTexts
import com.wangzan101.mika.ui.components.*
import com.wangzan101.mika.voice.VoiceSource
import com.wangzan101.mika.ui.theme.MikaIcons
import com.wangzan101.mika.ui.theme.MikaShapes
import com.wangzan101.mika.ui.theme.MikaSpace
import com.wangzan101.mika.ui.theme.MikaTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

/** The current call and receiving controls come first; the rest expands without changing pages. */
@Composable
fun CallSettingsScreen(runtime: CallRuntime, onInstructions: (() -> Unit)? = null, onBack: () -> Unit,
    standalone: Boolean = false, onHelperSetup: () -> Unit = {}, onAbout: () -> Unit = {},
    initialHistoryCallId: String? = null,
    updateAvailable: Boolean = false, focusRequest: Int = 0,
    voice: VoiceSource? = null,
    onPickSpeech: () -> Unit = {},
    openTaskComposer: Boolean = false,
    onTaskComposerOpened: () -> Unit = {},
) {
    var instructionsOpen by rememberSaveable { mutableStateOf(false) }
    var briefOpen by rememberSaveable { mutableStateOf(false) }
    var creatingTask by rememberSaveable { mutableStateOf(false) }
    var editingTaskId by rememberSaveable { mutableStateOf<String?>(null) }
    val taskItems by runtime.tasks.items.collectAsState()
    var handledFocusRequest by rememberSaveable { mutableIntStateOf(0) }
    val workspaceState = rememberSaveableStateHolder()
    val taskDraft = rememberSaveable(saver = CallTaskDraft.Saver) { CallTaskDraft() }
    LaunchedEffect(focusRequest) {
        if (focusRequest > 0 && focusRequest != handledFocusRequest) {
            instructionsOpen = false
            handledFocusRequest = focusRequest
        }
    }
    LaunchedEffect(openTaskComposer) {
        if (openTaskComposer) {
            instructionsOpen = false
            briefOpen = false
            creatingTask = true
            onTaskComposerOpened()
        }
    }
    when {
        briefOpen || instructionsOpen -> DefaultBriefEditor(
            runtime, voice, onPickSpeech = onPickSpeech, onBack = {
                briefOpen = false
                instructionsOpen = false
            },
        )
        creatingTask || editingTaskId != null -> {
            val existing = taskItems.firstOrNull { it.id == editingTaskId }
            val callState by runtime.state.collectAsState()
            val dialScope = rememberCoroutineScope()
            CallTaskComposer(runtime, callState, voice, existing, onBack = {
                creatingTask = false
                editingTaskId = null
            }, onPickSpeech = onPickSpeech, onDial = { number, sim, brief, id ->
                creatingTask = false
                editingTaskId = null
                dialScope.launch {
                    val result = runtime.dial(number, simId = sim, task = brief)
                    if (result.ok) withContext(Dispatchers.IO) { runCatching { runtime.tasks.markDialed(id) } }
                }
            })
        }
        else -> workspaceState.SaveableStateProvider("call-workspace") {
            CallSettingsWorkspace(runtime, taskDraft, onBack, { briefOpen = true },
                standalone, onHelperSetup, onAbout, updateAvailable, focusRequest, initialHistoryCallId,
                onCreateTask = { creatingTask = true }, onEditTask = { editingTaskId = it })
        }
    }
}

@Composable
private fun CallSettingsWorkspace(
    runtime: CallRuntime, taskDraft: CallTaskDraft,
    onBack: () -> Unit, onInstructions: () -> Unit,
    standalone: Boolean, onHelperSetup: () -> Unit, onAbout: () -> Unit, updateAvailable: Boolean,
    focusRequest: Int,
    initialHistoryCallId: String?,
    onCreateTask: () -> Unit,
    onEditTask: (String) -> Unit,
) {
    val config by runtime.settings.state.collectAsState()
    val state by runtime.state.collectAsState()
    val history by runtime.history.collectAsState()
    val tasks by runtime.tasks.items.collectAsState()
    LaunchedEffect(runtime) { withContext(Dispatchers.IO) { runCatching { runtime.tasks.load() } } }
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var controlBusy by remember { mutableStateOf(false) }
    var settingsBusy by remember { mutableStateOf(false) }
    var lastAction by remember { mutableIntStateOf(0) }
    var lastActionWasLockedDial by remember { mutableStateOf(false) }
    var feedback by remember { mutableStateOf<CallActionResult?>(null) }
    var tab by rememberSaveable { mutableStateOf(if (initialHistoryCallId != null) CallWorkspaceTab.History else CallWorkspaceTab.Receive) }
    LaunchedEffect(initialHistoryCallId) {
        if (initialHistoryCallId != null) tab = CallWorkspaceTab.History
    }
    val tabStates = rememberSaveableStateHolder()
    var liveNavigation by remember { mutableIntStateOf(0) }
    var handledLiveNavigation by remember { mutableIntStateOf(0) }
    var handledFocusRequest by rememberSaveable { mutableIntStateOf(0) }
    var taskNavigation by rememberSaveable { mutableIntStateOf(0) }
    var handledTaskNavigation by rememberSaveable { mutableIntStateOf(0) }
    var deviceNavigation by rememberSaveable { mutableIntStateOf(0) }
    var handledDeviceNavigation by rememberSaveable { mutableIntStateOf(0) }
    var pendingReuse by remember { mutableStateOf<Pair<String, CallTaskBrief>?>(null) }
    var dialog by rememberSaveable { mutableStateOf<AnswerSettingEditor?>(null) }
    val idle = state.phase == CallPhase.IDLE
    val pendingLockedDial = state.lockedDialRequestId
    val failureText = stringResource(R.string.calls_action_failed)
    val settingsErrorText = stringResource(R.string.calls_device_system_settings_failed)
    val device = rememberCallDeviceControls(runtime) { feedback = CallActionResult(false, settingsErrorText) }
    val ready = config.enabled && state.prepared && state.shellConnected && device.readiness.phoneAllowed &&
        device.readiness.callNotificationsAllowed && state.notificationsAvailable && config.credentialsConfigured && state.audioReady
    val canStartStandby = ready && config.audioVerified && config.autoAnswer && idle && pendingLockedDial == null &&
        device.readiness.standbyNotificationsAllowed && !busy && !controlBusy && !settingsBusy

    LaunchedEffect(focusRequest) {
        if (focusRequest > 0 && focusRequest != handledFocusRequest) {
            tab = CallWorkspaceTab.Receive
            liveNavigation++
            handledFocusRequest = focusRequest
        }
    }

    fun perform(priority: Boolean = false, lockedDial: Boolean = false, action: suspend () -> CallActionResult) {
        if (if (priority) controlBusy else busy || controlBusy) return
        if (priority) controlBusy = true else busy = true
        val sequence = ++lastAction
        lastActionWasLockedDial = lockedDial
        feedback = null
        scope.launch {
            try {
                val result = action()
                if (sequence == lastAction) feedback = result
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                if (sequence == lastAction) feedback = CallActionResult(false, failureText)
            } finally {
                if (priority) controlBusy = false else busy = false
            }
        }
    }

    suspend fun save(change: (CallConfig) -> CallConfig): Boolean {
        if (settingsBusy) return false
        settingsBusy = true
        return try {
            withContext(Dispatchers.IO) {
                synchronized(runtime.settings) { runtime.settings.save(change(runtime.settings.state.value)) }
            }
            true
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            feedback = CallActionResult(false, failureText)
            false
        } finally {
            settingsBusy = false
        }
    }

    val prepare: () -> Unit = {
        perform {
            if (!runtime.settings.state.value.enabled && !save { it.copy(enabled = true) }) {
                CallActionResult(false, failureText)
            } else runtime.prepare()
        }
    }
    val startStandby: () -> Unit = { perform { runtime.startBackgroundStandby() } }
    fun reuse(number: String, brief: CallTaskBrief) {
        scope.launch {
            val saved = withContext(Dispatchers.IO) {
                runCatching {
                    runtime.tasks.save(CallAssistantTask(
                        id = "", kind = CallTaskTexts.KIND_DIAL, title = brief.goal.take(24),
                        target = number, say = brief.goal, cannotPromise = brief.constraints, number = number,
                    ))
                }.getOrNull()
            }
            if (saved != null) onEditTask(saved.id)
        }
    }

    fun openDeviceSettings() {
        tab = CallWorkspaceTab.Settings
        deviceNavigation++
    }

    PageBackground(Modifier.testTag("call-settings")) {
        Column(Modifier.fillMaxSize().navigationBarsPadding().imePadding()) {
            if (standalone) TopBar(stringResource(R.string.calls_title), MikaIcons.Gear, ::openDeviceSettings,
                stringResource(R.string.calls_tab_settings), right = {
                    BarIconButton(MikaIcons.Info, stringResource(R.string.about_check), onAbout, badge = updateAvailable)
                })
            else TopBar(stringResource(R.string.calls_title), MikaIcons.Back, onBack, stringResource(R.string.back))
            if (!idle) {
                val callId = state.callId
                CurrentCallCard(
                    state = state, feedback = feedback,
                    canAnswer = ready && !busy && !controlBusy && callId != null && state.canAnswer,
                    canControl = !controlBusy && callId != null,
                    modifier = Modifier.padding(horizontal = MikaSpace.Page),
                    onAnswer = { callId?.let { perform { runtime.answer(it) } } },
                    onHangup = { callId?.let { perform(priority = true) { runtime.hangup(it) } } },
                    onTakeover = {
                        if (callId != null) perform(priority = true) {
                            withContext(Dispatchers.Main.immediate) {
                                val current = runtime.state.value
                                if (current.callId == callId && current.canTakeOver) runtime.takeover()
                                else CallActionResult(false, failureText)
                            }
                        }
                    },
                    onCancelAuto = { callId?.let { perform(priority = true) { runtime.cancelAutoAnswer(it) } } },
                    onTranscript = {
                        tab = CallWorkspaceTab.Receive
                        liveNavigation++
                    },
                )
            }
            if (pendingLockedDial != null) {
                CallLockedDialPendingRow(state, enabled = !controlBusy) {
                    perform(priority = true, lockedDial = true) { runtime.cancelLockedDial(pendingLockedDial) }
                }
            }
            CallWorkspaceTabs(tab) { tab = it }
            tabStates.SaveableStateProvider(tab.name) {
                val scroll = rememberScrollState()
                LaunchedEffect(tab, liveNavigation, taskNavigation, deviceNavigation) {
                    when (tab) {
                        CallWorkspaceTab.Receive -> if (liveNavigation != handledLiveNavigation) {
                            scroll.scrollTo(0)
                            handledLiveNavigation = liveNavigation
                        }
                        CallWorkspaceTab.Task -> if (taskNavigation != handledTaskNavigation) {
                            scroll.scrollTo(0)
                            handledTaskNavigation = taskNavigation
                        }
                        CallWorkspaceTab.Settings -> if (deviceNavigation != handledDeviceNavigation) {
                            scroll.scrollTo(0)
                            handledDeviceNavigation = deviceNavigation
                        }
                        CallWorkspaceTab.History -> Unit
                    }
                }
                Column(
                    Modifier.weight(1f).fillMaxWidth().verticalScroll(scroll)
                        .padding(horizontal = MikaSpace.Page).padding(top = 8.dp, bottom = 24.dp)
                        .testTag("call-page-" + tab.name.lowercase()),
                ) {
                    val visibleFeedback = feedback?.takeIf {
                        idle && !(lastActionWasLockedDial && it.ok && state.lockedDialDetail != null)
                    }
                    val center = tab == CallWorkspaceTab.Receive || tab == CallWorkspaceTab.Task
                    if (center && idle && pendingLockedDial == null && visibleFeedback == null) {
                        state.lockedDialDetail?.let { FooterNote(it) }
                    }
                    if (!center) visibleFeedback?.let { CallFeedback(it) }
                    when (tab) {
                        CallWorkspaceTab.Receive, CallWorkspaceTab.Task -> CallCenterPage(
                            config, state, ready, device, tasks,
                            controlsEnabled = !settingsBusy && !controlBusy && (!busy || config.autoAnswer),
                            canStartStandby = canStartStandby,
                            standbyNeedsSetup = ready && !device.readiness.standbyNotificationsAllowed,
                            canConfigure = idle && pendingLockedDial == null && !busy && !controlBusy && !settingsBusy,
                            stopBusy = controlBusy || settingsBusy,
                            onSetAi = { enabled ->
                                scope.launch {
                                    val saved = save { it.copy(autoAnswer = enabled) }
                                    if (saved && enabled && runtime.settings.state.value.autoAnswer &&
                                        device.readiness.standbyNotificationsAllowed) startStandby()
                                    if (enabled && !runtime.settings.state.value.autoAnswer) {
                                        feedback = CallActionResult(false, failureText)
                                    }
                                }
                            },
                            onHelperSetup = onHelperSetup,
                            onStartStandby = startStandby,
                            onSetup = ::openDeviceSettings,
                            onPrepare = prepare,
                            onCredentials = { dialog = AnswerSettingEditor.Credentials },
                            onCreateTask = onCreateTask,
                            onEditTask = onEditTask,
                        )
                        CallWorkspaceTab.History -> CallHistorySection(
                            history, state.historyWriteFailed, onMark = runtime::setHistoryHandled, onDelete = runtime::deleteHistory,
                            onRetrySummary = runtime::retrySummary,
                            focusRecordId = initialHistoryCallId,
                            onClear = runtime::clearHistory,
                            onReuseTask = if (pendingLockedDial != null || busy || controlBusy) null else { number, brief ->
                                if (taskDraft.hasContent) pendingReuse = number to brief else reuse(number, brief)
                            },
                        )
                        CallWorkspaceTab.Settings -> CallPreferencesPage(
                            runtime, config, state, ready, deviceNavigation,
                            canConfigure = idle && pendingLockedDial == null && !busy && !controlBusy && !settingsBusy,
                            controlsEnabled = !settingsBusy && !controlBusy && (!busy || config.autoAnswer),
                            stopBusy = controlBusy || settingsBusy, device = device, canStartStandby = canStartStandby,
                            onStop = { perform(priority = true) { runtime.stop() } },
                            onStartStandby = startStandby,
                            onOpen = { editor ->
                                when (editor) {
                                    AnswerSettingEditor.DefaultBrief -> onInstructions()
                                    AnswerSettingEditor.None, AnswerSettingEditor.StrangerOnly, AnswerSettingEditor.RingTimeout -> Unit
                                    else -> dialog = editor
                                }
                            },
                            onStrangerOnly = { enabled -> scope.launch { save { it.copy(answerStrangersOnly = enabled) } } },
                            onRingTimeout = { enabled -> scope.launch { save { it.copy(answerOnRingTimeout = enabled) } } },
                            onSummaryEnabled = { enabled -> scope.launch { save { it.copy(summaryEnabled = enabled) } } },
                        )
                    }
                }
            }
        }
    }

    pendingReuse?.let { (number, brief) ->
        AlertDialog(
            onDismissRequest = { pendingReuse = null },
            title = { Text(stringResource(R.string.calls_task_replace_title)) },
            text = { Text(stringResource(R.string.calls_task_replace_note)) },
            confirmButton = {
                TextButton(enabled = !busy && !controlBusy, onClick = { pendingReuse = null; reuse(number, brief) }) {
                    Text(stringResource(R.string.calls_task_replace_action))
                }
            },
            dismissButton = { TextButton(onClick = { pendingReuse = null }) { Text(stringResource(R.string.calls_cancel)) } },
        )
    }

    when (dialog) {
        AnswerSettingEditor.AnswerScope -> CallScopeDialog(config.allowAllIncoming, onDismiss = { dialog = null }) { all ->
            save { it.copy(allowAllIncoming = all) }
        }
        AnswerSettingEditor.AnswerDelay -> CallDelayDialog(config.autoAnswerDelaySeconds, onDismiss = { dialog = null }) { seconds ->
            save { it.copy(autoAnswerDelaySeconds = seconds) }
        }
        AnswerSettingEditor.Allowlist -> CallAllowlistDialog(config.allowedIncomingNumbers, onDismiss = { dialog = null }) { numbers ->
            save { it.copy(allowedIncomingNumbers = numbers) }
        }
        AnswerSettingEditor.SelfAnswer -> CallBypassDialog(config.bypassIncomingNumbers, onDismiss = { dialog = null }) { numbers ->
            save { it.copy(bypassIncomingNumbers = numbers) }
        }
        AnswerSettingEditor.Credentials -> CallCredentialsDialog(runtime, onDismiss = { dialog = null }) {
            lastActionWasLockedDial = false
            feedback = CallActionResult(true, it)
            scope.launch { runCatching { runtime.probeCredentials() } }
        }
        AnswerSettingEditor.Greeting -> CallGreetingDialog(config.greeting, onDismiss = { dialog = null }) { greeting ->
            save { it.copy(greeting = greeting) }
        }
        AnswerSettingEditor.OutgoingGreeting -> CallGreetingDialog(config.outgoingGreeting, onDismiss = { dialog = null },
            title = R.string.calls_outgoing_greeting) { greeting -> save { it.copy(outgoingGreeting = greeting) } }
        AnswerSettingEditor.DefaultSim -> CallDefaultSimDialog(state.simOptions, config.defaultOutgoingSimId,
            onDismiss = { dialog = null }, onChoose = { id -> scope.launch { if (save { it.copy(defaultOutgoingSimId = id) }) dialog = null } })
        AnswerSettingEditor.Duration -> CallDurationDialog(config.maxCallSeconds, onDismiss = { dialog = null }) { seconds ->
            save { it.copy(maxCallSeconds = seconds) }
        }
        AnswerSettingEditor.AudioChannel -> CallAudioDialog(
            config, canVerify = idle && pendingLockedDial == null && !busy && !controlBusy && !settingsBusy &&
                (ready || config.audioVerified),
            onVerify = { verified ->
                scope.launch { save { it.copy(audioVerified = verified, autoAnswer = verified && it.autoAnswer) } }
            },
            onDismiss = { dialog = null },
            onMode = { mode -> scope.launch { save { it.copy(audioMode = mode) } } },
        )
        AnswerSettingEditor.DefaultBrief, AnswerSettingEditor.BackgroundLock, AnswerSettingEditor.None,
        AnswerSettingEditor.StrangerOnly, AnswerSettingEditor.RingTimeout, null -> Unit
    }
}

private enum class CallWorkspaceTab(val label: Int) {
    Receive(R.string.calls_tab_call), Task(R.string.calls_nav_task),
    History(R.string.calls_tab_history), Settings(R.string.calls_tab_settings),
}

/** 通话中心、接听设置、通话记录。旧的「电话任务」页并进通话中心,枚举值留着以免恢复页面状态时对不上。 */
private val visibleCallTabs = listOf(CallWorkspaceTab.Receive, CallWorkspaceTab.Settings, CallWorkspaceTab.History)

private fun shownCallTab(tab: CallWorkspaceTab): CallWorkspaceTab =
    if (tab == CallWorkspaceTab.Task) CallWorkspaceTab.Receive else tab

@Composable
private fun CallWorkspaceTabs(selected: CallWorkspaceTab, onSelect: (CallWorkspaceTab) -> Unit) {
    val shown = shownCallTab(selected)
    // 页面底有背景图片时标签栏透出它,不留一条纯色
    val tabBg = if (LocalSkinBackdrop.current != null) Color.Transparent else MikaTheme.colors.bg
    TabRow(selectedTabIndex = visibleCallTabs.indexOf(shown), containerColor = tabBg, contentColor = MikaTheme.colors.accent) {
        visibleCallTabs.forEach { tab ->
            Tab(
                selected = tab == shown, onClick = { onSelect(tab) },
                modifier = Modifier.heightIn(min = 48.dp).testTag("call-tab-" + tab.name.lowercase()),
            ) {
                Text(stringResource(tab.label), Modifier.padding(horizontal = 3.dp, vertical = 6.dp),
                    textAlign = TextAlign.Center, style = MaterialTheme.typography.labelLarge,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
internal fun CallFeedback(result: CallActionResult) {
    FooterNote(result.message, Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        color = if (result.ok) MikaTheme.colors.ink3 else MaterialTheme.colorScheme.error)
}

@Composable
private fun CurrentCallCard(
    state: CallRuntimeState,
    feedback: CallActionResult?,
    canAnswer: Boolean,
    canControl: Boolean,
    modifier: Modifier = Modifier,
    onAnswer: () -> Unit,
    onHangup: () -> Unit,
    onTakeover: () -> Unit,
    onCancelAuto: () -> Unit,
    onTranscript: () -> Unit,
) {
    val book = rememberPhoneContacts()
    val cancelAuto = state.autoAnswerStage == CallAutoAnswerStage.COUNTDOWN ||
        state.autoAnswerStage == CallAutoAnswerStage.PREPARING
    val ringing = state.phase == CallPhase.RINGING && state.autoAnswerStage != CallAutoAnswerStage.ANSWERING
    val cancelManual = ringing && state.canTakeOver && !cancelAuto
    val showTakeover = state.canTakeOver && !cancelAuto && !ringing
    val phase = when (state.autoAnswerStage) {
        CallAutoAnswerStage.COUNTDOWN -> state.autoAnswerRemainingSeconds?.let {
            stringResource(R.string.calls_auto_countdown, it)
        } ?: stringResource(R.string.calls_answer_pending)
        CallAutoAnswerStage.PREPARING -> stringResource(R.string.calls_auto_preparing)
        CallAutoAnswerStage.ANSWERING -> stringResource(R.string.calls_auto_answering)
        else -> if (state.aiActive) stringResource(R.string.calls_ai_active) else phaseLabel(state.phase)
    }
    MikaCard(modifier.padding(bottom = 6.dp).testTag("call-current")) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                IconTile(MikaIcons.Phone, if (state.aiActive || cancelAuto) TileTone.Accent else TileTone.Neutral)
                Column(Modifier.weight(1f)) {
                    Text(phase, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    val party = state.number?.let { "\u2066${book.label(it)}\u2069" }
                        ?: stringResource(R.string.calls_unknown_number).takeIf { state.direction == CallDirection.INCOMING }
                    val direction = state.direction?.let { directionLabel(it) }
                    val subtitle = listOfNotNull(direction, party).joinToString(" · ")
                    if (subtitle.isNotBlank()) Text(subtitle, style = MaterialTheme.typography.bodySmall,
                        color = MikaTheme.colors.ink3, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (state.aiActive || state.liveTranscript.isNotEmpty()) {
                    IconButton(onClick = onTranscript, modifier = Modifier.testTag("call-current-transcript")) {
                        Icon(MikaIcons.Message, contentDescription = stringResource(R.string.calls_live_transcript),
                            tint = MikaTheme.colors.accent)
                    }
                }
            }
            if (ringing || showTakeover || cancelAuto || state.canHangUp) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    if (ringing || showTakeover) {
                        val action = when {
                            cancelManual -> Triple(R.string.calls_cancel_ai_answer, onTakeover, "call-cancel-ai-answer")
                            showTakeover -> Triple(R.string.calls_takeover, onTakeover, "call-takeover")
                            else -> Triple(R.string.calls_answer, onAnswer, "call-answer")
                        }
                        Button(onClick = action.second, enabled = if (cancelManual || showTakeover) canControl else canAnswer,
                            modifier = Modifier.weight(1.25f).heightIn(min = 48.dp).testTag(action.third),
                            shape = MikaShapes.Button, contentPadding = PaddingValues(horizontal = 7.dp, vertical = 6.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = MikaTheme.colors.accent, contentColor = MikaTheme.colors.onAccent),
                        ) { Text(stringResource(action.first), textAlign = TextAlign.Center) }
                    }
                    if (cancelAuto) OutlinedButton(onClick = onCancelAuto, enabled = canControl,
                        modifier = Modifier.weight(1.25f).heightIn(min = 48.dp).testTag("call-cancel-auto"),
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 6.dp),
                    ) { Text(stringResource(R.string.calls_auto_cancel), textAlign = TextAlign.Center) }
                    if (state.canHangUp) TextButton(onClick = onHangup, enabled = canControl,
                        modifier = Modifier.weight(.85f).heightIn(min = 48.dp).testTag("call-hangup"),
                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 6.dp),
                    ) { Text(stringResource(R.string.calls_hangup), textAlign = TextAlign.Center,
                        color = if (canControl) MaterialTheme.colorScheme.error else MikaTheme.colors.ink3) }
                }
            }
            feedback?.let {
                Text(it.message, Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    color = if (it.ok) MikaTheme.colors.ink3 else MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun LiveCallTranscript(callId: String?, lines: List<CallTranscriptLine>) {
    var expanded by rememberSaveable(callId) { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.calls_live_transcript), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
        if (lines.size > 3) TextButton(onClick = { expanded = !expanded }) {
            Text(stringResource(if (expanded) R.string.calls_collapse else R.string.calls_expand))
        }
    }
    if (lines.isEmpty()) {
        Text(stringResource(R.string.calls_live_waiting), style = MaterialTheme.typography.bodySmall, color = MikaTheme.colors.ink3)
    } else Column(Modifier.testTag("call-live-transcript"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        (if (expanded) lines else lines.takeLast(3)).forEach { line ->
            CallTranscriptText(line)
        }
    }
}

@Composable
private fun CallCenterPage(
    config: CallConfig, state: CallRuntimeState, ready: Boolean, device: CallDeviceControls,
    tasks: List<CallAssistantTask>,
    controlsEnabled: Boolean, canStartStandby: Boolean, standbyNeedsSetup: Boolean,
    canConfigure: Boolean, stopBusy: Boolean,
    onSetAi: (Boolean) -> Unit,
    onHelperSetup: () -> Unit, onStartStandby: () -> Unit, onSetup: () -> Unit,
    onPrepare: () -> Unit, onCredentials: () -> Unit,
    onCreateTask: () -> Unit, onEditTask: (String) -> Unit,
) {
    GroupLabel(stringResource(R.string.calls_section_status))
    CallStatusCard(config, state, controlsEnabled, onSetAi)
    if (state.aiActive || state.liveTranscript.isNotEmpty()) {
        GroupCard {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                LiveCallTranscript(state.callId, state.liveTranscript)
            }
        }
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        GroupLabel(stringResource(R.string.calls_section_tasks), Modifier.weight(1f))
        TextButton(onClick = onCreateTask, enabled = canConfigure, modifier = Modifier.testTag("call-task-new")) {
            Text(stringResource(R.string.calls_task_new))
        }
    }
    GroupCard(Modifier.testTag("call-task-list")) {
        if (tasks.isEmpty()) {
            Text(stringResource(R.string.calls_task_empty), Modifier.padding(16.dp), color = MikaTheme.colors.ink3,
                style = MaterialTheme.typography.bodySmall)
        }
        tasks.forEachIndexed { index, task ->
            val status = task.statusAt(System.currentTimeMillis())
            val statusText = stringResource(if (task.kind == CallTaskTexts.KIND_DIAL) when (task.dialScheduleState) {
                "scheduled" -> R.string.calls_schedule_pending
                "dispatching" -> R.string.calls_schedule_dispatching
                "submitted" -> R.string.calls_schedule_submitted
                "failed" -> R.string.calls_schedule_failed
                else -> if (task.dialed) R.string.calls_task_status_done else R.string.calls_task_status_pending
            } else when (status) {
                CallTaskStatus.ACTIVE -> R.string.calls_task_status_active
                CallTaskStatus.PENDING -> R.string.calls_task_status_pending
                CallTaskStatus.DONE -> R.string.calls_task_status_done
                CallTaskStatus.LATER -> R.string.calls_task_status_later
            })
            val line = if (task.kind == CallTaskTexts.KIND_DIAL) {
                stringResource(R.string.calls_task_line_dial, task.windowLabel.ifBlank { statusText })
            } else {
                stringResource(R.string.calls_task_line_incoming, task.target.ifBlank { stringResource(R.string.calls_caller) }, task.say.take(42))
            }
            ListRow(
                if (task.kind == CallTaskTexts.KIND_DIAL) MikaIcons.Phone else MikaIcons.Doc,
                task.title, line, first = index == 0 && tasks.isNotEmpty(),
                detail = task.dialScheduleMessage.takeIf { it.isNotBlank() },
                onClick = { onEditTask(task.id) },
            ) {
                Text(statusText, style = MaterialTheme.typography.labelMedium,
                    color = if (status == CallTaskStatus.ACTIVE) MikaTheme.colors.ok else MikaTheme.colors.ink3)
            }
        }
        ListRow(MikaIcons.Plus, stringResource(R.string.calls_task_new_row), first = tasks.isEmpty(), onClick = onCreateTask) {
            Icon(MikaIcons.Mic, stringResource(R.string.calls_task_hold), tint = MikaTheme.colors.accent)
        }
    }
    GroupLabel(stringResource(R.string.calls_readiness_title))
    GroupCard {
        CallPermissionRows(device, state.shellConnected, canConfigure, onHelperSetup, config, first = true)
        CallBackgroundStandbyRow(config, state, canStartStandby, onStartStandby, onSetup.takeIf { standbyNeedsSetup })
    }
    CallReadinessActions(
        config, state, ready, canConfigure, stopBusy, device.readiness, onPrepare, {}, onCredentials, showStop = false,
    )
    FooterNote(stringResource(R.string.calls_privacy_note))
}

@Composable
private fun CallStatusCard(
    config: CallConfig, state: CallRuntimeState, controlsEnabled: Boolean, onSetAi: (Boolean) -> Unit,
) {
    val listening = state.autoAnswerStage == CallAutoAnswerStage.STANDBY
    val title = when (state.autoAnswerStage) {
        CallAutoAnswerStage.STANDBY -> stringResource(R.string.calls_status_standby)
        CallAutoAnswerStage.PAUSED -> stringResource(R.string.calls_auto_paused)
        CallAutoAnswerStage.OFF -> stringResource(R.string.calls_mode_manual)
        else -> stringResource(R.string.calls_auto_answer)
    }
    val aiOn = config.autoAnswer && state.autoAnswerStage != CallAutoAnswerStage.OFF
    MikaCard(Modifier.padding(bottom = 6.dp).testTag("call-status")) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.size(8.dp).background(if (listening || aiOn) MikaTheme.colors.ok else MikaTheme.colors.ink3, CircleShape))
                Text(title, style = MaterialTheme.typography.titleMedium)
            }
            Text(autoAnswerSummary(config, state), style = MaterialTheme.typography.bodySmall, color = MikaTheme.colors.ink3)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val aiSelected = config.autoAnswer
                if (aiSelected) {
                    Button(onClick = { onSetAi(true) }, enabled = controlsEnabled,
                        modifier = Modifier.weight(1f).heightIn(min = 44.dp).testTag("call-mode-ai"),
                        shape = MikaShapes.Button,
                        colors = ButtonDefaults.buttonColors(containerColor = MikaTheme.colors.accent, contentColor = MikaTheme.colors.onAccent),
                    ) { Text(stringResource(R.string.calls_mode_ai)) }
                } else {
                    OutlinedButton(onClick = { onSetAi(true) }, enabled = controlsEnabled,
                        modifier = Modifier.weight(1f).heightIn(min = 44.dp).testTag("call-mode-ai"),
                    ) { Text(stringResource(R.string.calls_mode_ai)) }
                }
                if (!aiSelected) {
                    Button(onClick = { onSetAi(false) }, enabled = controlsEnabled,
                        modifier = Modifier.weight(1f).heightIn(min = 44.dp).testTag("call-mode-manual"),
                        shape = MikaShapes.Button,
                        colors = ButtonDefaults.buttonColors(containerColor = MikaTheme.colors.accent, contentColor = MikaTheme.colors.onAccent),
                    ) { Text(stringResource(R.string.calls_mode_manual)) }
                } else {
                    OutlinedButton(onClick = { onSetAi(false) }, enabled = controlsEnabled,
                        modifier = Modifier.weight(1f).heightIn(min = 44.dp).testTag("call-mode-manual"),
                    ) { Text(stringResource(R.string.calls_mode_manual)) }
                }
            }
        }
    }
}

@Composable
private fun CallPreferencesPage(
    runtime: CallRuntime,
    config: CallConfig, state: CallRuntimeState, ready: Boolean, openDeviceRequest: Int,
    canConfigure: Boolean, controlsEnabled: Boolean, stopBusy: Boolean, device: CallDeviceControls, canStartStandby: Boolean,
    onStop: () -> Unit, onOpen: (AnswerSettingEditor) -> Unit,
    onStrangerOnly: (Boolean) -> Unit,
    onRingTimeout: (Boolean) -> Unit,
    onSummaryEnabled: (Boolean) -> Unit,
    onStartStandby: () -> Unit,
) {
    var backgroundOpen by rememberSaveable { mutableStateOf(false) }
    var lastDeviceRequest by rememberSaveable { mutableIntStateOf(0) }
    var probing by remember { mutableStateOf(false) }
    LaunchedEffect(config.credentialsConfigured, config.credentialProbeOk) {
        if (config.credentialsConfigured && config.credentialProbeOk == null && !probing) {
            probing = true
            runCatching { runtime.probeCredentials() }
            probing = false
        }
    }
    LaunchedEffect(openDeviceRequest) {
        if (openDeviceRequest != lastDeviceRequest) {
            backgroundOpen = true
            lastDeviceRequest = openDeviceRequest
        }
    }
    GroupLabel(stringResource(R.string.calls_group_ai))
    GroupCard {
        val greeting = config.greeting.trim().lineSequence().firstOrNull().orEmpty()
        val brief = (if (config.defaultBriefMigrated) config.defaultBrief else config.systemPrompt).trim()
        ListRow(
            MikaIcons.Message, stringResource(R.string.calls_incoming_greeting),
            greeting.ifBlank { stringResource(R.string.calls_key_missing) },
            first = true, onClick = { onOpen(answerSettingEditor(AnswerSettingRow.Greeting)) }.takeIf { canConfigure },
        ) { RowChevron() }
        ListRow(
            MikaIcons.Doc, stringResource(R.string.calls_default_brief),
            stringResource(R.string.calls_default_brief_sub),
            detail = brief.lineSequence().firstOrNull()?.take(80),
            onClick = { onOpen(answerSettingEditor(AnswerSettingRow.DefaultBrief)) }.takeIf { canConfigure },
        ) { RowChevron() }
        ListRow(
            MikaIcons.Clock, stringResource(R.string.calls_duration_row),
            stringResource(R.string.calls_duration_value, config.maxCallSeconds),
            onClick = { onOpen(answerSettingEditor(AnswerSettingRow.Duration)) }.takeIf { canConfigure },
        ) { RowChevron() }
    }

    GroupLabel(stringResource(R.string.calls_outgoing_group))
    GroupCard {
        val selected = state.simOptions.firstOrNull { it.id == config.defaultOutgoingSimId }
        val simText = when {
            config.defaultOutgoingSimId == null -> stringResource(R.string.calls_sim_ask_each_time)
            selected == null -> stringResource(R.string.calls_sim_unavailable)
            else -> selected.label
        }
        ListRow(MikaIcons.Phone, stringResource(R.string.calls_default_sim_settings), simText,
            first = true, onClick = { onOpen(AnswerSettingEditor.DefaultSim) }.takeIf { canConfigure }) { RowChevron() }
        ListRow(MikaIcons.Message, stringResource(R.string.calls_outgoing_greeting),
            config.outgoingGreeting.lineSequence().firstOrNull().orEmpty().ifBlank { stringResource(R.string.calls_key_missing) },
            onClick = { onOpen(AnswerSettingEditor.OutgoingGreeting) }.takeIf { canConfigure }) { RowChevron() }
    }

    GroupLabel(stringResource(R.string.calls_group_summary))
    GroupCard {
        Row(
            Modifier.fillMaxWidth().toggleable(config.summaryEnabled, enabled = canConfigure, role = Role.Switch,
                onValueChange = onSummaryEnabled).padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.calls_summary_enable), style = MaterialTheme.typography.bodyMedium)
                Text(stringResource(R.string.calls_summary_row_sub), style = MaterialTheme.typography.bodySmall, color = MikaTheme.colors.ink3)
            }
            Switch(config.summaryEnabled, onCheckedChange = null, enabled = canConfigure, modifier = Modifier.testTag("call-summary-enabled"))
        }
        FooterNote(stringResource(R.string.calls_summary_cloud_consent))
    }

    GroupLabel(stringResource(R.string.calls_advanced))
    GroupCard {
        val configured = config.credentialsConfigured
        val probe = config.credentialProbeOk
        val credentialBad = !configured || probe == false
        ListRow(
            MikaIcons.Key, stringResource(R.string.calls_credentials_row),
            when {
                probe == true -> stringResource(R.string.calls_probe_ok)
                probing || (configured && probe == null) -> stringResource(R.string.calls_probe_pending)
                else -> null
            },
            first = true,
            detail = when {
                !configured -> stringResource(R.string.calls_key_missing)
                probe == false -> stringResource(R.string.calls_probe_fail)
                else -> null
            },
            detailColor = if (credentialBad) MaterialTheme.colorScheme.error else MikaTheme.colors.ink3,
            onClick = { onOpen(answerSettingEditor(AnswerSettingRow.Credentials)) }.takeIf { canConfigure },
        ) {
            if (configured && probe != false) RowValue(stringResource(R.string.calls_configured_badge), ValueTone.Ok)
            RowChevron()
        }
        val modeLabel = stringResource(if (config.audioMode == CallAudioMode.DIRECT) R.string.calls_audio_direct else R.string.calls_audio_redirect)
        val verifiedLabel = stringResource(if (config.audioVerified) R.string.calls_audio_verified_short else R.string.calls_audio_unverified_short)
        ListRow(
            MikaIcons.Mic, stringResource(R.string.calls_audio_channel),
            stringResource(R.string.calls_audio_summary, modeLabel, verifiedLabel),
            onClick = { onOpen(answerSettingEditor(AnswerSettingRow.AudioChannel)) }.takeIf { canConfigure },
        ) { RowChevron() }
        state.audioDetail?.let { FooterNote(it) }
        CallFoldRow(MikaIcons.Bell, stringResource(R.string.calls_background_settings), backgroundOpen, "call-settings-background",
            subtitle = stringResource(backgroundStandbyStatus(config, state))) { backgroundOpen = !backgroundOpen }
        if (backgroundOpen) {
            if (config.autoAnswer) {
                CallBackgroundStandbyRow(config, state, canStartStandby, onStartStandby)
                if (!state.backgroundStandbyStarting) state.backgroundStandbyBlockedReason?.let { FooterNote(it) }
            }
            CallBatterySettingsRow(device, canConfigure)
            FooterNote(stringResource(R.string.calls_background_lock_screen_note))
            if (device.readiness.callNotificationsAllowed) {
                TextButton(onClick = device.callChannelAction, enabled = canConfigure, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.calls_notification_settings))
                }
            }
        }
    }
    GroupLabel(stringResource(R.string.calls_rules_section))
    GroupCard(Modifier.testTag("call-incoming-rules")) {
        StrangerAndTimeoutRows(config, canConfigure, onStrangerOnly, onRingTimeout)
        ListRow(MikaIcons.Phone, stringResource(R.string.calls_rule_scope), rulesSummary(config), first = false,
            onClick = { onOpen(answerSettingEditor(AnswerSettingRow.AnswerScope)) }.takeIf { canConfigure }) { RowChevron() }
        ListRow(MikaIcons.Clock, stringResource(R.string.calls_wait_row),
            stringResource(R.string.calls_rule_delay_summary, config.autoAnswerDelaySeconds),
            onClick = { onOpen(answerSettingEditor(AnswerSettingRow.AnswerDelay)) }.takeIf { canConfigure }) { RowChevron() }
        ListRow(MikaIcons.Doc, stringResource(R.string.calls_allowlist_row),
            stringResource(R.string.calls_count_numbers, config.allowedIncomingNumbers.size),
            onClick = { onOpen(answerSettingEditor(AnswerSettingRow.Allowlist)) }.takeIf { canConfigure }) { RowChevron() }
        ListRow(MikaIcons.Heart, stringResource(R.string.calls_self_answer_row),
            if (config.bypassIncomingNumbers.isEmpty()) stringResource(R.string.calls_bypass_empty)
            else stringResource(R.string.calls_count_numbers, config.bypassIncomingNumbers.size),
            onClick = { onOpen(answerSettingEditor(AnswerSettingRow.SelfAnswer)) }.takeIf { canConfigure }) { RowChevron() }
    }
    if (config.enabled) {
        TextButton(onClick = onStop, enabled = !stopBusy, modifier = Modifier.fillMaxWidth().testTag("call-disable")) {
            Text(stringResource(R.string.calls_disable_short), color = if (stopBusy) MikaTheme.colors.ink3 else MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun CallReadinessActions(
    config: CallConfig, state: CallRuntimeState, ready: Boolean, enabled: Boolean, stopBusy: Boolean,
    device: CallDeviceReadiness,
    onPrepare: () -> Unit, onStop: () -> Unit, onCredentials: () -> Unit,
    showStop: Boolean = true,
) {
    val canPrepare = state.shellConnected && device.phoneAllowed && device.callNotificationsAllowed
    Column(Modifier.fillMaxWidth().testTag("call-readiness").padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            when {
                !config.enabled -> stringResource(R.string.calls_system_default)
                ready -> stringResource(R.string.calls_limits, config.maxCallSeconds)
                else -> stringResource(R.string.calls_needs_setup)
            },
            style = MaterialTheme.typography.bodySmall, color = MikaTheme.colors.ink3,
        )
        if (canPrepare && state.prepared && !config.credentialsConfigured) {
            CallPrimaryButton(stringResource(R.string.calls_credentials), enabled, onCredentials)
        } else if (canPrepare && !ready) {
            CallPrimaryButton(stringResource(if (config.enabled) R.string.calls_prepare else R.string.calls_enable),
                enabled, onPrepare, Modifier.testTag("call-enable"))
        }
        if (config.enabled && (ready || showStop)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (ready) TextButton(onClick = onPrepare, enabled = enabled, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.calls_prepare))
                }
                if (showStop) TextButton(onClick = onStop, enabled = !stopBusy, modifier = Modifier.weight(1f).testTag("call-disable")) {
                    Text(stringResource(R.string.calls_disable_short))
                }
            }
        }
    }
}

private fun readinessSummary(config: CallConfig, ready: Boolean): Int = when {
    !config.enabled -> R.string.calls_disabled
    !ready -> R.string.calls_needs_setup
    !config.audioVerified -> R.string.calls_device_ready_to_test
    else -> R.string.calls_ready
}

@Composable
private fun autoAnswerSummary(config: CallConfig, state: CallRuntimeState): String = when (state.autoAnswerStage) {
    CallAutoAnswerStage.OFF -> stringResource(if (config.enabled) R.string.calls_auto_off else R.string.calls_system_default)
    CallAutoAnswerStage.BLOCKED -> state.autoAnswerBlockedReason ?: stringResource(R.string.calls_needs_setup)
    CallAutoAnswerStage.STANDBY -> stringResource(R.string.calls_auto_standby, config.autoAnswerDelaySeconds)
    CallAutoAnswerStage.BUSY -> stringResource(R.string.calls_auto_busy)
    CallAutoAnswerStage.PAUSED -> {
        val until = config.autoAnswerPausedUntilMs
        if (until == null) stringResource(R.string.calls_auto_paused)
        else {
            val locale = LocalContext.current.resources.configuration.locales[0]
            val formatter = remember(locale) { DateFormat.getTimeInstance(DateFormat.SHORT, locale) }
            stringResource(R.string.calls_auto_paused_until, formatter.format(Date(until)))
        }
    }
    CallAutoAnswerStage.COUNTDOWN, CallAutoAnswerStage.PREPARING, CallAutoAnswerStage.ANSWERING ->
        stringResource(R.string.calls_answer_pending)
}

@Composable
private fun rulesSummary(config: CallConfig): String = when {
    config.allowAllIncoming -> stringResource(R.string.calls_all_note)
    config.allowedIncomingNumbers.isEmpty() -> stringResource(R.string.calls_allowlist_empty)
    else -> stringResource(R.string.calls_list_count, config.allowedIncomingNumbers.size)
}

@Composable
internal fun CallFoldRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, expanded: Boolean,
    testTag: String, subtitle: String? = null, onClick: () -> Unit,
) {
    val expansion = stringResource(if (expanded) R.string.calls_collapse else R.string.calls_expand)
    Row(
        Modifier.fillMaxWidth().testTag(testTag).clickable(role = Role.Button, onClick = onClick)
            .semantics { stateDescription = expansion }.heightIn(min = 52.dp).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        IconTile(icon)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MikaTheme.colors.ink3) }
        }
        CallExpandIcon(expanded)
    }
}

@Composable
internal fun CallExpandIcon(expanded: Boolean) {
    Icon(
        MikaIcons.Chevron, null, Modifier.size(16.dp).rotate(if (expanded) -90f else 90f), tint = MikaTheme.colors.ink3,
    )
}

@Composable
internal fun CallPrimaryButton(text: String, enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Button(
        onClick = onClick, enabled = enabled, modifier = modifier.fillMaxWidth().heightIn(min = 48.dp),
        shape = MikaShapes.Button,
        colors = ButtonDefaults.buttonColors(containerColor = MikaTheme.colors.accent, contentColor = MikaTheme.colors.onAccent),
    ) { Text(text) }
}

@Composable
internal fun directionLabel(direction: CallDirection): String = stringResource(
    if (direction == CallDirection.INCOMING) R.string.calls_direction_in else R.string.calls_direction_out,
)

@Composable
private fun phaseLabel(phase: CallPhase): String = stringResource(when (phase) {
    CallPhase.DIALING -> R.string.calls_dialing
    CallPhase.RINGING -> R.string.calls_ringing
    CallPhase.CONNECTING -> R.string.calls_connecting
    CallPhase.ACTIVE -> R.string.calls_connected
    CallPhase.HOLDING -> R.string.calls_holding
    CallPhase.DISCONNECTING -> R.string.calls_disconnecting
    CallPhase.UNKNOWN -> R.string.calls_system_active
    CallPhase.IDLE -> R.string.calls_no_current
})

@Composable
private fun callVoiceLabel(id: String): String = when {
    id == "zh_female_vv_jupiter_bigtts" -> stringResource(R.string.calls_voice_gentle)
    id.startsWith("S_") -> stringResource(R.string.calls_voice_clone)
    "female" in id -> stringResource(R.string.calls_voice_female)
    "male" in id -> stringResource(R.string.calls_voice_male)
    id.isBlank() -> stringResource(R.string.calls_default)
    else -> id
}

@Composable
private fun CallAudioDialog(
    config: CallConfig, canVerify: Boolean, onVerify: (Boolean) -> Unit, onDismiss: () -> Unit, onMode: (CallAudioMode) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.calls_audio_channel)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                CallAudioMode.entries.forEachIndexed { index, option ->
                    RadioRow(
                        stringResource(if (option == CallAudioMode.DIRECT) R.string.calls_audio_direct else R.string.calls_audio_redirect),
                        selected = config.audioMode == option, onClick = { onMode(option) }, first = index == 0,
                    )
                }
                Row(
                    Modifier.fillMaxWidth().toggleable(config.audioVerified, enabled = canVerify, role = Role.Checkbox) { onVerify(it) },
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Checkbox(config.audioVerified, onCheckedChange = null, enabled = canVerify)
                    Text(stringResource(R.string.calls_audio_verified))
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.calls_save)) } },
    )
}

@Composable
private fun StrangerAndTimeoutRows(
    config: CallConfig, enabled: Boolean, onStrangerOnly: (Boolean) -> Unit, onRingTimeout: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    var denied by remember { mutableStateOf(false) }
    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        PhoneContacts.clearCache()
        if (granted) {
            denied = false
            onStrangerOnly(true)
        } else denied = true
    }
    SettingSwitch(
        stringResource(R.string.calls_stranger_only),
        stringResource(R.string.calls_stranger_only_sub),
        config.answerStrangersOnly, enabled, "call-stranger-only",
    ) { on ->
        if (!on) {
            denied = false
            onStrangerOnly(false)
        } else if (PhoneContacts(context).granted()) {
            denied = false
            onStrangerOnly(true)
        } else request.launch(Manifest.permission.READ_CONTACTS)
    }
    if (denied) Text(stringResource(R.string.calls_stranger_only_denied),
        Modifier.padding(horizontal = 16.dp, vertical = 4.dp), color = MaterialTheme.colorScheme.error,
        style = MaterialTheme.typography.bodySmall)
    SettingSwitch(
        stringResource(R.string.calls_ring_timeout),
        stringResource(R.string.calls_ring_timeout_sub),
        config.answerOnRingTimeout, enabled, "call-ring-timeout",
        onRingTimeout,
    )
}

@Composable
private fun SettingSwitch(
    title: String, subtitle: String, checked: Boolean, enabled: Boolean, tag: String, onChange: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().toggleable(checked, enabled = enabled, role = Role.Switch, onValueChange = onChange)
            .padding(horizontal = 16.dp, vertical = 12.dp).testTag(tag),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MikaTheme.colors.ink3)
        }
        Switch(checked, onCheckedChange = null, enabled = enabled)
    }
}

@Composable
private fun CallScopeDialog(all: Boolean, onDismiss: () -> Unit, onSave: suspend (Boolean) -> Boolean) {
    var chosen by rememberSaveable { mutableStateOf(all) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(R.string.calls_rule_scope)) },
        text = {
            Column {
                RadioRow(stringResource(R.string.calls_only_list), !chosen, { chosen = false }, first = true, enabled = !busy)
                RadioRow(stringResource(R.string.calls_all_incoming), chosen, { chosen = true },
                    subtitle = stringResource(R.string.calls_all_note), enabled = !busy)
                if (error) Text(stringResource(R.string.calls_action_failed), color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            TextButton(enabled = !busy, onClick = {
                busy = true
                scope.launch {
                    try { if (onSave(chosen)) onDismiss() else error = true } finally { busy = false }
                }
            }) { Text(stringResource(R.string.calls_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text(stringResource(R.string.calls_cancel)) } },
    )
}

@Composable
private fun CallDelayDialog(seconds0: Int, onDismiss: () -> Unit, onSave: suspend (Int) -> Boolean) {
    var delay by rememberSaveable { mutableStateOf(seconds0.toString()) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val minDelay = CallConfig.MIN_AUTO_ANSWER_DELAY_SECONDS
    val maxDelay = CallConfig.MAX_AUTO_ANSWER_DELAY_SECONDS
    val seconds = delay.toIntOrNull()?.takeIf { it in minDelay..maxDelay }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(R.string.calls_wait_row)) },
        text = {
            Column {
                OutlinedTextField(delay, { delay = it.filter(Char::isDigit).take(2) },
                    modifier = Modifier.fillMaxWidth().testTag("call-answer-delay"),
                    label = { Text(stringResource(R.string.calls_delay_field, minDelay, maxDelay)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, enabled = !busy)
                if (error) Text(stringResource(R.string.calls_action_failed), color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            TextButton(enabled = !busy && seconds != null, onClick = {
                val value = seconds ?: return@TextButton
                busy = true
                scope.launch {
                    try { if (onSave(value)) onDismiss() else error = true } finally { busy = false }
                }
            }) { Text(stringResource(R.string.calls_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text(stringResource(R.string.calls_cancel)) } },
    )
}

@Composable
private fun ContactChoiceSummary(raw: String) {
    val book = rememberPhoneContacts()
    val labels = remember(raw, book.granted()) {
        raw.split(',', '，', '\n').map { it.trim() }.filter { it.isNotEmpty() }.map { book.label(it) }
    }
    if (labels.isNotEmpty()) Text(labels.joinToString("\n"), style = MaterialTheme.typography.bodySmall, color = MikaTheme.colors.ink3)
}

@Composable
private fun CallAllowlistDialog(existing: Set<String>, onDismiss: () -> Unit, onSave: suspend (Set<String>) -> Boolean) {
    var numbers by rememberSaveable { mutableStateOf(existing.sorted().joinToString("\n")) }
    var picking by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val normalized = parseCallNumberList(numbers)
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(R.string.calls_allowlist_row)) },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ContactChoiceSummary(numbers)
                TextButton(onClick = { picking = true }, enabled = !busy) { Text(stringResource(R.string.calls_contacts_pick)) }
                OutlinedTextField(numbers, { numbers = it }, modifier = Modifier.fillMaxWidth().testTag("call-allowlist"),
                    label = { Text(stringResource(R.string.calls_allowlist)) }, minLines = 2, maxLines = 5, enabled = !busy,
                    textStyle = TextStyle(textDirection = TextDirection.Ltr), isError = normalized == null)
                if (error) Text(stringResource(R.string.calls_action_failed), color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            TextButton(enabled = !busy && normalized != null, onClick = {
                val next = normalized ?: return@TextButton
                busy = true
                scope.launch {
                    try { if (onSave(next)) onDismiss() else error = true } finally { busy = false }
                }
            }) { Text(stringResource(R.string.calls_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text(stringResource(R.string.calls_cancel)) } },
    )
    if (picking) ContactMultiPicker(normalized.orEmpty(), { picking = false }) { chosen ->
        val added = chosen.mapNotNull { CallPolicy.incomingNumber(it.number) }
        numbers = (parseCallNumberList(numbers).orEmpty() + added).joinToString("\n")
        picking = false
    }
}

@Composable
private fun CallGreetingDialog(initial: String, onDismiss: () -> Unit, title: Int = R.string.calls_incoming_greeting, onSave: suspend (String) -> Boolean) {
    var greeting by rememberSaveable { mutableStateOf(initial) }
    SimpleTextDialog(stringResource(title), greeting, { greeting = it.take(CallPolicy.MAX_GREETING_CHARS) },
        stringResource(R.string.calls_greeting_hint), onDismiss, { onSave(greeting) })
}

@Composable
private fun CallDefaultSimDialog(options: List<CallSim>, selected: String?, onDismiss: () -> Unit, onChoose: (String?) -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(R.string.calls_default_sim_settings)) },
        text = {
            Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                RadioRow(stringResource(R.string.calls_sim_ask_each_time), selected == null, onClick = { onChoose(null) }, first = true)
                options.forEach { sim -> RadioRow(sim.label, selected == sim.id, onClick = { onChoose(sim.id) }) }
                if (selected != null && options.none { it.id == selected }) Text(stringResource(R.string.calls_sim_unavailable))
            }
        }, confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.calls_cancel)) } })
}

@Composable
private fun CallDurationDialog(initial: Int, onDismiss: () -> Unit, onSave: suspend (Int) -> Boolean) {
    var seconds by rememberSaveable { mutableStateOf(initial.toString()) }
    val value = seconds.toIntOrNull()?.takeIf { it in 15..CallPolicy.MAX_SECONDS }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(R.string.calls_duration_row)) },
        text = {
            Column {
                OutlinedTextField(seconds, { seconds = it.filter(Char::isDigit).take(3) }, modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.calls_duration)) }, singleLine = true, enabled = !busy,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                if (error) Text(stringResource(R.string.calls_action_failed), color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            TextButton(enabled = !busy && value != null, onClick = {
                val next = value ?: return@TextButton
                busy = true
                scope.launch { try { if (onSave(next)) onDismiss() else error = true } finally { busy = false } }
            }) { Text(stringResource(R.string.calls_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text(stringResource(R.string.calls_cancel)) } },
    )
}

@Composable
private fun SimpleTextDialog(
    title: String, value: String, onValue: (String) -> Unit, hint: String,
    onDismiss: () -> Unit, onSave: suspend () -> Boolean,
) {
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(value, onValue, modifier = Modifier.fillMaxWidth(), label = { Text(title) },
                    supportingText = { Text(hint) }, maxLines = 3, enabled = !busy)
                if (error) Text(stringResource(R.string.calls_action_failed), color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            TextButton(enabled = !busy, onClick = {
                busy = true
                scope.launch { try { if (onSave()) onDismiss() else error = true } finally { busy = false } }
            }) { Text(stringResource(R.string.calls_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text(stringResource(R.string.calls_cancel)) } },
    )
}


@Composable
private fun CallCredentialsDialog(runtime: CallRuntime, onDismiss: () -> Unit, onResult: (String) -> Unit) {
    var legacy by remember { mutableStateOf(false) }
    var key by remember { mutableStateOf("") }
    var appId by remember { mutableStateOf("") }
    var speaker by rememberSaveable { mutableStateOf(runtime.settings.state.value.speaker) }
    var error by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val saved = stringResource(R.string.calls_key_saved)
    val voiceOk = canEditCallSpeaker(speaker, runtime.settings.state.value.speaker)
    val keyReady = key.isBlank() || (!legacy || appId.isNotBlank())
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(R.string.calls_credentials)) },
        text = {
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.calls_voice_row), style = MaterialTheme.typography.titleSmall)
                Text(callVoiceLabel(speaker), color = MikaTheme.colors.ink3, style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    speaker, { speaker = it; error = false }, modifier = Modifier.fillMaxWidth().testTag("call-voice-field"),
                    label = { Text(stringResource(R.string.calls_speaker)) }, singleLine = true, enabled = !busy,
                    isError = !voiceOk, supportingText = { Text(stringResource(R.string.calls_speaker_edit_note)) },
                )
                HorizontalDivider()
                Text(stringResource(R.string.calls_credentials_note))
                Row(
                    Modifier.fillMaxWidth()
                        .toggleable(legacy, enabled = !busy, role = Role.Checkbox) { legacy = it; key = ""; appId = ""; error = false }
                        .heightIn(min = 48.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Checkbox(legacy, onCheckedChange = null, enabled = !busy)
                    Text(stringResource(R.string.calls_legacy_credentials))
                }
                if (legacy) OutlinedTextField(
                    appId, { appId = it; error = false }, modifier = Modifier.fillMaxWidth(),
                    label = { Text("App ID") }, singleLine = true, enabled = !busy,
                )
                OutlinedTextField(
                    key, { key = it; error = false }, modifier = Modifier.fillMaxWidth(),
                    label = { Text(if (legacy) "Access Key" else "API Key") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    singleLine = true, enabled = !busy,
                )
                if (error) Text(stringResource(R.string.calls_action_failed), color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            TextButton(enabled = !busy && voiceOk && keyReady && (key.isNotBlank() || speaker.trim() != runtime.settings.state.value.speaker), onClick = {
                busy = true
                scope.launch {
                    try {
                        withContext(Dispatchers.IO) {
                            if (key.isNotBlank()) {
                                runtime.settings.saveCredentials(if (legacy) appId else "", if (legacy) key else "", if (legacy) "" else key)
                            }
                            runtime.settings.save(runtime.settings.state.value.copy(speaker = speaker))
                        }
                        key = ""; appId = ""
                        onResult(saved)
                        onDismiss()
                    } catch (e: CancellationException) { throw e }
                    catch (_: Exception) { error = true }
                    finally { busy = false }
                }
            }) { Text(stringResource(R.string.calls_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text(stringResource(R.string.calls_cancel)) } },
    )
}

private fun canEditCallSpeaker(candidate: String, current: String): Boolean {
    val speaker = candidate.trim()
    return !speaker.startsWith("S_", ignoreCase = true) || speaker == current
}

private fun parseCallNumberList(input: String): Set<String>? {
    val entries = input.split(',', '，', '،', ';', '；', '\n').map(String::trim).filter(String::isNotEmpty)
    val normalized = entries.map { CallPolicy.incomingNumber(it) ?: return null }.toSet()
    return normalized.takeIf { it.size <= CallPolicy.MAX_ALLOWED_NUMBERS }
}

@Composable
private fun CallBypassDialog(existing: Set<String>, onDismiss: () -> Unit, onSave: suspend (Set<String>) -> Boolean) {
    var numbers by rememberSaveable { mutableStateOf(existing.sorted().joinToString("\n")) }
    var picking by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val normalized = parseCallNumberList(numbers)
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(R.string.calls_bypass_title)) },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.calls_bypass_note))
                ContactChoiceSummary(numbers)
                TextButton(onClick = { picking = true }, enabled = !busy) { Text(stringResource(R.string.calls_contacts_pick)) }
                OutlinedTextField(
                    numbers, { numbers = it; error = false },
                    label = { Text(stringResource(R.string.calls_number)) },
                    modifier = Modifier.fillMaxWidth().testTag("call-bypass-numbers"),
                    supportingText = { Text(if (normalized != null) stringResource(R.string.calls_allowlist_hint)
                        else stringResource(R.string.calls_allowlist_invalid, CallPolicy.MAX_ALLOWED_NUMBERS)) },
                    textStyle = TextStyle(textDirection = TextDirection.Ltr),
                    isError = normalized == null, minLines = 2, maxLines = 6, enabled = !busy,
                )
                if (error) Text(stringResource(R.string.calls_action_failed), color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            TextButton(enabled = !busy && normalized != null, onClick = {
                val next = normalized ?: return@TextButton
                busy = true
                scope.launch {
                    try {
                        if (onSave(next)) onDismiss() else error = true
                    } finally { busy = false }
                }
            }) { Text(stringResource(R.string.calls_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text(stringResource(R.string.calls_cancel)) } },
    )
    if (picking) ContactMultiPicker(parseCallNumberList(numbers).orEmpty(), { picking = false }) { chosen ->
        val added = chosen.mapNotNull { CallPolicy.incomingNumber(it.number) }
        numbers = (parseCallNumberList(numbers).orEmpty() + added).joinToString("\n")
        picking = false
    }
}
