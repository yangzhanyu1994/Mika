package com.wangzan101.mika.assistant

import android.Manifest
import android.app.KeyguardManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import com.wangzan101.mika.MikaApp
import com.wangzan101.mika.R
import com.wangzan101.mika.chat.ResearchPhase
import com.wangzan101.mika.chat.WorkReply
import com.wangzan101.mika.chat.routeAssistantConversation
import com.wangzan101.mika.chat.routeLiveWeather
import com.wangzan101.mika.chat.invokesSavedSkill
import com.wangzan101.mika.memory.MemoryRuntime
import com.wangzan101.mika.ui.OrbMotion
import com.wangzan101.mika.ui.orbMotion
import com.wangzan101.mika.ui.toolOrbMotion
import com.wangzan101.mika.core.model.ModelRole
import com.wangzan101.mika.cap.sandbox.AndroidSandbox
import com.wangzan101.mika.cap.sandbox.Attachments
import com.wangzan101.mika.cap.sandbox.SandboxDirs
import com.wangzan101.mika.cap.sandbox.SandboxState
import com.wangzan101.mika.core.agent.AttachmentLines
import com.wangzan101.mika.core.agent.Conversation
import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.core.agent.Turn
import com.wangzan101.mika.i18n.AppLanguage
import com.wangzan101.mika.quit.MikaQuit
import com.wangzan101.mika.task.TaskService
import com.wangzan101.mika.ui.AttachFiles
import com.wangzan101.mika.ui.MarkdownModel
import com.wangzan101.mika.ui.VoiceCopy
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.theme.animationsOff
import com.wangzan101.mika.voice.HoldToTalk
import com.wangzan101.mika.voice.SpeechEngineSpec
import com.wangzan101.mika.voice.SpeechEvent
import com.wangzan101.mika.media.readAloud
import com.wangzan101.mika.voice.VoicePlayback
import com.wangzan101.mika.voice.VoicePlayer
import com.wangzan101.mika.voice.assistantSpeech
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.IOException
import java.util.UUID

class AssistantOverlayActivity : ComponentActivity() {
    private val app get() = application as MikaApp
    private var invocation: AssistantCapture? = null
    private var text by mutableStateOf("")
    private var partial by mutableStateOf("")
    private var status by mutableStateOf<String?>(null)
    private var listening by mutableStateOf(false)
    private var converting by mutableStateOf(false)
    private var level by mutableFloatStateOf(0f)
    private var screenshot by mutableStateOf<Bitmap?>(null)
    private var photo by mutableStateOf<Bitmap?>(null)
    private var sending by mutableStateOf(false)
    private var sendAfterReply by mutableStateOf(false)
    private var mode by mutableStateOf(AssistantMode.Auto)
    private var conversationId: String? = null
    private var reply by mutableStateOf<AssistantReply?>(null)
    private var history by mutableStateOf<List<AssistantExchange>>(emptyList())
    private var pendingQuestion by mutableStateOf<String?>(null)
    private var pendingAfter = 0
    private var pendingChat = false
    private var resolvedMode by mutableStateOf<AssistantMode?>(null)
    private var revealTurn by mutableStateOf<Int?>(null)
    private var previousRun: String? = null
    private var replyJob: Job? = null
    private val readoutPlayer by lazy { VoicePlayer(applicationContext) }
    private var playbackJob: Job? = null
    private var closeAfterPlayback: Job? = null
    private var interactions = 0L
    private var pendingPhoto: Uri? = null
    private var photoUri: Uri? = null
    private var voiceJob: Job? = null
    private var screenJob: Job? = null
    private var finishSpeech = false
    private var afterSpeech: (() -> Unit)? = null
    private var autoListen = true
    private var permissionPending = false
    private var resumed = false

