package com.wangzan101.mika.assistant

import android.content.Context
import android.util.Base64
import com.wangzan101.mika.calls.VolcRealtimeConfig
import com.wangzan101.mika.calls.VolcRealtimeCredentials
import com.wangzan101.mika.settings.KeystoreKeyCipher
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

internal enum class ReadoutProvider { Doubao, Compatible }

internal data class ReadoutModel(
    val provider: ReadoutProvider = ReadoutProvider.Doubao,
    val model: String = VolcRealtimeConfig.MODEL,
    val voice: String = VolcRealtimeConfig.DEFAULT_SPEAKER,
    val baseUrl: String = "",
) {
    fun speechUrl(): HttpUrl? {
        val url = baseUrl.trim().toHttpUrlOrNull() ?: return null
        if (!url.isHttps || url.username.isNotEmpty() || url.password.isNotEmpty() ||
            url.query != null || url.fragment != null) return null
        return url.toString().trimEnd('/').plus("/audio/speech").toHttpUrlOrNull()
    }
}

internal class ReadoutCredentials(val key: String, val appId: String = "", val accessKey: String = "") {
    fun valid(provider: ReadoutProvider): Boolean {
        if (listOf(key, appId, accessKey).any { it.length > 1024 || it.any { c -> c !in '!'..'~' } }) return false
        return if (provider == ReadoutProvider.Compatible) key.isNotBlank() && appId.isEmpty() && accessKey.isEmpty()
        else (key.isNotBlank() && appId.isEmpty() && accessKey.isEmpty()) ||
            (key.isEmpty() && appId.isNotBlank() && accessKey.isNotBlank())
    }
    fun volc() = VolcRealtimeCredentials(apiKey = key, appId = appId, accessKey = accessKey)
}

internal class AssistantReadoutSettings(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("mika_assistant_readout", Context.MODE_PRIVATE)
    private val cipher = KeystoreKeyCipher("mika-assistant-readout")
    val enabled get() = prefs.getBoolean("enabled", false)

    @Synchronized
    fun setEnabled(enabled: Boolean): Boolean =
        (!enabled || configured() != null) && prefs.edit().putBoolean("enabled", enabled).commit()

    fun model(): ReadoutModel {
        val defaults = ReadoutModel()
        return ReadoutModel(
            provider = ReadoutProvider.entries.firstOrNull { it.name == prefs.getString("provider", null) } ?: defaults.provider,
            model = prefs.getString("model", defaults.model).orEmpty(),
            voice = prefs.getString("voice", defaults.voice).orEmpty(),
            baseUrl = prefs.getString("base_url", "").orEmpty(),
        )
    }

    @Synchronized
    fun configured(): Pair<ReadoutModel, ReadoutCredentials>? =
        credentials()?.let { model() to it }

    @Synchronized
    fun save(model: ReadoutModel, replacement: ReadoutCredentials?) {
        require(model.model.isNotBlank() && model.model.length <= 128 && model.model.none(Char::isISOControl))
        require(model.voice.isNotBlank() && model.voice.length <= 128 && model.voice.none(Char::isISOControl))
        require(if (model.provider == ReadoutProvider.Doubao) model.model == VolcRealtimeConfig.MODEL else model.speechUrl() != null)
        val previous = this.model()
        val sameTarget = previous.provider == model.provider && previous.speechUrl() == model.speechUrl()
        val credential = replacement ?: credentials()?.takeIf { sameTarget }
        require(credential?.valid(model.provider) == true)
        val edit = prefs.edit().putString("provider", model.provider.name).putString("model", model.model)
            .putString("voice", model.voice).putString("base_url", model.baseUrl)
        if (replacement != null) {
            val plain = "${replacement.key}\n${replacement.appId}\n${replacement.accessKey}".toByteArray()
            try {
                edit.putString("credential", Base64.encodeToString(cipher.encrypt(plain), Base64.NO_WRAP))
            } finally {
                plain.fill(0)
            }
        }
        check(edit.commit())
    }

    private fun credentials(): ReadoutCredentials? = runCatching {
        val stored = prefs.getString("credential", null) ?: return null
        val plain = cipher.decrypt(Base64.decode(stored, Base64.NO_WRAP))
        try {
            val fields = plain.toString(Charsets.UTF_8).split('\n')
            if (fields.size != 3) return null
            ReadoutCredentials(fields[0], fields[1], fields[2]).takeIf { it.valid(model().provider) }
        } finally {
            plain.fill(0)
        }
    }.getOrNull()
}
