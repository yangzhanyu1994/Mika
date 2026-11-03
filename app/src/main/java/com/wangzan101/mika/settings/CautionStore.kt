package com.wangzan101.mika.settings

import com.wangzan101.mika.core.caution.AppActionEntry
import com.wangzan101.mika.core.caution.AppCaution
import com.wangzan101.mika.core.caution.AppCautionMode
import com.wangzan101.mika.core.caution.AppCautionRuntime
import com.wangzan101.mika.core.caution.CautionDefaults
import com.wangzan101.mika.core.caution.CautionLimits
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

/** 降低风控的开关、每个 App 的上限、操作记录和冷却。 */
class CautionStore(private val file: File) {
    data class Override(
        val mode: AppCautionMode? = null,
        val hourly: Int? = null,
        val daily: Int? = null,
        val minDelayMs: Long? = null,
        val maxDelayMs: Long? = null,
        val cooldownMs: Long? = null,
        val searchHourly: Int? = null,
        val searchDaily: Int? = null,
        val pageHourly: Int? = null,
        val pageDaily: Int? = null,
        val detailHourly: Int? = null,
        val detailDaily: Int? = null,
    )

    private val overrides = HashMap<String, Override>()
    /** 总开关。没存过时开着。关掉后不再加间隔、次数、验证暂停和写操作确认。 */
    var reduceRisk: Boolean = true
        private set
    /** 没单独改过的 App 用这一对间隔;空表示用默认的几秒到约 15 秒。 */
    var paceMinMs: Long? = null
        private set
    var paceMaxMs: Long? = null
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
            enabled = { reduceRisk },
        )
    }

    fun forPackage(packageName: String): AppCaution {
        val base = CautionDefaults.forPackage(packageName)
        val o = overrides[packageName]
        val limits = base.limits
        val min = (o?.minDelayMs ?: paceMinMs ?: limits.minDelayMs).coerceIn(0, CautionDefaults.MAX_DELAY_MS)
        val max = (o?.maxDelayMs ?: paceMaxMs ?: limits.maxDelayMs).coerceIn(min, CautionDefaults.MAX_DELAY_MS)
        return AppCaution(
            o?.mode ?: base.mode,
            CautionLimits(
                hourly = (o?.hourly ?: limits.hourly).coerceAtLeast(1),
                daily = (o?.daily ?: limits.daily).coerceAtLeast(1),
                minDelayMs = min,
                maxDelayMs = max,
                cooldownMs = (o?.cooldownMs ?: limits.cooldownMs).coerceAtLeast(0),
                searchHourly = (o?.searchHourly ?: limits.searchHourly).coerceAtLeast(1),
                searchDaily = (o?.searchDaily ?: limits.searchDaily).coerceAtLeast(1),
                pageHourly = (o?.pageHourly ?: limits.pageHourly).coerceAtLeast(1),
                pageDaily = (o?.pageDaily ?: limits.pageDaily).coerceAtLeast(1),
                detailHourly = (o?.detailHourly ?: limits.detailHourly).coerceAtLeast(1),
                detailDaily = (o?.detailDaily ?: limits.detailDaily).coerceAtLeast(1),
            ),
        )
    }

    fun setMode(packageName: String, mode: AppCautionMode) {
        overrides[packageName] = (overrides[packageName] ?: Override()).copy(mode = mode)
        persist()
    }

    fun setLimits(
        packageName: String, hourly: Int, daily: Int, minDelayMs: Long, maxDelayMs: Long, cooldownMs: Long,
        searchHourly: Int? = null, searchDaily: Int? = null, pageHourly: Int? = null, pageDaily: Int? = null,
        detailHourly: Int? = null, detailDaily: Int? = null,
    ) {
        val min = minDelayMs.coerceIn(0, CautionDefaults.MAX_DELAY_MS)
        overrides[packageName] = (overrides[packageName] ?: Override()).copy(
            hourly = hourly.coerceAtLeast(1),
            daily = daily.coerceAtLeast(1),
            minDelayMs = min,
            maxDelayMs = maxDelayMs.coerceIn(min, CautionDefaults.MAX_DELAY_MS),
            cooldownMs = cooldownMs.coerceAtLeast(0),
            searchHourly = searchHourly?.coerceAtLeast(1),
            searchDaily = searchDaily?.coerceAtLeast(1),
            pageHourly = pageHourly?.coerceAtLeast(1),
            pageDaily = pageDaily?.coerceAtLeast(1),
            detailHourly = detailHourly?.coerceAtLeast(1),
            detailDaily = detailDaily?.coerceAtLeast(1),
        )
        persist()
    }

    fun setReduceRisk(enabled: Boolean) {
        reduceRisk = enabled
        persist()
    }

    fun setPace(minDelayMs: Long, maxDelayMs: Long) {
        val min = minDelayMs.coerceIn(0, CautionDefaults.MAX_DELAY_MS)
        paceMinMs = min
        paceMaxMs = maxDelayMs.coerceIn(min, CautionDefaults.MAX_DELAY_MS)
        persist()
    }

    fun log(): List<AppActionEntry> = runtime.log()

    private fun load() {
        val o = runCatching { Json.parseToJsonElement(file.readText()).jsonObject }.getOrNull() ?: return
        reduceRisk = (o["reduceRisk"] as? JsonPrimitive)?.booleanOrNull ?: true
        paceMinMs = (o["paceMinMs"] as? JsonPrimitive)?.longOrNull
        paceMaxMs = (o["paceMaxMs"] as? JsonPrimitive)?.longOrNull
        (o["apps"] as? JsonObject)?.forEach { (pkg, value) ->
            val a = value as? JsonObject ?: return@forEach
            overrides[pkg] = Override(
                mode = a.str("mode")?.let { n -> AppCautionMode.entries.firstOrNull { it.name == n } },
                hourly = a.str("hourly")?.toIntOrNull(),
                daily = a.str("daily")?.toIntOrNull(),
                minDelayMs = a.str("minDelayMs")?.toLongOrNull(),
                maxDelayMs = a.str("maxDelayMs")?.toLongOrNull(),
                cooldownMs = a.str("cooldownMs")?.toLongOrNull(),
                searchHourly = a.str("searchHourly")?.toIntOrNull(),
                searchDaily = a.str("searchDaily")?.toIntOrNull(),
                pageHourly = a.str("pageHourly")?.toIntOrNull(),
                pageDaily = a.str("pageDaily")?.toIntOrNull(),
                detailHourly = a.str("detailHourly")?.toIntOrNull(),
                detailDaily = a.str("detailDaily")?.toIntOrNull(),
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
            put("reduceRisk", reduceRisk)
            paceMinMs?.let { put("paceMinMs", it) }
            paceMaxMs?.let { put("paceMaxMs", it) }
            put("apps", buildJsonObject {
                overrides.forEach { (pkg, o) ->
                    put(pkg, buildJsonObject {
                        o.mode?.let { put("mode", it.name) }
                        o.hourly?.let { put("hourly", it) }
                        o.daily?.let { put("daily", it) }
                        o.minDelayMs?.let { put("minDelayMs", it) }
                        o.maxDelayMs?.let { put("maxDelayMs", it) }
                        o.cooldownMs?.let { put("cooldownMs", it) }
                        o.searchHourly?.let { put("searchHourly", it) }
                        o.searchDaily?.let { put("searchDaily", it) }
                        o.pageHourly?.let { put("pageHourly", it) }
                        o.pageDaily?.let { put("pageDaily", it) }
                        o.detailHourly?.let { put("detailHourly", it) }
                        o.detailDaily?.let { put("detailDaily", it) }
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
