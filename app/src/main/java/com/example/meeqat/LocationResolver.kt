package com.example.meeqat

import android.annotation.SuppressLint
import android.content.Context
import android.location.Address
import android.location.Geocoder
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import android.os.Looper
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

object LocationResolver {
    data class Candidate(val lat: Double, val lon: Double, val name: String)

    private const val FIX_TIMEOUT_MS = 20_000L
    private const val USER_AGENT = "Meeqat/1.0 (https://github.com/anas005/meeqat)"

    suspend fun search(query: String): List<Candidate> =
        withContext(Dispatchers.IO) {
            val trimmed = query.trim()
            require(trimmed.isNotEmpty()) { "Enter a place name" }
            val url = URL(
                "https://photon.komoot.io/api/?limit=8&q=" +
                    URLEncoder.encode(trimmed, "UTF-8"),
            )
            val connection = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000
                readTimeout = 10_000
                setRequestProperty("User-Agent", USER_AGENT)
                setRequestProperty("Accept", "application/json")
            }
            try {
                if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                    throw IOException("Place search failed (HTTP ${connection.responseCode})")
                }
                val features = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
                    .getJSONArray("features")
                (0 until features.length()).mapNotNull { index ->
                    val feature = features.getJSONObject(index)
                    val coordinates = feature.getJSONObject("geometry").getJSONArray("coordinates")
                    val properties = feature.getJSONObject("properties")
                    val name = properties.optString("name").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    Candidate(lat = coordinates.getDouble(1), lon = coordinates.getDouble(0), name = name)
                }
                    .distinctBy { Triple(it.name, (it.lat * 100).roundToInt(), (it.lon * 100).roundToInt()) }
            } finally {
                connection.disconnect()
            }
        }

    @SuppressLint("MissingPermission")
    suspend fun current(context: Context): Candidate {
        val manager = context.getSystemService(LocationManager::class.java)
        val location = requestFreshLocation(context, manager)
            ?: lastKnownLocation(manager)
            ?: throw IOException("Couldn't get your location. Check that location services are on.")

        val fallback = LocationStore.all(context).firstOrNull {
            val result = FloatArray(1)
            android.location.Location.distanceBetween(it.lat, it.lon, location.latitude, location.longitude, result)
            result[0] <= 10_000f
        }?.name ?: "Current location"

        val name = try {
            reverseName(context, location.latitude, location.longitude, fallback)
        } catch (error: IOException) {
            fallback
        }
        return Candidate(location.latitude, location.longitude, name)
    }

    private fun enabledProvider(manager: LocationManager): String? =
        listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .firstOrNull { manager.isProviderEnabled(it) }

    @SuppressLint("MissingPermission")
    private suspend fun requestFreshLocation(context: Context, manager: LocationManager): Location? {
        val provider = enabledProvider(manager) ?: return null
        return withTimeoutOrNull(FIX_TIMEOUT_MS) {
            suspendCancellableCoroutine { continuation ->
                if (Build.VERSION.SDK_INT >= 30) {
                    val signal = CancellationSignal()
                    continuation.invokeOnCancellation { signal.cancel() }
                    manager.getCurrentLocation(
                        provider,
                        signal,
                        ContextCompat.getMainExecutor(context),
                    ) { location ->
                        if (continuation.isActive) continuation.resume(location)
                    }
                } else {
                    val listener = object : LocationListener {
                        override fun onLocationChanged(location: Location) {
                            if (continuation.isActive) continuation.resume(location)
                        }

                        override fun onProviderEnabled(provider: String) = Unit

                        override fun onProviderDisabled(provider: String) = Unit

                        @Deprecated("Deprecated in Java")
                        override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) = Unit
                    }
                    continuation.invokeOnCancellation { manager.removeUpdates(listener) }
                    manager.requestSingleUpdate(provider, listener, Looper.getMainLooper())
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun lastKnownLocation(manager: LocationManager): Location? =
        listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .mapNotNull { manager.getLastKnownLocation(it) }
            .maxByOrNull { it.time }

    private suspend fun reverseName(
        context: Context,
        lat: Double,
        lon: Double,
        fallback: String,
    ): String = withContext(Dispatchers.IO) {
        val geocoder = geocoder(context)
        val addresses = if (Build.VERSION.SDK_INT >= 33) {
            suspendCancellableCoroutine { continuation ->
                geocoder.getFromLocation(lat, lon, 1, object : Geocoder.GeocodeListener {
                    override fun onGeocode(addresses: MutableList<Address>) {
                        if (continuation.isActive) continuation.resume(addresses)
                    }

                    override fun onError(errorMessage: String?) {
                        if (continuation.isActive) {
                            continuation.resumeWithException(
                                IOException(errorMessage ?: "Address lookup failed"),
                            )
                        }
                    }
                })
            }
        } else {
            @Suppress("DEPRECATION")
            geocoder.getFromLocation(lat, lon, 1).orEmpty()
        }
        addresses.firstOrNull()?.let { addressName(it, fallback) } ?: fallback
    }

    private fun geocoder(context: Context): Geocoder {
        if (!Geocoder.isPresent()) throw IOException("Location search is not available on this device")
        return Geocoder(context, Locale.getDefault())
    }

    private fun addressName(address: Address, fallback: String): String =
        address.locality
            ?: address.subAdminArea
            ?: address.adminArea
            ?: address.featureName
            ?: fallback
}
