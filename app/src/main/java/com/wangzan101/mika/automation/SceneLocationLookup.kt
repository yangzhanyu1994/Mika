package com.wangzan101.mika.automation

import android.annotation.SuppressLint
import android.content.Context
import android.location.Address
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import kotlin.coroutines.resume

internal data class ScenePlace(val label: String, val latitude: Double, val longitude: Double)

/** Android providers may be disabled, slow, or unavailable; never present old data as current. */
internal object SceneLocationLookup {
    private const val MAX_CACHE_AGE_NS = 120_000_000_000L
    private const val TIMEOUT_MS = 15_000L

    @SuppressLint("MissingPermission")
    suspend fun current(context: Context): Location? {
        val manager = context.getSystemService(LocationManager::class.java) ?: return null
        val providers = listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)
            .filter { runCatching { manager.isProviderEnabled(it) }.getOrDefault(false) }
        if (providers.isEmpty()) return null
        val cached = providers.mapNotNull { runCatching { manager.getLastKnownLocation(it) }.getOrNull() }
            .filter(::usable).minByOrNull { it.accuracy }
        if (cached != null) return cached
        val result = Channel<Location?>(providers.size)
        val signals = mutableListOf<CancellationSignal>()
        try {
            providers.forEach { provider ->
                val signal = CancellationSignal().also { signals.add(it) }
                try {
                    manager.getCurrentLocation(provider, signal, context.mainExecutor) { result.trySend(it) }
                } catch (_: Exception) { result.trySend(null) }
            }
            return withTimeoutOrNull(TIMEOUT_MS) {
                repeat(providers.size) {
                    val location = result.receive()
                    if (location != null && usable(location)) return@withTimeoutOrNull location
                }
                null
            }
        } finally {
            signals.forEach { it.cancel() }
            result.close()
        }
    }

    private fun usable(location: Location): Boolean {
        val age = SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos
        return age in 0..MAX_CACHE_AGE_NS && location.hasAccuracy() && location.accuracy in 0.1f..500f &&
            location.latitude.isFinite() && location.longitude.isFinite()
    }

    suspend fun search(context: Context, query: String): List<ScenePlace>? {
        if (!Geocoder.isPresent()) return null
        val coder = Geocoder(context, Locale.getDefault())
        val results = withTimeoutOrNull(TIMEOUT_MS) {
            if (Build.VERSION.SDK_INT >= 33) suspendCancellableCoroutine<List<Address>> { continuation ->
                try {
                    coder.getFromLocationName(query, 5, object : Geocoder.GeocodeListener {
                        override fun onGeocode(addresses: MutableList<Address>) {
                            if (continuation.isActive) continuation.resume(addresses)
                        }
                        override fun onError(errorMessage: String?) {
                            if (continuation.isActive) continuation.resume(emptyList())
                        }
                    })
                } catch (_: Exception) { if (continuation.isActive) continuation.resume(emptyList()) }
            } else withContext(Dispatchers.IO) {
                @Suppress("DEPRECATION") runCatching { coder.getFromLocationName(query, 5).orEmpty() }.getOrDefault(emptyList())
            }
        } ?: return null
        return results.mapNotNull { address ->
            if (!address.hasLatitude() || !address.hasLongitude()) return@mapNotNull null
            val label = address.getAddressLine(0)?.takeIf { it.isNotBlank() }
                ?: listOfNotNull(address.featureName, address.locality, address.countryName).distinct().joinToString(", ")
            if (label.isBlank()) null else ScenePlace(label.take(128), address.latitude, address.longitude)
        }.distinctBy { Triple(it.label, it.latitude, it.longitude) }
    }
}
