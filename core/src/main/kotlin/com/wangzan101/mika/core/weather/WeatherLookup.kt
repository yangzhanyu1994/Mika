package com.wangzan101.mika.core.weather

import com.wangzan101.mika.core.capability.Capability
import com.wangzan101.mika.core.capability.CapabilityKey
import com.wangzan101.mika.core.capability.CapabilityStatus
import com.wangzan101.mika.core.prompt.Prompts
import com.wangzan101.mika.core.tool.AgentTool
import com.wangzan101.mika.core.tool.Args
import com.wangzan101.mika.core.tool.Risk
import com.wangzan101.mika.core.tool.ToolContext
import com.wangzan101.mika.core.tool.ToolDef
import com.wangzan101.mika.core.tool.ToolExecution
import com.wangzan101.mika.core.tool.Verb
import com.wangzan101.mika.core.tool.str
import java.net.URLEncoder
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

data class WeatherReport(
    val ok: Boolean,
    val failure: String? = null,
    val source: String? = null,
    val place: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val timezone: String? = null,
    val observedAt: String? = null,
    val fetchedAtMs: Long? = null,
    val temperature: String? = null,
    val apparent: String? = null,
    val condition: String? = null,
    val weatherCode: Int? = null,
    val wind: String? = null,
    val forecastDate: String? = null,
    val todayMax: String? = null,
    val todayMin: String? = null,
)

interface WeatherPort {
    suspend fun read(city: String?): WeatherReport
}

internal data class GeoHit(val label: String, val latitude: Double, val longitude: Double)

/**
 * Reads a forecast from Open-Meteo. A named city is geocoded. Otherwise the authorized device fix is used.
 * Missing network, location, or fields stay as failures. Nothing here invents a temperature.
 */
class WeatherLookup(
    private val locate: suspend () -> LocateResult,
    private val httpGet: suspend (String) -> String,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    data class LocateResult(
        val failure: String?,
        val latitude: Double?,
        val longitude: Double?,
        val place: String?,
    )

    suspend fun read(city: String?): WeatherReport {
        val named = city?.trim()?.take(80)?.takeIf { it.isNotEmpty() }
        val hit = if (named != null) {
            val body = try {
                httpGet(OpenMeteo.geocodeUrl(named))
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                return failed("network", place = named)
            }
            OpenMeteo.parseGeocode(body) ?: return failed("unknown_city", place = named)
        } else {
            val loc = try {
                locate()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                return failed("no_location")
            }
            if (loc.failure != null || loc.latitude == null || loc.longitude == null) {
                return failed(loc.failure ?: "no_location", place = loc.place)
            }
            GeoHit(loc.place?.takeIf { it.isNotBlank() } ?: "", loc.latitude, loc.longitude)
        }
        val body = try {
            httpGet(OpenMeteo.forecastUrl(hit.latitude, hit.longitude))
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return failed("network", place = hit.label.ifBlank { null }, latitude = hit.latitude, longitude = hit.longitude)
        }
        return OpenMeteo.parseForecast(body, hit.label.ifBlank { null }, hit.latitude, hit.longitude, nowMs())
    }

    private fun failed(
        failure: String,
        place: String? = null,
        latitude: Double? = null,
        longitude: Double? = null,
    ) = WeatherReport(
        ok = false, failure = failure, source = OpenMeteo.SOURCE, place = place,
        latitude = latitude, longitude = longitude, fetchedAtMs = nowMs(),
    )
}

internal object OpenMeteo {
    const val SOURCE = "open-meteo"
    private val json = Json { ignoreUnknownKeys = true }

    fun geocodeUrl(city: String): String =
        "https://geocoding-api.open-meteo.com/v1/search?count=1&name=" +
            URLEncoder.encode(city, Charsets.UTF_8.name())

    fun forecastUrl(latitude: Double, longitude: Double): String =
        "https://api.open-meteo.com/v1/forecast?latitude=$latitude&longitude=$longitude" +
            "&current=temperature_2m,apparent_temperature,weather_code,wind_speed_10m" +
            "&daily=weather_code,temperature_2m_max,temperature_2m_min&timezone=auto&forecast_days=1"

    fun parseGeocode(body: String): GeoHit? {
        val results = json.parseToJsonElement(body).jsonObject["results"]?.jsonArray ?: return null
        val first = results.firstOrNull()?.jsonObject ?: return null
        val latitude = first.double("latitude") ?: return null
        val longitude = first.double("longitude") ?: return null
        val name = first.string("name") ?: return null
        val country = first.string("country")
        return GeoHit(listOfNotNull(name, country).joinToString(", "), latitude, longitude)
    }

