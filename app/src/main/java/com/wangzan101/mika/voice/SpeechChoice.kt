package com.wangzan101.mika.voice

import android.content.Context
import android.content.SharedPreferences
import com.wangzan101.mika.settings.FeatureFlags
import com.wangzan101.mika.settings.KeyCipher
import com.wangzan101.mika.settings.KeystoreKeyCipher
import java.util.Base64
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** 用户为「语音转文字」做的选择,以及它在设置里的存取。具体用哪个引擎由 [SpeechResolver] 结合手机能力再定。 */
sealed interface SpeechChoice {
    /** 没选过:有手机自带识别就用它。 */
    data object Auto : SpeechChoice

    /** 明确选了手机自带识别。 */
    data object System : SpeechChoice

    /** 不用语音输入。 */
    data object Off : SpeechChoice

    /** 用某个服务商的某个模型转写,[method] 决定请求怎么发。 */
    data class Provider(val providerId: String, val model: String, val method: TranscribeMethod) : SpeechChoice

    /** Native streaming ASR, with credentials stored separately from this non-secret selection. */
    data class Doubao(val resourceId: String = "volc.seedasr.sauc.duration") : SpeechChoice

    /**
     * 单独的语音转文字服务(硅基流动、百炼 Paraformer,或兼容 OpenAI 的转写地址)。
     * Key 不在这里,加密存在 [SpeechSettings]。
     */
    data class Service(val kind: String, val baseUrl: String, val model: String) : SpeechChoice
}

/**
 * 服务商转写音频的接口形态。存的是名字,只能往后加、不能改名或删,旧设置才读得回来。
 * 带 ChatInputAudio 前缀的三种都是对话接口里的 input_audio,只是写法不同,选模型试转时依次都试:
 * [ChatInputAudio] 的 data 放裸 base64 并附一句转写指令;[ChatInputAudioUri] 的 data 换成
 * `data:audio/wav;base64,...` 的 data URI(有的服务只认这种);[ChatInputAudioOnly] 也用 data URI,
 * 但只发音频不带指令(专做转写的模型不收文字块)。
 */
enum class TranscribeMethod { AudioTranscriptions, ChatInputAudio, GeminiInline, ChatInputAudioUri, ChatInputAudioOnly }

/**
 * 选择存在 [FeatureFlags] 那份设置里,一个键、一段 JSON;读不出来一律当 [SpeechChoice.Auto]。
 * 另记一个「手机自带识别不响应」([systemSilent]):按住说话时服务一直不回话,连着 [SILENT_STRIKES] 次才记上
 * (一次卡住多半是识别服务冷启动慢,不该从此关掉这条路);中间任何一次回了话就重新数。
 * 用户在设置里选「自动」或「手机自带」时记号和次数都清掉,好让换了识别服务、更新过系统的手机能再试。
 */
class SpeechSettings(private val prefs: SharedPreferences, private val cipher: KeyCipher = KeystoreKeyCipher("mika-speech-keys")) {
    private val _choice = MutableStateFlow(read())
    val choice: StateFlow<SpeechChoice> = _choice.asStateFlow()

    val hasDoubaoCredentials: Boolean get() = prefs.contains(DOUBAO_KEY)

    /** Save without making a billable network request. Both authentication generations are supported. */
    internal fun saveDoubao(credentials: DoubaoCredentials, resourceId: String) {
        require(credentials.valid && resourceId in DoubaoProtocol.resources)
        val json = buildJsonObject {
            put("apiKey", JsonPrimitive(credentials.apiKey))
            put("appId", JsonPrimitive(credentials.appId))
            put("accessKey", JsonPrimitive(credentials.accessKey))
        }.toString().toByteArray(Charsets.UTF_8)
        val encrypted = Base64.getEncoder().encodeToString(cipher.encrypt(json))
        val selected = SpeechChoice.Doubao(resourceId)
        prefs.edit().putString(DOUBAO_KEY, encrypted).putString(KEY, encode(selected).toString()).apply()
        _choice.value = selected
    }