    private val audioPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        permissionPending = false
        if (granted) {
            if (resumed) startSpeech() else autoListen = true
        } else status = getString(R.string.voice_permission_title)
    }
    private val cameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        permissionPending = false
        if (granted) launchCamera()
        else status = getString(R.string.assistant_camera_permission)
    }
    private val camera = registerForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val uri = pendingPhoto ?: return@registerForActivityResult
        pendingPhoto = null
        if (!ok) AttachFiles.discardCamera(this, uri)
        else lifecycleScope.launch {
            val preview = withContext(Dispatchers.IO) {
                runCatching {
                    ImageDecoder.decodeBitmap(ImageDecoder.createSource(contentResolver, uri)) { decoder, info, _ ->
                        decoder.setTargetSampleSize((maxOf(info.size.width, info.size.height) + 767) / 768)
                    }
                }.getOrNull()
            }
            if (preview == null || isFinishing) {
                AttachFiles.discardCamera(this@AssistantOverlayActivity, uri)
                if (!isFinishing) status = getString(R.string.attach_gone)
            } else {
                removePhoto()
                photoUri = uri
                photo = preview
            }
        }
    }

    override fun attachBaseContext(newBase: Context) = super.attachBaseContext(AppLanguage.wrap(newBase))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val active = AssistantCapture.active
        if (active == null || active.id != intent.getStringExtra(AssistantCapture.EXTRA_CAPTURE) || locked()) {
            finish()
            return
        }
        invocation = active
        if (savedInstanceState != null) {
            mode = AssistantMode.entries.firstOrNull { it.name == savedInstanceState.getString("assistantMode") }
                ?: AssistantMode.Auto
            resolvedMode = AssistantMode.entries.firstOrNull { it.name == savedInstanceState.getString("assistantResolvedMode") }
            text = savedInstanceState.getString("assistantQuestion").orEmpty()
            sendAfterReply = savedInstanceState.getBoolean("assistantSendAfterReply")
            if (text.isNotBlank()) autoListen = false
            savedInstanceState.getString("assistantConversation")?.let { id ->
                autoListen = false
                pendingQuestion = savedInstanceState.getString("assistantPendingQuestion")
                pendingAfter = savedInstanceState.getInt("assistantPendingAfter", 0)
                pendingChat = savedInstanceState.getBoolean("assistantPendingChat")
                revealTurn = pendingAfter.takeIf { pendingQuestion != null }
                previousRun = savedInstanceState.getString("assistantPreviousRun")
                showReply(id)
            }
        }
        MikaQuit.resume(this)
        lifecycleScope.launch { active.invalidated.collect { if (it) finish() } }
        val transparent = android.graphics.Color.TRANSPARENT
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(transparent),
            navigationBarStyle = SystemBarStyle.dark(transparent),
        )
        window.isNavigationBarContrastEnforced = false
        val appearance = AssistantAppearance(this)
        setContent {
            val voiceSettings by app.voiceSettings.state.collectAsState()
            MikaTheme(dark = isSystemInDarkTheme(), reduceMotion = animationsOff(this)) {
                AssistantOverlay(
                    history = history,
                    revealTurn = revealTurn,
                    pendingQuestion = pendingQuestion,
                    awaitingReply = pendingQuestion != null,
                    displayName = appearance.name,
                    animateAvatar = appearance.animate,
                    text = text,
                    partial = partial,
                    status = status,
                    listening = listening,
                    converting = converting,
                    level = { level },
                    screenshot = screenshot,
                    photo = photo,
                    sending = sending,
                    sendQueued = sendAfterReply,
                    mode = mode,
                    resolvedMode = resolvedMode,
                    onMode = {
                        noteInteraction()
                        mode = it
                        status = null
                    },
                    reply = reply,
                    onReadResult = if (voiceSettings.enabled && !voiceSettings.choice?.model.isNullOrBlank()) ({
                        reply?.let { latest -> playResult(latest.collapsedBody ?: latest.body, latest.title) }
                    }) else null,
                    onOpenConversation = ::openConversation,
                    onText = { noteInteraction(); text = it },
                    onEdit = { stopSpeech(keepText = true) },
                    onVoice = { if (listening) finishSpeech = true else if (!converting) requestSpeech() },
                    onScreenshot = ::attachScreenshot,
                    onPhoto = { finishThen(::requestCamera) },
                    onRemoveScreenshot = ::removeScreenshot,
                    onRemovePhoto = ::removePhoto,
                    onClear = ::clear,
                    onSend = { noteInteraction(); finishThen(::send) },
                    onClose = { finish() },
                )
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("assistantConversation", conversationId)
        outState.putString("assistantQuestion", text)
        outState.putBoolean("assistantSendAfterReply", sendAfterReply)
        outState.putString("assistantMode", mode.name)
        outState.putString("assistantResolvedMode", resolvedMode?.name)
        outState.putString("assistantPendingQuestion", pendingQuestion)
        outState.putInt("assistantPendingAfter", pendingAfter)
        outState.putBoolean("assistantPendingChat", pendingChat)
        outState.putString("assistantPreviousRun", previousRun)
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        if (invocation == null || locked()) {
            finish()
        } else if (autoListen) {
            autoListen = false
            requestSpeech()
        } else sendQueuedFollowUp()
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        noteInteraction()
    }

    private fun noteInteraction() {
        interactions++
        closeAfterPlayback?.cancel()
        closeAfterPlayback = null
    }

    override fun onPause() {
        resumed = false
        noteInteraction()
        stopPlayback()
        stopSpeech(keepText = true)
        super.onPause()
    }

    override fun onStop() {
        super.onStop()
        if (!permissionPending && pendingPhoto == null && !isChangingConfigurations && !sending) finish()
    }

    override fun onDestroy() {
        if (isFinishing) {
            pendingPhoto?.let { AttachFiles.discardCamera(this, it) }
            removePhoto()
            invocation?.let { current ->
                if (!current.invalidated.value) current.dismiss()
                current.invalidate()
            }
        }
        super.onDestroy()
    }

    private fun locked() = getSystemService(KeyguardManager::class.java).isKeyguardLocked

    private fun requestSpeech() {
        if (!resumed || isFinishing || sending) return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) startSpeech()
        else {
            permissionPending = true
            audioPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun startSpeech() {
        if (listening || converting || isFinishing || sending) return
        stopPlayback()
        partial = ""
        status = null
        finishSpeech = false
        listening = true
        voiceJob = lifecycleScope.launch {
            var finalText: String? = null
            try {
                assistantSpeech(app.voice, { finishSpeech }) { event ->
                    when (event) {
                        is SpeechEvent.Level -> {
                            level = event.value
                            if (level >= 0.12f && closeAfterPlayback != null) noteInteraction()
                        }
                        is SpeechEvent.Partial -> {
                            partial = event.stable + event.tail
                            if (partial.isNotBlank() && closeAfterPlayback != null) noteInteraction()
                        }
                        SpeechEvent.Converting -> { converting = true; listening = false }
                        is SpeechEvent.Final -> finalText = event.text.trim()
                        is SpeechEvent.Failed -> {
                            if (closeAfterPlayback != null) noteInteraction()
                            status = VoiceCopy.failure(event.why)
                        }
                        SpeechEvent.EndOfSpeech -> Unit
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("MikaAssistant", "Dictation failed", e)
                status = getString(R.string.voice_mic_unavailable)
            }
            val recognized = finalText?.ifBlank { partial } ?: partial
            if (recognized.isNotBlank() && closeAfterPlayback != null) noteInteraction()
            if (recognized.isNotBlank()) text = HoldToTalk.join(text, recognized)
            partial = ""
            level = 0f
            listening = false
            converting = false
            val action = afterSpeech
            afterSpeech = null
            if (resumed) {
                if (action != null) action()
                // Ending microphone capture and requesting a different UI action are separate.
                else if (!finalText.isNullOrBlank()) send()
            }
        }
    }

    private fun stopPlayback() {
        if (playbackJob?.isActive == true) {
            playbackJob?.cancel()
            readoutPlayer.stop()
            status = null
        }
        playbackJob = null
    }

    private fun playCompletedResult(turn: Turn) {
        if (!resumed || listening || converting || isFinishing || turn.outcome.isBlank()) return
        if (!app.voiceSettings.state.value.autoRead) return
        val uncertain = turn.status == "unverified"
        val fallback = getString(if (uncertain) R.string.chat_unverified else R.string.task_done)
        val headline = when {
            uncertain -> turn.note?.takeIf(String::isNotBlank) ?: WorkReply.from(turn.outcome).summary
            turn.research -> turn.outcome
            else -> WorkReply.from(turn.outcome).summary
        }
        playResult(headline, fallback)
    }

    private fun playResult(headline: String, fallback: String) {
        if (!resumed || listening || converting || isFinishing) return
        val voice = app.voiceSettings.state.value
        if (!voice.enabled || voice.choice?.model.isNullOrBlank()) return
        stopPlayback()
        val activityBefore = interactions
        playbackJob = lifecycleScope.launch {
            status = getString(R.string.assistant_reading_result)
            val played = try {
                val words = withContext(Dispatchers.Default) {
                    assistantSpokenSummary(MarkdownModel.toPlainText(headline), fallback)
                }
                var finished = false
                if (words.isNotBlank()) readAloud(words, app.voiceSettings, app.providers::ttsModel) {
                    readoutPlayer.play(it.bytes, it.mime)
                    finished = readoutPlayer.state.first { state ->
                        state == VoicePlayback.Idle || state is VoicePlayback.Error
                    } == VoicePlayback.Idle
                }
                finished
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                false
            }
            playbackJob = null
            status = if (played) null else getString(R.string.voice_play_failed, getString(R.string.retry))
            if (played && activityBefore == interactions) offerVoiceFollowUp()
        }
    }

    private fun offerVoiceFollowUp() {
        if (!resumed || listening || converting || sending || sendAfterReply || isFinishing || text.isNotBlank() ||
            screenshot != null || photo != null ||
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED ||
            app.voice.spec() is SpeechEngineSpec.Unavailable
        ) return
        closeAfterPlayback = lifecycleScope.launch {
            delay(3_000)
            if (resumed && !isFinishing && !sending && text.isBlank() && partial.isBlank()) finish()
        }
        startSpeech()
    }

    private fun stopSpeech(keepText: Boolean) {
        voiceJob?.cancel()
        voiceJob = null
        if (keepText && partial.isNotBlank()) text = HoldToTalk.join(text, partial)
        afterSpeech = null
        partial = ""
        level = 0f
        listening = false
        converting = false
    }

    private fun finishThen(action: () -> Unit) {
        if (sending || afterSpeech != null) return
        if (!listening && !converting) action()
        else {
            afterSpeech = action
            finishSpeech = true
        }
    }

    private fun requestCamera() {
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) launchCamera()
        else {
            permissionPending = true
            cameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    private fun launchCamera() {
        if (isFinishing || pendingPhoto != null) return
        try {
            val uri = AttachFiles.newCameraUri(this)
            pendingPhoto = uri
            camera.launch(uri)
        } catch (e: RuntimeException) {
            pendingPhoto?.let { AttachFiles.discardCamera(this, it) }
            pendingPhoto = null
            status = getString(R.string.attach_no_camera)
        }
    }

    private fun removePhoto() {
        photoUri?.let { AttachFiles.discardCamera(this, it) }
        photoUri = null
        photo = null
    }

    private fun attachScreenshot() {
        val capture = invocation ?: return
        if (screenJob?.isActive == true) return
        if (!capture.canShare(app)) {
            status = getString(R.string.assistant_screenshot_unavailable)
            return
        }
        screenJob = lifecycleScope.launch {
            val bitmap = withTimeoutOrNull(1_200) { capture.screenshot.filterNotNull().first() }
            if (bitmap != null && capture.canShare(app)) {
                screenshot = bitmap
                status = null
            } else {
                status = getString(R.string.assistant_screenshot_unavailable)
            }
        }
    }

    private fun removeScreenshot() {
        screenJob?.cancel()
        screenshot = null
    }

    private fun clear() {
        sendAfterReply = false
        stopSpeech(keepText = false)
        text = ""
        status = null
        removeScreenshot()
        removePhoto()
    }

    private fun sendQueuedFollowUp() {
        if (!sendAfterReply || pendingQuestion != null || !resumed || sending || isFinishing) return
        sendAfterReply = false
        send()
    }

    private fun send() {
        if (sending || sendAfterReply || locked() || isFinishing) return
        val words = text.trim()
        if (words.isEmpty() && screenshot == null && photoUri == null) return
        if (pendingQuestion != null) {
            sendAfterReply = true
            return
        }
        stopPlayback()
        sending = true
        status = null
        lifecycleScope.launch {
            val existing = conversationId
            val id = existing ?: app.conversations.newConversation()
            fun dispatch(goal: String) {
                if (existing == null) TaskService.start(this@AssistantOverlayActivity, goal, id)
                else if (!TaskService.enqueueFollowUp(this@AssistantOverlayActivity, id, UUID.randomUUID().toString(), goal)) {
                    throw IOException("Could not enqueue the follow-up")
                }
            }
            try {
                screenJob?.join()
                val screen = screenshot
                val cameraFile = photoUri?.let { AttachFiles.cameraFile(this@AssistantOverlayActivity, it) }
                if (words.isEmpty() && screen == null && cameraFile == null) return@launch
                if (screen != null && invocation?.canShare(app) != true) {
                    screenshot = null
                    status = getString(R.string.appaccess_share_expired)
                    return@launch
                }
                val priorTurns = if (existing == null) emptyList() else withContext(Dispatchers.IO) { app.conversations.turns(id) }
                val baseline = priorTurns.size
                val oldRun = app.taskState.run(id)?.key
                val automatic = mode == AssistantMode.Auto && screen == null && cameraFile == null
                val knownRoute = if (automatic) {
                    val skillMatch = withContext(Dispatchers.IO) {
                        invokesSavedSkill(words, MemoryRuntime.of(this@AssistantOverlayActivity).skills().list())
                    }
                    if (skillMatch) ModelRole.Main else routeLiveWeather(words, app.researchChat.canSearch())
                } else null
                val adaptive = automatic && knownRoute != ModelRole.Main && app.researchChat.canDelegate()
                val target = when {
                    screen != null || cameraFile != null || mode == AssistantMode.Work -> ModelRole.Main
                    mode == AssistantMode.Chat || adaptive -> ModelRole.Chat
                    knownRoute != null -> knownRoute
                    else -> {
                        status = getString(R.string.assistant_routing)
                        val latestWork = priorTurns.lastOrNull()?.takeIf {
                            !it.research && it.status in setOf("succeeded", "unverified")
                        }
                        routeAssistantConversation(words, app.providers, latestWork)
                    }
                }
                status = null
                if (!resumed || locked() || isFinishing) return@launch
                if (target == null) {
                    status = getString(R.string.assistant_routing_unavailable)
                    return@launch
                }
                if (app.providers.configFor(target) == null) {
                    status = getString(R.string.assistant_model_required)
                    return@launch
                }
                if (target == ModelRole.Chat) {
                    // The previous reply can reach disk just before ResearchChat releases its draft.
                    val previousDraft = app.researchChat.pending.value[id]
                    if (previousDraft != null && priorTurns.any { it.research && it.atMs == previousDraft.startedAt }) {
                        withTimeoutOrNull(2_000) { app.researchChat.pending.first { it[id] == null } }
                    }
                    if (!resumed || locked() || isFinishing) return@launch
                    val handoff: (() -> Boolean)? = if (automatic) {
                        { handOffToWork(id, words, ::dispatch) }
                    } else null
                    if (!app.researchChat.send(id, words, onWork = handoff)) throw IOException("Chat submission failed")
                } else {
                    if (app.teach.blocksTasks()) {
                        status = getString(R.string.teach_blocked_task)
                        return@launch
                    }
                    if (screen == null && cameraFile == null) withContext(Dispatchers.IO) { dispatch(words) }
                    else submitImages(id, words, screen, cameraFile, ::dispatch)
                }
                pendingChat = target == ModelRole.Chat
                resolvedMode = when {
                    adaptive -> null
                    pendingChat -> AssistantMode.Chat
                    else -> AssistantMode.Work
                }
                removeScreenshot()
                removePhoto()
                text = ""
                pendingQuestion = words
                pendingAfter = baseline
                revealTurn = baseline
                previousRun = oldRun
                if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) showReply(id) else finish()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("MikaAssistant", "Task submission failed", e)
                status = getString(R.string.assistant_send_failed)
            } finally {
                sending = false
                sendQueuedFollowUp()
            }
        }
    }

    private fun handOffToWork(id: String, words: String, dispatch: (String) -> Unit): Boolean {
        if (isDestroyed || isFinishing || conversationId != id || pendingQuestion != words) return false
        val problem = when {
            !resumed || locked() -> R.string.assistant_send_failed
            app.providers.configFor(ModelRole.Main) == null -> R.string.assistant_model_required
            app.teach.blocksTasks() -> R.string.teach_blocked_task
            else -> try {
                dispatch(words)
                null
            } catch (e: Exception) {
                Log.w("MikaAssistant", "Work handoff failed", e)
                R.string.assistant_send_failed
            }
        }
        if (problem != null) {
            pendingQuestion = null
            sendAfterReply = false
            if (text.isBlank()) text = words
            reply = null
            status = getString(problem)
        } else {
            pendingChat = false
            resolvedMode = AssistantMode.Work
            showReply(id)
        }
        return true
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun showReply(id: String) {
        conversationId = id
        replyJob?.cancel()
        if (pendingQuestion != null) reply = AssistantReply(
            getString(R.string.runtime_preparing), waiting = true, motion = OrbMotion.Connecting,
        )
        val useChat = pendingChat
        val ignoredKey = previousRun
        val live = app.taskState.runs.map { runs ->
            runs.firstOrNull { it.conversationId == id && it.key != ignoredKey }
        }.distinctUntilChanged().flatMapLatest { run -> run?.state ?: flowOf(null) }
        val saved = app.conversations.changes.filter { it == id }.onStart { emit(id) }.map {
            withContext(Dispatchers.IO) { app.conversations.turns(id) }
        }

        replyJob = lifecycleScope.launch {
            val startupTimeout = launch {
                delay(15_000)
                if (pendingQuestion != null) reply = AssistantReply(getString(R.string.assistant_status_unavailable))
            }
            var terminalWorkReply: AssistantReply? = null
            combine(live, saved, app.researchChat.pending) { state, turns, chats ->
                Triple(state, turns, chats[id])
            }.collect { (state, turns, draft) ->
                history = turns.map {
                    val result = savedReply(it)
                    AssistantExchange(AttachmentLines.words(it.userText), result.body, result.collapsedBody)
                }
                val finishedIndex = if (pendingQuestion != null) {
                    (pendingAfter until turns.size).firstOrNull { turns[it].research == useChat }
                } else null
                if (pendingQuestion != null && finishedIndex == null) {
                    revealTurn = turns.size
                    if (useChat && draft != null) {
                        startupTimeout.cancel()
                        if (draft.phase != ResearchPhase.Thinking) resolvedMode = AssistantMode.Chat
                        reply = AssistantReply(
                            getString(if (draft.phase == ResearchPhase.Searching) R.string.assistant_searching else R.string.assistant_thinking),
                            body = draft.text, running = true, waiting = true, motion = draft.phase.orbMotion,
                        )
                    } else if (!useChat && state != null && state != TaskState.Idle) {
                        startupTimeout.cancel()
                        reply = liveReply(state)
                        if (state is TaskState.Succeeded || state is TaskState.Failed || state == TaskState.Cancelled) terminalWorkReply = reply
                    } else if (!useChat && terminalWorkReply != null && state == null) {
                        // A terminal result is still usable if recording the turn failed.
                        pendingQuestion = null
                        previousRun = null
                        reply = terminalWorkReply
                        terminalWorkReply = null
                        sendQueuedFollowUp()
                    }
                } else {
                    startupTimeout.cancel()
                    val finished = finishedIndex?.let(turns::get)
                    if (finishedIndex != null) {
                        revealTurn = finishedIndex
                        resolvedMode = if (useChat) AssistantMode.Chat else AssistantMode.Work
                    }
                    pendingQuestion = null
                    previousRun = null
                    reply = (finished ?: turns.lastOrNull())?.let(::savedReply)
                    if (sendAfterReply) sendQueuedFollowUp()
                    else if (finished != null && finished.status in setOf("succeeded", "unverified")) {
                        playCompletedResult(finished)
                    }
                }
            }
        }
    }

    private fun liveReply(state: TaskState): AssistantReply? {
        if (state == TaskState.Idle) return null
        val (title, body) = TaskService.describe(state)
        return when (state) {
            is TaskState.Succeeded -> workResultReply(
                if (state.doubtful) title else getString(R.string.task_done),
                state.answer, state.note.takeIf { state.doubtful },
                completed = true,
            )
            is TaskState.Running -> {
                val label = assistantProgressLabel(state.tool)
                AssistantReply(
                    getString(label), body = WorkReply.from(state.preview.orEmpty(), streaming = true).summary,
                    running = true, waiting = true,
                    motion = state.orbMotion,
                    progress = state.narration?.takeIf(String::isNotBlank) ?: state.reason?.takeIf(String::isNotBlank),
                )
            }
            is TaskState.Held -> AssistantReply(title, body, waiting = state.reason.queued, motion = OrbMotion.Waiting)
            is TaskState.WaitingInput, is TaskState.WaitingConfirmation, is TaskState.WaitingTakeover ->
                AssistantReply(title, body, motion = OrbMotion.Waiting)
            else -> AssistantReply(title, body)
        }
    }

    private fun savedReply(turn: Turn): AssistantReply = when (turn.status) {
        "unverified" -> workResultReply(
            getString(R.string.chat_unverified),
            turn.outcome, turn.note,
        )
        "failed" -> AssistantReply(getString(R.string.task_not_done), turn.outcome)
        "cancelled" -> AssistantReply(
            getString(R.string.task_stopped),
            Conversation.stopSummary(turn) ?: getString(R.string.task_stopped_body),
        )
        else -> if (turn.research) AssistantReply(getString(R.string.task_done), turn.outcome, completed = true)
        else workResultReply(getString(R.string.task_done), turn.outcome, completed = true)
    }

    private fun workResultReply(title: String, answer: String, note: String? = null, completed: Boolean = false): AssistantReply {
        val result = WorkReply.from(answer)
        val prefix = note?.takeIf(String::isNotBlank)?.plus("\n\n").orEmpty()
        return AssistantReply(
            title, (prefix + result.full).trimEnd(), completed = completed,
            collapsedBody = result.summary.takeIf { it.isNotBlank() && it != result.full.trim() }?.let { prefix + it },
        )
    }

    private fun openConversation() {
        val id = conversationId ?: return
        startActivity(TaskService.openAppIntent(this, id))
        finish()
    }

    private suspend fun submitImages(
        id: String, words: String, screen: Bitmap?, cameraFile: File?, dispatch: (String) -> Unit,
    ) = withContext(Dispatchers.IO) {
        val temporary = screen?.let { File.createTempFile("assistant-screen-", ".jpg", cacheDir) }
        try {
            val files = buildList {
                if (screen != null && temporary != null) {
                    val written = temporary.outputStream().use { screen.compress(Bitmap.CompressFormat.JPEG, 90, it) }
                    if (!written) throw IOException("Could not encode screenshot")
                    add("Screenshot.jpg" to temporary)
                }
                if (cameraFile != null) add(cameraFile.name to cameraFile)
            }
            val dirs = SandboxDirs(app)
            val sandbox = AndroidSandbox(app, SandboxState.get(app))
            Attachments.withTaskFiles(files, dirs.workspace(id), dirs.gate, dirs.tree,
                room = { sandbox.freeBytes() >= Attachments.MIN_FREE && sandbox.workspaceBytes(id) <= Attachments.WORKSPACE_MAX },
            ) { imported ->
                if (screen != null && invocation?.canShare(app) != true) throw IOException("Screen sharing permission changed")
                dispatch(AttachmentLines.composeBytes(words, imported))
            }
        } finally {
            temporary?.delete()
        }
    }
}

internal fun assistantProgressLabel(tool: String?): Int = when (toolOrbMotion(tool)) {
    null -> R.string.assistant_thinking
    OrbMotion.Searching -> R.string.assistant_searching
    OrbMotion.Observing -> R.string.assistant_looking
    else -> R.string.task_running
}

/** Use the opening result or verification note, with a status fallback for long unbroken text. */
internal fun assistantSpokenSummary(plain: String, fallback: String): String {
    val lines = plain.lineSequence().dropWhile(String::isBlank)
    val opening = lines.firstOrNull()?.trim().orEmpty()
    if (opening.startsWith("• ")) {
        val fields = lines.takeWhile { it.startsWith("• ") }.take(3).map { it.removePrefix("• ").trim() }.toList()
        val spoken = fields.runningReduce { result, field -> "$result。$field" }.lastOrNull { it.length <= 120 }
        if (!spoken.isNullOrBlank()) return spoken
    }
    val sentences = opening.split(Regex("(?<=[。！？!?])\\s*|(?<=\\.)\\s+")).filter(String::isNotBlank)
    val short = sentences.take(2).joinToString(" ")
    return short.takeIf { it.isNotBlank() && it.length <= 120 }
        ?: sentences.firstOrNull()?.takeIf { it.length <= 120 }
        ?: fallback
}
