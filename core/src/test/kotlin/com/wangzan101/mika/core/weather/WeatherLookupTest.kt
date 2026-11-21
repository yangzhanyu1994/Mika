package com.wangzan101.mika.core.weather

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WeatherLookupTest {
    @Test fun namedCityUsesTheGeocoderAndKeepsObservationTime() = runBlocking {
        val lookup = lookup(locationFailure = "no_location") { url ->
            if ("search" in url) {
                """{"results":[{"name":"Shanghai","country":"China","latitude":31.2,"longitude":121.5}]}"""
            } else {
                forecast
            }
        }
        val report = lookup.read("上海")
        assertEquals(true, report.ok)
        assertEquals("open-meteo", report.source)
        assertEquals("Shanghai, China", report.place)
        assertEquals("2026-11-21T10:00", report.observedAt)
        assertEquals("Asia/Shanghai", report.timezone)
        assertEquals("18.2 °C", report.temperature)
        assertEquals("mainly clear", report.condition)
        assertEquals("2026-11-21", report.forecastDate)
        assertEquals(1_700_000_000_000L, report.fetchedAtMs)
    }

    @Test fun missingLocationIsNotAForecast() = runBlocking {
        val report = lookup(locationFailure = "no_permission") { error("network") }.read(null)
        assertEquals(false, report.ok)
        assertEquals("no_permission", report.failure)
        assertNull(report.temperature)
    }

    @Test fun networkFailureStaysAFailure() = runBlocking {
        val report = lookup(locationFailure = null, latitude = 31.2, longitude = 121.5) { error("offline") }.read(null)
        assertEquals("network", report.failure)
        assertNull(report.temperature)
    }

    @Test fun unknownCityAndIncompleteBodyAreNotFilledIn() = runBlocking {
        val unknown = lookup { """{"results":[]}""" }.read("Nowhereville")
        assertEquals("unknown_city", unknown.failure)
        val incomplete = lookup { url ->
            if ("search" in url) """{"results":[{"name":"Shanghai","latitude":1.0,"longitude":2.0}]}"""
            else """{"current":{"temperature_2m":1}}"""
        }.read("上海")
        assertEquals("incomplete", incomplete.failure)
        assertNull(incomplete.temperature)
    }

    @Test fun unknownWeatherCodeHasNoInventedCondition() {
        val report = OpenMeteo.parseForecast(
            """{"timezone":"UTC","current":{"time":"2026-11-21T00:00","temperature_2m":1,"weather_code":999},
                "current_units":{"temperature_2m":"°C"}}""",
            "X", 1.0, 2.0, 5L,
        )
        assertEquals(true, report.ok)
        assertEquals(999, report.weatherCode)
        assertNull(report.condition)
    }

    private fun lookup(
        locationFailure: String? = null,
        latitude: Double? = null,
        longitude: Double? = null,
        http: suspend (String) -> String,
    ) = WeatherLookup(
        locate = { WeatherLookup.LocateResult(locationFailure, latitude, longitude, null) },
        httpGet = http,
        nowMs = { 1_700_000_000_000L },
    )

    private val forecast = """
        {"timezone":"Asia/Shanghai",
         "current":{"time":"2026-11-21T10:00","temperature_2m":18.2,"apparent_temperature":17.0,"weather_code":1,"wind_speed_10m":3.0},
         "current_units":{"temperature_2m":"°C","apparent_temperature":"°C","wind_speed_10m":"km/h"},
         "daily":{"time":["2026-11-21"],"temperature_2m_max":[20.0],"temperature_2m_min":[14.0]}}
    """.trimIndent()
}
