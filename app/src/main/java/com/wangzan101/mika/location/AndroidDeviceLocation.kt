package com.wangzan101.mika.location

import android.annotation.SuppressLint
import android.content.Context
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import android.os.SystemClock
import com.wangzan101.mika.core.location.DeviceLocationPolicy
import com.wangzan101.mika.core.location.DeviceLocationPort
import com.wangzan101.mika.core.location.DeviceLocationReading
import com.wangzan101.mika.core.location.LocationAccess
import com.wangzan101.mika.core.location.LocationSample
import com.wangzan101.mika.core.run.Attendance
import com.wangzan101.mika.core.run.RunOrigin
import com.wangzan101.mika.settings.FeatureFlags
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import kotlin.coroutines.resume

/**
 * System LocationManager only. No fused/GMS client, so a phone without Google Play
 * can still answer. Coordinates are not written to the log.
 */
class AndroidDeviceLocation(private val context: Context) : DeviceLocationPort {
    @SuppressLint("MissingPermission")
    override suspend fun read(origin: RunOrigin, attendance: Attendance): DeviceLocationReading {
        val access = access()
        val manager = context.getSystemService(LocationManager::class.java)
        val services = manager != null && providers(manager).isNotEmpty()
        val background = EnvironmentBackground.granted(context)
        val auto = FeatureFlags.get(context).autoLocate()
        val needsLive = access != LocationAccess.None && services &&
            (!DeviceLocationPolicy.backgroundRequired(origin, attendance) || (auto && background))
        val cached = if (needsLive) manager?.let { last(it) } else null
        val reading = if (needsLive && cached?.let { it.ageMs > DeviceLocationPolicy.FRESH_MS } != false) manager?.let { current(it) } else null
        val decision = DeviceLocationPolicy.decide(access, services, background, auto, origin, attendance, reading, cached)
        val sample = decision.sample
        val (place, geocoder) = if (sample == null) null to "none" else reverse(sample.latitude, sample.longitude)
        return DeviceLocationReading(decision, place, geocoder, sample?.let { System.currentTimeMillis() - it.ageMs })
    }

    private fun access(): LocationAccess {
        val fine = context.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        val coarse = context.checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        return when {
            fine -> LocationAccess.Fine
            coarse -> LocationAccess.Coarse
            else -> LocationAccess.None
        }
    }

    private fun providers(manager: LocationManager): List<String> =
        listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)
            .filter { runCatching { manager.isProviderEnabled(it) }.getOrDefault(false) }

    @SuppressLint("MissingPermission")
    private fun last(manager: LocationManager): LocationSample? =
        providers(manager).mapNotNull { runCatching { manager.getLastKnownLocation(it) }.getOrNull() }
            .mapNotNull(::sample)
            .filter { it.fromCache }
            .minByOrNull { it.accuracyMeters }

    @SuppressLint("MissingPermission")
    private suspend fun current(manager: LocationManager): LocationSample? {
        val names = providers(manager)
        if (names.isEmpty()) return null
        val result = Channel<Location?>(names.size)
        val signals = mutableListOf<CancellationSignal>()
        try {
            names.forEach { provider ->
                val signal = CancellationSignal().also { signals += it }
                try {
                    manager.getCurrentLocation(provider, signal, context.mainExecutor) { result.trySend(it) }
                } catch (_: Exception) {
                    result.trySend(null)
                }
            }
            return withTimeoutOrNull(15_000) {
                repeat(names.size) {
                    val location = result.receive() ?: return@repeat
                    sample(location)?.copy(fromCache = false)?.let { return@withTimeoutOrNull it }
                }
                null
            }
        } finally {
            signals.forEach { it.cancel() }
            result.close()
        }
    }

    private fun sample(location: Location): LocationSample? {
        val age = (SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos) / 1_000_000L
        if (!location.hasAccuracy() || location.latitude.isNaN() || location.longitude.isNaN()) return null
        return LocationSample(location.latitude, location.longitude, location.accuracy, age, location.provider ?: "unknown", fromCache = true)
    }

    private suspend fun reverse(latitude: Double, longitude: Double): Pair<String?, String> {
        if (!Geocoder.isPresent()) return null to "none"
        val coder = Geocoder(context, Locale.getDefault())
        val addresses = withTimeoutOrNull(8_000) {
            if (Build.VERSION.SDK_INT >= 33) suspendCancellableCoroutine { continuation ->
                try {
                    coder.getFromLocation(latitude, longitude, 1, object : Geocoder.GeocodeListener {
                        override fun onGeocode(addresses: MutableList<android.location.Address>) {
                            if (continuation.isActive) continuation.resume(addresses)
                        }
                        override fun onError(errorMessage: String?) {
                            if (continuation.isActive) continuation.resume(emptyList())
                        }
                    })
                } catch (_: Exception) {
                    if (continuation.isActive) continuation.resume(emptyList())
                }
            } else withContext(Dispatchers.IO) {
                @Suppress("DEPRECATION")
                runCatching { coder.getFromLocation(latitude, longitude, 1).orEmpty() }.getOrDefault(emptyList())
            }
        }
        val place = addresses?.firstOrNull()?.getAddressLine(0)?.takeIf { it.isNotBlank() }?.take(160)
        return if (place == null) null to "unavailable" else place to "system"
    }
}

internal object EnvironmentBackground {
    fun granted(context: Context): Boolean =
        Build.VERSION.SDK_INT < 29 ||
            context.checkSelfPermission(android.Manifest.permission.ACCESS_BACKGROUND_LOCATION) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
}
