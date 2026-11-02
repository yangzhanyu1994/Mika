package com.wangzan101.mika.settings

import com.wangzan101.mika.core.caution.AppActionEntry
import com.wangzan101.mika.core.caution.AppCaution
import com.wangzan101.mika.core.caution.AppCautionMode
import com.wangzan101.mika.core.caution.AppCautionRuntime
import com.wangzan101.mika.core.caution.CautionDefaults
import com.wangzan101.mika.core.caution.CautionLimits
import com.wangzan101.mika.core.caution.WirelessIdle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.File

/** 账号保护的设置、操作记录和冷却。换模式、改上限都写在这里。 */
class CautionStore(private val file: File) {
    data class Override(
        val mode: AppCautionMode? = null,
        val hourly: Int? = null,
        val daily: Int? = null,
        val minDelayMs: Long? = null,
        val maxDelayMs: Long? = null,
        val cooldownMs: Long? = null,
    )

    private val overrides = HashMap<String, Override>()
    /** null 表示还没选过,按能不能自己再打开无线调试决定。 */
    var wirelessIdle: Boolean? = null
        private set
    var graceMs: Long = WirelessIdle.DEFAULT_GRACE_MS
        private set

    private val _revision = MutableStateFlow(0)
    val revision: StateFlow<Int> = _revision.asStateFlow()

    private var pauses: Map<String, Long> = emptyMap()
    private var logSeed: List<AppActionEntry> = emptyList()

    val runtime: AppCautionRuntime

    init {
        load()
        runtime = AppCautionRuntime(
            config = ::forPackage,
            initialPauses = pauses,
            initialLog = logSeed,
            onChange = { persist() },
        )
    }

    fun forPackage(packageName: String): AppCaution {
        val base = CautionDefaults.forPackage(packageName)
        val o = overrides[packageName] ?: return base
        val limits = base.limits
        val min = o.minDelayMs ?: limits.minDelayMs
        val max = (o.maxDelayMs ?: limits.maxDelayMs).coerceAtLeast(min)
        return AppCaution(
            o.mode ?: base.mode,
            CautionLimits(
                hourly = (o.hourly ?: limits.hourly).coerceAtLeast(1),
                daily = (o.daily ?: limits.daily).coerceAtLeast(1),
                minDelayMs = min.coerceAtLeast(0),
                maxDelayMs = max,
                cooldownMs = (o.cooldownMs ?: limits.cooldownMs).coerceAtLeast(0),
            ),
        )
    }

    fun setMode(packageName: String, mode: AppCautionMode) {
        overrides[packageName] = (overrides[packageName] ?: Override()).copy(mode = mode)
        persist()
    }

    fun setLimits(packageName: String, hourly: Int, daily: Int, minDelayMs: Long, maxDelayMs: Long, cooldownMs: Long) {
        val min = minDelayMs.coerceAtLeast(0)
        overrides[packageName] = (overrides[packageName] ?: Override()).copy(
            hourly = hourly.coerceAtLeast(1),
            daily = daily.coerceAtLeast(1),
            minDelayMs = min,
            maxDelayMs = maxDelayMs.coerceAtLeast(min),
            cooldownMs = cooldownMs.coerceAtLeast(0),
        )
        persist()
    }

    fun setWirelessIdle(enabled: Boolean?) {
        wirelessIdle = enabled
        persist()
    }

    fun setGraceMs(ms: Long) {
        graceMs = ms.coerceIn(5_000L, 30 * 60_000L)
        persist()
    }

    fun log(): List<AppActionEntry> = runtime.log()

    private fun load() {
        val o = runCatching { Json.parseToJsonElement(file.readText()).jsonObject }.getOrNull() ?: return
        wirelessIdle = (o["wirelessIdle"] as? JsonPrimitive)?.booleanOrNull
        graceMs = (o["graceMs"] as? JsonPrimitive)?.longOrNull ?: graceMs
        (o["apps"] as? JsonObject)?.forEach { (pkg, value) ->
            val a = value as? JsonObject ?: return@forEach
            overrides[pkg] = Override(
                mode = a.str("mode")?.let { n -> AppCautionMode.entries.firstOrNull { it.name == n } },
                hourly = a.str("hourly")?.toIntOrNull(),
                daily = a.str("daily")?.toIntOrNull(),
                minDelayMs = a.str("minDelayMs")?.toLongOrNull(),
                maxDelayMs = a.str("maxDelayMs")?.toLongOrNull(),
                cooldownMs = a.str("cooldownMs")?.toLongOrNull(),
            )
        }
        pauses = (o["pauses"] as? JsonObject)?.mapNotNull { (pkg, value) ->
            (value as? JsonPrimitive)?.longOrNull?.let { pkg to it }
        }?.toMap().orEmpty()
        logSeed = (o["log"] as? kotlinx.serialization.json.JsonArray)?.mapNotNull { el ->
            val row = el as? JsonObject ?: return@mapNotNull null
            val at = row.str("at")?.toLongOrNull() ?: return@mapNotNull null
            val pkg = row.str("pkg") ?: return@mapNotNull null
            AppActionEntry(at, pkg, row.str("summary").orEmpty())
        }.orEmpty()
    }

    @Synchronized private fun persist() {
        val body = buildJsonObject {
            wirelessIdle?.let { put("wirelessIdle", it) }
            put("graceMs", graceMs)
            put("apps", buildJsonObject {
                overrides.forEach { (pkg, o) ->
                    put(pkg, buildJsonObject {
                        o.mode?.let { put("mode", it.name) }
                        o.hourly?.let { put("hourly", it) }
                        o.daily?.let { put("daily", it) }
                        o.minDelayMs?.let { put("minDelayMs", it) }
                        o.maxDelayMs?.let { put("maxDelayMs", it) }
                        o.cooldownMs?.let { put("cooldownMs", it) }
                    })
                }
            })
            put("pauses", buildJsonObject { runtime.pauses().forEach { (pkg, until) -> put(pkg, until) } })
            put("log", kotlinx.serialization.json.buildJsonArray {
                runtime.log().takeLast(AppCautionRuntime.LOG_MAX).forEach { row ->
                    add(buildJsonObject {
                        put("at", row.atMs); put("pkg", row.packageName); put("summary", row.summary)
                    })
                }
            })
        }
        runCatching {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(body.toString())
            if (!tmp.renameTo(file)) {
                file.writeText(body.toString())
                tmp.delete()
            }
        }
        _revision.value += 1
    }

    private fun JsonObject.str(key: String) = (this[key] as? JsonPrimitive)?.content
}
