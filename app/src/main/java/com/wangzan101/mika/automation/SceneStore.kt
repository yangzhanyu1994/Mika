package com.wangzan101.mika.automation

import android.content.Context
import com.wangzan101.mika.settings.KeystoreKeyCipher
import java.util.Base64
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

@Serializable
data class BluetoothScene(
    val id: String,
    val title: String,
    val address: String,
    val deviceName: String,
    val onConnect: Boolean,
    val delaySeconds: Int,
    val goal: String,
    val enabled: Boolean = true,
    val lastThreadId: String? = null,
    val lastDispatchedAt: Long? = null,
) {
    override fun toString() = "BluetoothScene(enabled=$enabled, onConnect=$onConnect)"
}

/** Never overwrite an unreadable rules file; state is updated only after encryption and commit. */
class SceneStore private constructor(context: Context) {
    private val prefs = context.getSharedPreferences("mika_scenes", Context.MODE_PRIVATE)
    private val cipher = KeystoreKeyCipher("mika-scenes")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val mutable = MutableStateFlow<List<BluetoothScene>>(emptyList())
    val rules = mutable.asStateFlow()
    private val failed = MutableStateFlow(false)
    val unavailable = failed.asStateFlow()
    private var loaded = false

    @Synchronized fun load(): Boolean {
        if (loaded) return true
        return try {
            val raw = prefs.getString("encrypted_rules_v1", null)
            val rows = if (raw == null) emptyList() else {
                require(raw.length <= 500_000)
                val plain = cipher.decrypt(Base64.getDecoder().decode(raw))
                try { json.decodeFromString(ListSerializer(BluetoothScene.serializer()), plain.toString(Charsets.UTF_8)) }
                finally { plain.fill(0) }
            }
            require(rows.size <= 50 && rows.map { it.id }.distinct().size == rows.size)
            rows.forEach(::validate)
            mutable.value = rows
            loaded = true
            failed.value = false
            true
        } catch (_: Exception) { failed.value = true; false }
    }

    @Synchronized fun save(rule: BluetoothScene) {
        check(load())
        validate(rule)
        val before = mutable.value
        val next = if (before.any { it.id == rule.id }) before.map { if (it.id == rule.id) rule else it } else before + rule
        require(next.size <= 50)
        persist(next)
    }

    @Synchronized fun remove(id: String) {
        check(load())
        persist(mutable.value.filterNot { it.id == id })
    }

    private fun persist(rows: List<BluetoothScene>) {
        val plain = json.encodeToString(ListSerializer(BluetoothScene.serializer()), rows).toByteArray()
        val ok = try { prefs.edit().putString("encrypted_rules_v1", Base64.getEncoder().encodeToString(cipher.encrypt(plain))).commit() }
        finally { plain.fill(0) }
        check(ok)
        mutable.value = rows
    }

    private fun validate(rule: BluetoothScene) {
        require(rule.id.matches(Regex("[a-zA-Z0-9-]{1,80}")))
        require(rule.title.isNotBlank() && rule.title.length <= 80)
        require(rule.address.matches(Regex("(?i)[0-9a-f]{2}(:[0-9a-f]{2}){5}")))
        require(rule.deviceName.isNotBlank() && rule.deviceName.length <= 120)
        require(rule.goal.isNotBlank() && rule.goal.length <= 4000)
        require(rule.delaySeconds in 0..600)
    }

    companion object {
        @Volatile private var instance: SceneStore? = null
        fun get(context: Context): SceneStore = instance ?: synchronized(this) {
            instance ?: SceneStore(context.applicationContext).also { instance = it }
        }
    }
}
