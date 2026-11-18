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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import com.wangzan101.mika.MikaApp
import com.wangzan101.mika.R
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
import com.wangzan101.mika.ui.VoiceCopy
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.theme.SettingsAppearance
import com.wangzan101.mika.ui.theme.animationsOff
import com.wangzan101.mika.voice.HoldToTalk
import com.wangzan101.mika.voice.SpeechEvent
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
    private var conversationId: String? = null
    private var reply by mutableStateOf<AssistantReply?>(null)
    private var history by mutableStateOf<List<AssistantExchange>>(emptyList())
    private var pendingQuestion by mutableStateOf<String?>(null)
    private var pendingAfter = 0
    private var previousRun: String? = null
    private var replyJob: Job? = null
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
            savedInstanceState.getString("assistantConversation")?.let { id ->
                autoListen = false
                text = savedInstanceState.getString("assistantQuestion").orEmpty()
                pendingQuestion = savedInstanceState.getString("assistantPendingQuestion")
                pendingAfter = savedInstanceState.getInt("assistantPendingAfter", 0)
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
            val avatar by produceState<Bitmap?>(null) { value = appearance.avatar() }
            val face by produceState<AvatarEyes?>(null, avatar) {
                value = withContext(Dispatchers.Default) { avatar?.let { AvatarEyes.detect(it) } }
            }
            MikaTheme(dark = isSystemInDarkTheme(), reduceMotion = animationsOff(this)) {
                SettingsAppearance {
                    AssistantOverlay(
                        avatar = avatar,
                        face = face,
                        history = history,
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
                        reply = reply,
                        onOpenConversation = ::openConversation,
                        onText = { text = it },
                        onEdit = { stopSpeech(keepText = true) },
                        onVoice = { if (listening) finishSpeech = true else if (!converting) requestSpeech() },
                        onScreenshot = ::attachScreenshot,
                        onPhoto = { finishThen(::requestCamera) },
                        onRemoveScreenshot = ::removeScreenshot,
                        onRemovePhoto = ::removePhoto,
                        onClear = ::clear,
                        onSend = { finishThen(::send) },
                        onClose = { finish() },
                    )
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("assistantConversation", conversationId)
        outState.putString("assistantQuestion", text)
        outState.putString("assistantPendingQuestion", pendingQuestion)
        outState.putInt("assistantPendingAfter", pendingAfter)
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
        }
    }

    override fun onPause() {
        resumed = false
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
        partial = ""
        status = null
        finishSpeech = false
        listening = true
        voiceJob = lifecycleScope.launch {
            var finalText: String? = null
            try {
                assistantSpeech(app.voice, { finishSpeech }) { event ->
                    when (event) {
                        is SpeechEvent.Level -> level = event.value
                        is SpeechEvent.Partial -> partial = event.stable + event.tail
                        SpeechEvent.Converting -> { converting = true; listening = false }
                        is SpeechEvent.Final -> finalText = event.text.trim()
                        is SpeechEvent.Failed -> status = VoiceCopy.failure(event.why)
                        SpeechEvent.EndOfSpeech -> Unit
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: RuntimeException) {
                Log.w("MikaAssistant", "Dictation failed", e)
                status = getString(R.string.voice_mic_unavailable)
            }
            val recognized = finalText?.ifBlank { partial } ?: partial
            if (recognized.isNotBlank()) text = HoldToTalk.join(text, recognized)
            partial = ""
            level = 0f
            listening = false
            converting = false
            val action = afterSpeech
            afterSpeech = null
            if (resumed) action?.invoke()
        }
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
        stopSpeech(keepText = false)
        text = ""
        status = null
        removeScreenshot()
        removePhoto()
    }

    private fun send() {
        if (sending || pendingQuestion != null || screenJob?.isActive == true || locked() || isFinishing) return
        val words = text.trim()
        val screen = screenshot
        val cameraFile = photoUri?.let { AttachFiles.cameraFile(this, it) }
        if (words.isEmpty() && screen == null && cameraFile == null) return
        if (app.providers.roles.value.main == null) {
            status = getString(R.string.assistant_model_required)
            return
        }
        if (app.teach.blocksTasks()) {
            status = getString(R.string.teach_blocked_task)
            return
        }
        if (screen != null && invocation?.canShare(app) != true) {
            screenshot = null
            status = getString(R.string.appaccess_share_expired)
            return
        }
        sending = true
        status = null
        lifecycleScope.launch {
            val existing = conversationId
            val id = existing ?: app.conversations.newConversation()
            val baseline = history.size
            val oldRun = app.taskState.run(id)?.key
            fun dispatch(goal: String) {
                if (existing == null) TaskService.start(this@AssistantOverlayActivity, goal, id)
                else if (!TaskService.enqueueFollowUp(this@AssistantOverlayActivity, id, UUID.randomUUID().toString(), goal)) {
                    throw IOException("Could not enqueue the follow-up")
                }
            }
            try {
                if (screen == null && cameraFile == null) withContext(Dispatchers.IO) { dispatch(words) }
                else submitImages(id, words, screen, cameraFile, ::dispatch)
                removeScreenshot()
                removePhoto()
                text = ""
                pendingQuestion = words
                pendingAfter = baseline
                previousRun = oldRun
                if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) showReply(id) else finish()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("MikaAssistant", "Task submission failed", e)
                status = getString(R.string.assistant_send_failed)
            } finally {
                sending = false
            }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun showReply(id: String) {
        conversationId = id
        replyJob?.cancel()
        if (pendingQuestion != null) reply = AssistantReply(getString(R.string.runtime_preparing), waiting = true)
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
            combine(live, saved, ::Pair).collect { (state, turns) ->
                history = turns.map { AssistantExchange(AttachmentLines.words(it.userText), savedReply(it).body) }
                if (pendingQuestion != null && turns.size <= pendingAfter) {
                    if (state != null && state != TaskState.Idle) {
                        startupTimeout.cancel()
                        reply = liveReply(state)
                    }
                } else {
                    startupTimeout.cancel()
                    pendingQuestion = null
                    previousRun = null
                    reply = turns.lastOrNull()?.let(::savedReply)
                }
            }
        }
    }

    private fun liveReply(state: TaskState): AssistantReply? {
        if (state == TaskState.Idle) return null
        val (title, body) = TaskService.describe(state)
        return when (state) {
            is TaskState.Succeeded -> AssistantReply(
                if (state.doubtful) title else getString(R.string.task_done), body, completed = true,
            )
            is TaskState.Running -> AssistantReply(
                getString(assistantProgressLabel(state.tool)),
                running = true,
                waiting = true,
                thinking = state.tool == null,
            )
            is TaskState.Held -> AssistantReply(title, body, waiting = state.reason.queued)
            else -> AssistantReply(title, body)
        }
    }

    private fun savedReply(turn: Turn): AssistantReply = when (turn.status) {
        "unverified" -> AssistantReply(
            getString(R.string.chat_unverified),
            listOfNotNull(turn.note?.takeIf(String::isNotBlank), turn.outcome).joinToString("\n\n"),
        )
        "failed" -> AssistantReply(getString(R.string.task_not_done), turn.outcome)
        "cancelled" -> AssistantReply(
            getString(R.string.task_stopped),
            Conversation.stopSummary(turn) ?: getString(R.string.task_stopped_body),
        )
        else -> AssistantReply(getString(R.string.task_done), turn.outcome, completed = true)
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

internal fun assistantProgressLabel(tool: String?): Int = when (tool) {
    null -> R.string.assistant_thinking
    "web_search", "knowledge_search", "skill_search", "search_files" -> R.string.assistant_searching
    "phone_find", "find_visually", "list_elements", "observe", "screenshot" -> R.string.assistant_looking
    else -> R.string.task_running
}
