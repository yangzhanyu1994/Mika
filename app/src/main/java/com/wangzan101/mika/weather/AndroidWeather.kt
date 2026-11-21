package com.wangzan101.mika.weather

import com.wangzan101.mika.core.location.DeviceLocationPort
import com.wangzan101.mika.core.run.Attendance
import com.wangzan101.mika.core.run.RunOrigin
import com.wangzan101.mika.core.weather.WeatherLookup
import com.wangzan101.mika.core.weather.WeatherPort
import com.wangzan101.mika.core.weather.WeatherReport
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Open-Meteo lookup. Coordinates leave the device only as this request, and only when a fix or a named city exists. */
internal class AndroidWeather(private val location: DeviceLocationPort) : WeatherPort {
    private val lookup = WeatherLookup(
        locate = {
            val reading = location.read(RunOrigin.Ui, Attendance.Present)
            val sample = reading.decision.sample
            WeatherLookup.LocateResult(reading.decision.failure, sample?.latitude, sample?.longitude, reading.place)
        },
        httpGet = ::fetch,
    )

    override suspend fun read(city: String?): WeatherReport = withContext(Dispatchers.IO) { lookup.read(city) }

    private fun fetch(url: String): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8_000
            readTimeout = 8_000
            instanceFollowRedirects = false
            setRequestProperty("Accept", "application/json")
        }
        try {
            val code = connection.responseCode
            if (code !in 200..299) error("weather http $code")
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }
}