    internal fun doubaoCredentials(): DoubaoCredentials? = runCatching {
        val encrypted = prefs.getString(DOUBAO_KEY, null) ?: return null
        val obj = Json.parseToJsonElement(String(cipher.decrypt(Base64.getDecoder().decode(encrypted)), Charsets.UTF_8)).jsonObject
        DoubaoCredentials(
            obj["apiKey"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            obj["appId"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            obj["accessKey"]?.jsonPrimitive?.contentOrNull.orEmpty(),
        ).takeIf { it.valid }
    }.getOrNull()

    fun clearDoubao() {
        prefs.edit().remove(DOUBAO_KEY).apply()
        if (_choice.value is SpeechChoice.Doubao) set(SpeechChoice.Off)
    }

    /** 存下语音服务的地址、模型名和 Key,并把它选成现在的语音转文字。Key 加密,不进日志。 */
    fun saveService(kind: String, baseUrl: String, model: String, apiKey: String) {
        val url = baseUrl.trim().trimEnd('/')
        val name = model.trim()
        val key = apiKey.trim()
        require(kind.isNotBlank() && url.isNotBlank() && name.isNotBlank() && key.isNotBlank())
        val choice = SpeechChoice.Service(kind, url, name)
        val encrypted = Base64.getEncoder().encodeToString(cipher.encrypt(key.toByteArray(Charsets.UTF_8)))
        val meta = buildJsonObject {
            put("baseUrl", JsonPrimitive(url))
            put("model", JsonPrimitive(name))
        }.toString()
        prefs.edit()
            .putString(KEY, encode(choice).toString())
            .putString(serviceKeyName(kind), encrypted)
            .putString(serviceMetaName(kind), meta)
            .apply()
        _choice.value = choice
    }

    fun serviceKey(kind: String): String? = runCatching {
        val raw = prefs.getString(serviceKeyName(kind), null) ?: return null
        String(cipher.decrypt(Base64.getDecoder().decode(raw)), Charsets.UTF_8).takeIf { it.isNotBlank() }
    }.getOrNull()

    fun serviceSaved(kind: String): SpeechChoice.Service? = runCatching {
        val raw = prefs.getString(serviceMetaName(kind), null) ?: return null
        val obj = Json.parseToJsonElement(raw).jsonObject
        val url = obj["baseUrl"]?.jsonPrimitive?.contentOrNull ?: return null
        val model = obj["model"]?.jsonPrimitive?.contentOrNull ?: return null
        SpeechChoice.Service(kind, url, model)
    }.getOrNull()

    fun configuredServiceKinds(): Set<String> = SpeechServices.kinds.filter { serviceKey(it) != null }.toSet()

    /**
     * 自带识别失败时改用已经配好的语音服务。当前选的就是它时用当前的;
     * 选了手机自带、但以前存过 Key,也还能退回去。
     */
    fun fallbackService(): SpeechChoice.Service? {
        val current = _choice.value
        if (current is SpeechChoice.Service && !serviceKey(current.kind).isNullOrBlank()) return current
        return SpeechServices.kinds.firstNotNullOfOrNull { kind ->
            val saved = serviceSaved(kind) ?: return@firstNotNullOfOrNull null
            if (serviceKey(kind).isNullOrBlank()) null else saved
        }
    }

    fun clearService(kind: String) {
        prefs.edit().remove(serviceKeyName(kind)).remove(serviceMetaName(kind)).apply()
        val current = _choice.value
        if (current is SpeechChoice.Service && current.kind == kind) set(SpeechChoice.Auto)
    }

    private val _systemSilent = MutableStateFlow(prefs.getBoolean(SILENT, false))
    val systemSilent: StateFlow<Boolean> = _systemSilent.asStateFlow()

    fun set(choice: SpeechChoice) {
        // 这两种都会去用手机自带识别:用户主动选了,就再给它机会
        val retry = choice == SpeechChoice.System || choice == SpeechChoice.Auto
        val edit = prefs.edit().putString(KEY, encode(choice).toString())
        if (retry) edit.remove(SILENT).remove(STRIKES)
        edit.apply()
        _choice.value = choice
        if (retry) _systemSilent.value = false
    }

    /** 手机自带识别这次一个回调都没给:连着第 [SILENT_STRIKES] 次才记成不响应。 */
    fun noteSystemSilent() {
        val strikes = prefs.getInt(STRIKES, 0) + 1
        val edit = prefs.edit().putInt(STRIKES, strikes)
        if (strikes >= SILENT_STRIKES) edit.putBoolean(SILENT, true)
        edit.apply()
        if (strikes >= SILENT_STRIKES) _systemSilent.value = true
    }

    /**
     * 点名试通过的识别服务(系统默认的不回话、它回了),下次先试它;null 是没有,或者系统默认的就回话。
     * 见 [SystemSpeech.attempts]。
     */
    val systemService: String? get() = prefs.getString(SERVICE, null)

    /** 手机自带识别这次回了话([service] 是回话的那个,null 是系统默认的):之前没响应的次数作废,记住回话的服务。 */
    fun noteSystemAnswered(service: String? = null) {
        val edit = prefs.edit()
        var changed = false
        if (prefs.getInt(STRIKES, 0) != 0) { edit.remove(STRIKES); changed = true }
        if (service != systemService) {
            if (service == null) edit.remove(SERVICE) else edit.putString(SERVICE, service)
            changed = true
        }
        if (changed) edit.apply()
    }

    private fun read(): SpeechChoice {
        val raw = prefs.getString(KEY, null) ?: return SpeechChoice.Auto
        return runCatching { decode(Json.parseToJsonElement(raw).jsonObject) }.getOrNull() ?: SpeechChoice.Auto
    }

    private fun encode(choice: SpeechChoice): JsonObject = buildJsonObject {
        when (choice) {
            SpeechChoice.Auto -> put(TYPE, JsonPrimitive("auto"))
            SpeechChoice.System -> put(TYPE, JsonPrimitive("system"))
            SpeechChoice.Off -> put(TYPE, JsonPrimitive("off"))
            is SpeechChoice.Doubao -> {
                put(TYPE, JsonPrimitive("doubao"))
                put("resourceId", JsonPrimitive(choice.resourceId))
            }
            is SpeechChoice.Provider -> {
                put(TYPE, JsonPrimitive("provider"))
                put("providerId", JsonPrimitive(choice.providerId))
                put("model", JsonPrimitive(choice.model))
                put("method", JsonPrimitive(choice.method.name))
            }
            is SpeechChoice.Service -> {
                put(TYPE, JsonPrimitive("service"))
                put("kind", JsonPrimitive(choice.kind))
                put("baseUrl", JsonPrimitive(choice.baseUrl))
                put("model", JsonPrimitive(choice.model))
            }
        }
    }

    /** 字段缺了、类型不认识、方法名不存在都返回 null,由调用处退回自动。 */
    private fun decode(obj: JsonObject): SpeechChoice? = when (obj[TYPE]?.jsonPrimitive?.contentOrNull) {
        "auto" -> SpeechChoice.Auto
        "system" -> SpeechChoice.System
        "off" -> SpeechChoice.Off
        "doubao" -> obj["resourceId"]?.jsonPrimitive?.contentOrNull
            ?.takeIf { it in DoubaoProtocol.resources }?.let { SpeechChoice.Doubao(it) }
        "provider" -> {
            val id = obj["providerId"]?.jsonPrimitive?.contentOrNull
            val model = obj["model"]?.jsonPrimitive?.contentOrNull
            val method = TranscribeMethod.entries.firstOrNull { it.name == obj["method"]?.jsonPrimitive?.contentOrNull }
            if (id == null || model == null || method == null) null else SpeechChoice.Provider(id, model, method)
        }
        "service" -> {
            val kind = obj["kind"]?.jsonPrimitive?.contentOrNull
            val url = obj["baseUrl"]?.jsonPrimitive?.contentOrNull
            val model = obj["model"]?.jsonPrimitive?.contentOrNull
            if (kind == null || url == null || model == null) null else SpeechChoice.Service(kind, url, model)
        }
        else -> null
    }

    companion object {
        private const val KEY = "speech_choice"
        private const val DOUBAO_KEY = "speech_doubao_credentials_encrypted"
        private const val TYPE = "type"
        private const val SILENT = "speech_system_silent"
        private const val STRIKES = "speech_system_silent_strikes"
        private const val SERVICE = "speech_system_service"

        private fun serviceKeyName(kind: String) = "speech_service_key_$kind"
        private fun serviceMetaName(kind: String) = "speech_service_meta_$kind"

        /** 连着这么多次不响应才记下。 */
        const val SILENT_STRIKES = 2

        fun get(context: Context) = SpeechSettings(
            context.applicationContext.getSharedPreferences(FeatureFlags.PREFS, Context.MODE_PRIVATE),
        )
    }
}
