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

    fun setEnabled(enabled: Boolean): Boolean = synchronized(SAVE_LOCK) {
        val snapshot = prefs.all
        (!enabled || credentials(snapshot, model(snapshot).provider) != null) &&
            prefs.edit().putBoolean("enabled", enabled).commit()
    }

    fun model(): ReadoutModel = model(prefs.all)

    private fun model(snapshot: Map<String, *>): ReadoutModel {
        val defaults = ReadoutModel()
        return ReadoutModel(
            provider = ReadoutProvider.entries.firstOrNull { it.name == snapshot["provider"] } ?: defaults.provider,
            model = snapshot["model"] as? String ?: defaults.model,
            voice = snapshot["voice"] as? String ?: defaults.voice,
            baseUrl = snapshot["base_url"] as? String ?: "",
        )
    }

    /** Pair a provider and its credentials from a single atomic SharedPreferences snapshot. */
    fun playbackConfiguration(): Pair<ReadoutModel, ReadoutCredentials>? {
        val snapshot = prefs.all
        if (snapshot["enabled"] != true) return null
        val selected = model(snapshot)
        return credentials(snapshot, selected.provider)?.let { selected to it }
    }

    fun save(model: ReadoutModel, replacement: ReadoutCredentials?) = synchronized(SAVE_LOCK) {
        require(model.model.isNotBlank() && model.model.length <= 128 && model.model.none(Char::isISOControl))
        require(model.voice.isNotBlank() && model.voice.length <= 128 && model.voice.none(Char::isISOControl))
        require(if (model.provider == ReadoutProvider.Doubao) model.model == VolcRealtimeConfig.MODEL else model.speechUrl() != null)
        val snapshot = prefs.all
        val previous = this.model(snapshot)
        val sameTarget = previous.provider == model.provider && previous.speechUrl() == model.speechUrl()
        val credential = replacement ?: if (sameTarget) credentials(snapshot, previous.provider) else null
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

    private fun credentials(snapshot: Map<String, *>, provider: ReadoutProvider): ReadoutCredentials? = runCatching {
        val stored = snapshot["credential"] as? String ?: return null
        val plain = cipher.decrypt(Base64.decode(stored, Base64.NO_WRAP))
        try {
            val fields = plain.toString(Charsets.UTF_8).split('\n')
            if (fields.size != 3) return null
            ReadoutCredentials(fields[0], fields[1], fields[2]).takeIf { it.valid(provider) }
        } finally {
            plain.fill(0)
        }
    }.getOrNull()

    private companion object {
        val SAVE_LOCK = Any()
    }
}
