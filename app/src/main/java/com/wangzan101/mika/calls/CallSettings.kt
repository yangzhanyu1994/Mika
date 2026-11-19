package com.wangzan101.mika.calls

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.util.Base64
import com.wangzan101.mika.settings.KeyCipher
import com.wangzan101.mika.settings.KeystoreKeyCipher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Credentials are encrypted together; never put decrypted fields in a state flow or an error. */
class CallSettings(
    private val prefs: SharedPreferences,
    private val cipher: KeyCipher = KeystoreKeyCipher("mika-volc-realtime-calls"),
) {
    private val mutable = MutableStateFlow(read())
    val state = mutable.asStateFlow()
    internal val credentialsRevision = MutableStateFlow(0L)

    @Synchronized
    fun save(config: CallConfig): CallConfig {
        val defaults = CallConfig()
        val previous = mutable.value
        require(config.defaultOutgoingSimId == null || CallPolicy.isRememberedSimId(config.defaultOutgoingSimId)) {
            "保存的拨号 SIM 无效，请重新选择"
        }
        require(config.allowedIncomingNumbers.size <= CallPolicy.MAX_ALLOWED_NUMBERS) { "最多可设置 100 个来电号码" }
        val numbers = config.allowedIncomingNumbers.map {
            requireNotNull(CallPolicy.incomingNumber(it)) { "自动接听名单中包含无效号码" }
        }.distinctBy(CallPolicy::incomingMatchKey).toSet()
        require(config.bypassIncomingNumbers.size <= CallPolicy.MAX_ALLOWED_NUMBERS) { "最多可设置 100 个本人接听号码" }
        val bypass = config.bypassIncomingNumbers.map {
            requireNotNull(CallPolicy.incomingNumber(it)) { "本人接听名单中包含无效号码" }
        }.distinctBy(CallPolicy::incomingMatchKey).toSet()
        require(config.greeting.length <= CallPolicy.MAX_GREETING_CHARS) { "开场白过长" }
        require(config.outgoingGreeting.length <= CallPolicy.MAX_GREETING_CHARS) { "拨出开场白过长" }
        require(config.systemPrompt.length <= CallPolicy.MAX_PROMPT_CHARS) { "通话说明过长" }
        require(config.defaultBrief.length <= CallDefaultBrief.MAX_CHARS) { "通用应答过长" }
        val speaker = config.speaker.trim().ifBlank { defaults.speaker }
        require(speaker.length <= CallPolicy.MAX_SPEAKER_CHARS && speaker.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it in "_-" }) {
            "无效的火山音色编号"
        }
        val audioVerified = config.audioVerified && config.audioMode == previous.audioMode
        val next = config.copy(
            allowedIncomingNumbers = numbers,
            bypassIncomingNumbers = bypass,
            autoAnswerPausedUntilMs = config.autoAnswerPausedUntilMs?.takeIf { it > System.currentTimeMillis() },
            audioVerified = audioVerified,
            autoAnswer = config.enabled && audioVerified && config.autoAnswer,
            backgroundStandbyAuthorized = previous.backgroundStandbyAuthorized && config.enabled && audioVerified && config.autoAnswer,
            autoAnswerDelaySeconds = config.autoAnswerDelaySeconds.coerceIn(
                CallConfig.MIN_AUTO_ANSWER_DELAY_SECONDS, CallConfig.MAX_AUTO_ANSWER_DELAY_SECONDS,
            ),
            maxCallSeconds = config.maxCallSeconds.coerceIn(15, CallPolicy.MAX_SECONDS),
            greeting = config.greeting.trim(), // An explicitly empty greeting is different from a missing preference.
            outgoingGreeting = config.outgoingGreeting.trim(),
            systemPrompt = config.systemPrompt.trim(),
            defaultBrief = config.defaultBrief.trim().take(CallDefaultBrief.MAX_CHARS),
            defaultBriefMigrated = config.defaultBriefMigrated || previous.defaultBriefMigrated,
            credentialProbeOk = config.credentialProbeOk,
            answerStrangersOnly = config.answerStrangersOnly,
            answerOnRingTimeout = config.answerOnRingTimeout,
            speaker = speaker,
            credentialsConfigured = previous.credentialsConfigured,
            speechProvider = previous.speechProvider,
        )
        val committed = prefs.edit()
            .putBoolean("enabled", next.enabled)
            .putBoolean("summary_enabled", next.summaryEnabled)
            .putBoolean("auto_answer", next.autoAnswer)
            .putBoolean(BACKGROUND_AUTHORIZED, next.backgroundStandbyAuthorized)
            .putInt("auto_answer_delay_seconds", next.autoAnswerDelaySeconds)
            .putString("default_outgoing_sim_id", next.defaultOutgoingSimId)
            .putBoolean("allow_all", next.allowAllIncoming)
            .putStringSet("allowlist", numbers)
            .putStringSet("bypass_incoming", bypass)
            .putLong("auto_answer_paused_until_ms", next.autoAnswerPausedUntilMs ?: 0L)
            .putInt("max_seconds", next.maxCallSeconds)
            .putString("audio_mode", next.audioMode.name)
            .putString("audio_verified_build", Build.FINGERPRINT.takeIf { next.audioVerified })
            .putString("greeting", next.greeting)
            .putString("outgoing_greeting", next.outgoingGreeting)
            .putString("prompt", next.systemPrompt)
            .putString("default_brief", next.defaultBrief)
            .putBoolean("default_brief_migrated", next.defaultBriefMigrated)
            .putString("credential_probe", when (next.credentialProbeOk) {
                true -> "ok"
                false -> "fail"
                null -> null
            })
            .putBoolean("answer_strangers_only", next.answerStrangersOnly)
            .putBoolean("answer_on_ring_timeout", next.answerOnRingTimeout)
            .putString("speaker", next.speaker)
            .commit()
        // A failed write may not leave auto-answer running with a policy the user tried to change.
        mutable.value = if (committed) next else previous.copy(enabled = next.enabled && previous.enabled, autoAnswer = false, backgroundStandbyAuthorized = false)
        check(committed) { "无法保存电话设置" }
        return next
    }

    /** Called only by the foreground standby action. A live service is checked separately. */
    @Synchronized
    internal fun authorizeBackgroundStandby(): Boolean {
        val config = mutable.value
        if (!config.enabled || !config.autoAnswer || !config.audioVerified || !config.credentialsConfigured) return false
        if (config.backgroundStandbyAuthorized) return true
        if (!prefs.edit().putBoolean(BACKGROUND_AUTHORIZED, true).commit()) return false
        mutable.value = config.copy(backgroundStandbyAuthorized = true)
        return true
    }

    @Synchronized
    fun saveCredentials(appId: String, accessKey: String, apiKey: String) {
        val legacyId = appId.trim()
        val legacyKey = accessKey.trim()
        val modern = apiKey.trim()
        require(listOf(legacyId, legacyKey, modern).all { value ->
            value.length <= 1_024 && value.all { it.code in 33..126 }
        }) { "火山凭据格式无效" }
        val clear = legacyId.isEmpty() && legacyKey.isEmpty() && modern.isEmpty()
        require(clear || (modern.isNotEmpty() && legacyId.isEmpty() && legacyKey.isEmpty()) ||
            (modern.isEmpty() && legacyId.isNotEmpty() && legacyKey.isNotEmpty())) {
            "请仅填写 API Key，或同时填写旧版 App ID 和 Access Key"
        }
        val edit = prefs.edit()
        if (clear) {
            edit.remove(CREDENTIALS)
            if (selectedProvider() == CallSpeechProvider.Volcengine) {
                edit.putBoolean("auto_answer", false).putBoolean(BACKGROUND_AUTHORIZED, false).remove("credential_probe")
            }
        } else {
            val bytes = "${modern}\n${legacyId}\n${legacyKey}".toByteArray()
            try {
                val encrypted = runCatching { cipher.encrypt(bytes) }.getOrElse { error("无法加密火山凭据") }
                edit.putString(CREDENTIALS, Base64.encodeToString(encrypted, Base64.NO_WRAP))
            } finally {
                bytes.fill(0)
            }
        }
        val committed = edit.commit()
        if ((committed || clear) && selectedProvider() == CallSpeechProvider.Volcengine) {
            mutable.value = mutable.value.copy(credentialsConfigured = !clear,
                credentialProbeOk = if (clear) null else mutable.value.credentialProbeOk,
                autoAnswer = !clear && mutable.value.autoAnswer,
                backgroundStandbyAuthorized = !clear && mutable.value.backgroundStandbyAuthorized)
            credentialsRevision.value++
        }
        check(committed) { "无法保存火山凭据" }
    }

    /** Store the separate OpenAI key without ever copying a Volcengine credential across providers. */
    @Synchronized
    fun selectOpenAiVoice(apiKey: String) {
        val trimmed = apiKey.trim()
        require(trimmed.length <= 1024 && trimmed.all { it.code in 33..126 })
        val editor = prefs.edit()
        if (trimmed.isNotEmpty()) {
            val plain = trimmed.toByteArray(Charsets.UTF_8)
            try {
                editor.putString(OPENAI_CREDENTIAL, Base64.encodeToString(cipher.encrypt(plain), Base64.NO_WRAP))
            } finally {
                plain.fill(0)
            }
        } else {
            require(credentials(CallSpeechProvider.OpenAi) != null) { "OpenAI Realtime API key is required" }
        }
        storeProvider(editor, CallSpeechProvider.OpenAi)
    }

    @Synchronized
    fun selectVolcengineVoice() {
        if (selectedProvider() == CallSpeechProvider.Volcengine) return
        require(credentials(CallSpeechProvider.Volcengine) != null)
        storeProvider(prefs.edit(), CallSpeechProvider.Volcengine)
    }

    private fun storeProvider(editor: SharedPreferences.Editor, provider: CallSpeechProvider) {
        check(editor.putString(VOICE_PROVIDER, provider.name).putBoolean("auto_answer", false)
            .putBoolean(BACKGROUND_AUTHORIZED, false).remove("credential_probe").commit())
        mutable.value = mutable.value.copy(speechProvider = provider, credentialsConfigured = credentials(provider) != null,
            autoAnswer = false, backgroundStandbyAuthorized = false, credentialProbeOk = null)
        credentialsRevision.value++
    }

    fun hasCredentialsFor(provider: CallSpeechProvider): Boolean =
        prefs.contains(if (provider == CallSpeechProvider.OpenAi) OPENAI_CREDENTIAL else CREDENTIALS)

    private fun selectedProvider(): CallSpeechProvider = CallSpeechProvider.entries.firstOrNull {
        it.name == prefs.getString(VOICE_PROVIDER, null)
    } ?: CallSpeechProvider.Volcengine

    @Synchronized
    fun noteCredentialProbe(ok: Boolean) {
        if (!prefs.edit().putString("credential_probe", if (ok) "ok" else "fail").commit()) return
        mutable.value = mutable.value.copy(credentialProbeOk = ok)
    }

    @Synchronized
    internal fun credentials(provider: CallSpeechProvider = selectedProvider()): VolcRealtimeCredentials? = runCatching {
        val name = if (provider == CallSpeechProvider.OpenAi) OPENAI_CREDENTIAL else CREDENTIALS
        val stored = prefs.getString(name, null) ?: return null
        val bytes = cipher.decrypt(Base64.decode(stored, Base64.NO_WRAP))
        try {
            if (provider == CallSpeechProvider.OpenAi) {
                return VolcRealtimeCredentials(apiKey = bytes.toString(Charsets.UTF_8)).takeIf { it.valid }
            }
            val fields = bytes.toString(Charsets.UTF_8).split('\n')
            if (fields.size != 3) return null
            VolcRealtimeCredentials(apiKey = fields[0], appId = fields[1], accessKey = fields[2]).takeIf { it.valid }
        } finally {
            bytes.fill(0)
        }
    }.getOrNull()

    private fun read(): CallConfig = runCatching {
        val defaults = CallConfig()
        val enabled = prefs.getBoolean("enabled", false)
        val verified = prefs.getString("audio_verified_build", null)?.let { it == Build.FINGERPRINT } == true
        CallConfig(
            enabled = enabled,
            autoAnswer = enabled && verified && prefs.getBoolean("auto_answer", false),
            backgroundStandbyAuthorized = enabled && verified && prefs.getBoolean("auto_answer", false) &&
                prefs.getBoolean(BACKGROUND_AUTHORIZED, false),
            autoAnswerDelaySeconds = prefs.getInt("auto_answer_delay_seconds", defaults.autoAnswerDelaySeconds)
                .coerceIn(CallConfig.MIN_AUTO_ANSWER_DELAY_SECONDS, CallConfig.MAX_AUTO_ANSWER_DELAY_SECONDS),
            defaultOutgoingSimId = prefs.getString("default_outgoing_sim_id", null),
            allowAllIncoming = prefs.getBoolean("allow_all", false),
            allowedIncomingNumbers = prefs.getStringSet("allowlist", emptySet()).orEmpty()
                .mapNotNull(CallPolicy::incomingNumber).distinctBy(CallPolicy::incomingMatchKey)
                .take(CallPolicy.MAX_ALLOWED_NUMBERS).toSet(),
            bypassIncomingNumbers = prefs.getStringSet("bypass_incoming", emptySet()).orEmpty()
                .mapNotNull(CallPolicy::incomingNumber).distinctBy(CallPolicy::incomingMatchKey)
                .take(CallPolicy.MAX_ALLOWED_NUMBERS).toSet(),
            autoAnswerPausedUntilMs = prefs.getLong("auto_answer_paused_until_ms", 0L)
                .takeIf { it > System.currentTimeMillis() },
            maxCallSeconds = prefs.getInt("max_seconds", CallPolicy.DEFAULT_SECONDS).coerceIn(15, CallPolicy.MAX_SECONDS),
            audioMode = prefs.getString("audio_mode", null)?.let(CallAudioMode::valueOf) ?: defaults.audioMode,
            audioVerified = verified,
            credentialsConfigured = credentials() != null,
            speechProvider = selectedProvider(),
            summaryEnabled = prefs.getBoolean("summary_enabled", false),
            greeting = CallPolicy.migrateDefaultGreeting(prefs.getString("greeting", defaults.greeting).orEmpty())
                .take(CallPolicy.MAX_GREETING_CHARS),
            outgoingGreeting = prefs.getString("outgoing_greeting", defaults.outgoingGreeting).orEmpty().take(CallPolicy.MAX_GREETING_CHARS),
            systemPrompt = prefs.getString("prompt", "").orEmpty().take(CallPolicy.MAX_PROMPT_CHARS),
            defaultBrief = prefs.getString("default_brief", "").orEmpty().take(CallDefaultBrief.MAX_CHARS),
            defaultBriefMigrated = prefs.getBoolean("default_brief_migrated", false),
            credentialProbeOk = when (prefs.getString("credential_probe", null)) {
                "ok" -> true
                "fail" -> false
                else -> null
            },
            answerStrangersOnly = prefs.getBoolean("answer_strangers_only", false),
            answerOnRingTimeout = prefs.getBoolean("answer_on_ring_timeout", false),
            speaker = prefs.getString("speaker", defaults.speaker).orEmpty().take(CallPolicy.MAX_SPEAKER_CHARS),
        )
    }.getOrDefault(CallConfig())

    companion object {
        private const val CREDENTIALS = "volc_credentials_v1"
        private const val OPENAI_CREDENTIAL = "openai_realtime_key_v1"
        private const val VOICE_PROVIDER = "speech_provider"
        internal const val BACKGROUND_AUTHORIZED = "background_standby_authorized"

        fun create(context: Context): CallSettings =
            CallSettings(context.getSharedPreferences("mika_call_settings", Context.MODE_PRIVATE))
    }
}
