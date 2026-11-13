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
data class EnvironmentScene(
    val id: String,
    val title: String,
    val type: String,
    val target: String,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val radius: Float = 200f,
    val enter: Boolean,
    val delaySeconds: Int = 60,
    val goal: String,
    val enabled: Boolean = true,
    val lastThreadId: String? = null,
) {
    override fun toString() = "EnvironmentScene(type=$type, enabled=$enabled)"
}

/** An unreadable encrypted store is never overwritten. */
class EnvironmentSceneStore private constructor(context: Context) {
    private val prefs = context.getSharedPreferences("mika_environment_scenes", Context.MODE_PRIVATE)
    private val cipher = KeystoreKeyCipher("mika-environment-scenes")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private var loaded = false
    private val mutable = MutableStateFlow<List<EnvironmentScene>>(emptyList())
    val rules = mutable.asStateFlow()
    private val failed = MutableStateFlow(false)
    val unavailable = failed.asStateFlow()

    @Synchronized fun load(): Boolean {
        if (loaded) return true
        return try {
            val raw = prefs.getString("rules", null)
            val values = if (raw == null) emptyList() else {
                require(raw.length <= 500_000)
                val bytes = cipher.decrypt(Base64.getDecoder().decode(raw))
                try { json.decodeFromString(ListSerializer(EnvironmentScene.serializer()), bytes.toString(Charsets.UTF_8)) }
                finally { bytes.fill(0) }
            }
            require(values.size <= 50 && values.map { it.id }.distinct().size == values.size)
            values.forEach(::validate)
            mutable.value = values
            loaded = true
            failed.value = false
            true
        } catch (_: Exception) { failed.value = true; false }
    }
    @Synchronized fun save(rule: EnvironmentScene) {
        check(load()); validate(rule)
        val before = mutable.value
        val next = if (before.any { it.id == rule.id }) before.map { if (it.id == rule.id) rule else it } else before + rule
        require(next.size <= 50)
        persist(next)
    }
    @Synchronized fun remove(id: String) { check(load()); persist(mutable.value.filterNot { it.id == id }) }
    private fun persist(rows: List<EnvironmentScene>) {
        val plain = json.encodeToString(ListSerializer(EnvironmentScene.serializer()), rows).toByteArray()
        val ok = try { prefs.edit().putString("rules", Base64.getEncoder().encodeToString(cipher.encrypt(plain))).commit() }
        finally { plain.fill(0) }
        check(ok); mutable.value = rows
    }
    private fun validate(rule: EnvironmentScene) {
        require(rule.id.matches(Regex("[a-zA-Z0-9-]{1,80}")))
        require(rule.title.isNotBlank() && rule.title.length <= 80)
        require(rule.type in setOf("wifi", "location"))
        require(rule.target.isNotBlank() && rule.target.length <= 128)
        require(rule.goal.isNotBlank() && rule.goal.length <= 4000)
        require(rule.delaySeconds in 0..600)
        if (rule.type == "location") {
            require(rule.latitude != null && rule.latitude in -90.0..90.0)
            require(rule.longitude != null && rule.longitude in -180.0..180.0)
            require(rule.radius in 100f..10000f)
        }
    }
    companion object {
        @Volatile private var instance: EnvironmentSceneStore? = null
        fun get(context: Context) = instance ?: synchronized(this) {
            instance ?: EnvironmentSceneStore(context.applicationContext).also { instance = it }
        }
    }
}