    fun parseForecast(
        body: String,
        place: String?,
        latitude: Double,
        longitude: Double,
        fetchedAtMs: Long,
    ): WeatherReport {
        val root = json.parseToJsonElement(body).jsonObject
        val current = root["current"]?.jsonObject
        val observedAt = current?.string("time")
        val temperature = current?.numberText("temperature_2m")
        if (observedAt.isNullOrBlank() || temperature == null) {
            return WeatherReport(
                ok = false, failure = "incomplete", source = SOURCE, place = place,
                latitude = latitude, longitude = longitude, timezone = root.string("timezone"),
                fetchedAtMs = fetchedAtMs,
            )
        }
        val units = root["current_units"]?.jsonObject
        val daily = root["daily"]?.jsonObject
        val code = current.int("weather_code")
        return WeatherReport(
            ok = true,
            source = SOURCE,
            place = place,
            latitude = latitude,
            longitude = longitude,
            timezone = root.string("timezone"),
            observedAt = observedAt,
            fetchedAtMs = fetchedAtMs,
            temperature = withUnit(temperature, units?.string("temperature_2m")),
            apparent = current.numberText("apparent_temperature")?.let { withUnit(it, units?.string("apparent_temperature")) },
            condition = code?.let { CONDITIONS[it] },
            weatherCode = code,
            wind = current.numberText("wind_speed_10m")?.let { withUnit(it, units?.string("wind_speed_10m")) },
            forecastDate = daily?.strings("time")?.firstOrNull(),
            todayMax = daily?.numbers("temperature_2m_max")?.firstOrNull()?.let { withUnit(it, "°C") },
            todayMin = daily?.numbers("temperature_2m_min")?.firstOrNull()?.let { withUnit(it, "°C") },
        )
    }

    private fun withUnit(value: String, unit: String?): String = if (unit.isNullOrBlank()) value else "$value $unit"

    private fun JsonObject.string(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull
    private fun JsonObject.double(key: String): Double? = this[key]?.jsonPrimitive?.doubleOrNull
    private fun JsonObject.int(key: String): Int? = this[key]?.jsonPrimitive?.intOrNull
    private fun JsonObject.numberText(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull
    private fun JsonObject.strings(key: String): List<String>? =
        this[key]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }
    private fun JsonObject.numbers(key: String): List<String>? =
        this[key]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }

    /** Official WMO code groups. An unknown code stays numeric and is not given a guessed phrase. */
    private val CONDITIONS = mapOf(
        0 to "clear",
        1 to "mainly clear",
        2 to "partly cloudy",
        3 to "overcast",
        45 to "fog",
        48 to "depositing rime fog",
        51 to "light drizzle",
        53 to "moderate drizzle",
        55 to "dense drizzle",
        61 to "slight rain",
        63 to "moderate rain",
        65 to "heavy rain",
        71 to "slight snow",
        73 to "moderate snow",
        75 to "heavy snow",
        80 to "slight rain showers",
        81 to "moderate rain showers",
        82 to "violent rain showers",
        95 to "thunderstorm",
    )
}

internal fun weatherPayload(report: WeatherReport): JsonObject = buildJsonObject {
    put("ok", report.ok)
    report.failure?.let { put("failure", it) }
    report.source?.let { put("source", it) }
    report.place?.let { put("place", it) }
    report.latitude?.let { put("latitude", it) }
    report.longitude?.let { put("longitude", it) }
    report.timezone?.let { put("timezone", it) }
    report.observedAt?.let { put("observedAt", it) }
    report.fetchedAtMs?.let { put("fetchedAtMs", it) }
    report.temperature?.let { put("temperature", it) }
    report.apparent?.let { put("apparent", it) }
    report.condition?.let { put("condition", it) }
    report.weatherCode?.let { put("weatherCode", it) }
    report.wind?.let { put("wind", it) }
    report.forecastDate?.let { put("forecastDate", it) }
    report.todayMax?.let { put("todayMax", it) }
    report.todayMin?.let { put("todayMin", it) }
}

internal class WeatherCapability(val port: WeatherPort) : Capability {
    override val id = "weather"
    override fun status(): CapabilityStatus = CapabilityStatus.Available
    override fun tools(): List<AgentTool> = weatherTools
    override fun sections(env: com.wangzan101.mika.core.capability.PromptEnv): List<String> = listOf(
        "When the user asks about the weather, call read_weather. Pass city only when the user named a place. " +
            "Otherwise the tool uses the authorized device location. Report the source, observation time, and timezone from the result. " +
            "network, no_location, no_permission, services_off, no_fix, unknown_city, and incomplete are the answer: do not invent a forecast.",
    )
    override fun onTaskStart(ctx: ToolContext) { ctx[KEY] = this }
    companion object { val KEY = CapabilityKey<WeatherCapability>("weather") }
}

internal val weatherTools: List<AgentTool> = listOf(ReadWeather)

private object ReadWeather : AgentTool {
    override val capability = "weather"
    override val offScreen = true
    override val risk = Risk.Read
    override val verb = Verb.ReadWeather
    override val label = "天气"
    override val logged = false
    override val def = ToolDef(
        "read_weather",
        Prompts.tool("read_weather"),
        mapOf("city" to str("City or place the user named. Omit it to use the authorized device location.")),
        emptyList(),
    )
    override fun available(ctx: ToolContext) = ctx[WeatherCapability.KEY] != null

    override suspend fun execute(a: Args, ctx: ToolContext): ToolExecution =
        ToolExecution(weatherPayload(ctx[WeatherCapability.KEY]!!.port.read(a.str("city"))))
}
