package com.example.meeqat

import android.annotation.SuppressLint
import android.content.Context
import android.location.Address
import android.location.Geocoder
import android.os.Build
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import java.io.IOException
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

object LocationResolver {
    data class Candidate(val lat: Double, val lon: Double, val name: String)

    suspend fun search(context: Context, query: String): List<Candidate> =
        withContext(Dispatchers.IO) {
            val trimmed = query.trim()
            require(trimmed.isNotEmpty()) { "Enter a place name" }
            val geocoder = geocoder(context)
            val addresses = if (Build.VERSION.SDK_INT >= 33) {
                suspendCancellableCoroutine { continuation ->
                    geocoder.getFromLocationName(trimmed, 5, object : Geocoder.GeocodeListener {
                        override fun onGeocode(addresses: MutableList<Address>) {
                            if (continuation.isActive) continuation.resume(addresses)
                        }

                        override fun onError(errorMessage: String?) {
                            if (continuation.isActive) {
                                continuation.resumeWithException(
                                    IOException(errorMessage ?: "Place search failed"),
                                )
                            }
                        }
                    })
                }
            } else {
                @Suppress("DEPRECATION")
                geocoder.getFromLocationName(trimmed, 5).orEmpty()
            }
            addresses.mapNotNull { address ->
                if (!address.hasLatitude() || !address.hasLongitude()) return@mapNotNull null
                Candidate(
                    lat = address.latitude,
                    lon = address.longitude,
                    name = addressName(address, trimmed),
                )
            }
        }

    @SuppressLint("MissingPermission")
    suspend fun current(context: Context): Candidate {
        val client = LocationServices.getFusedLocationProviderClient(context)
        val location = client
            .getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, CancellationTokenSource().token)
            .await() ?: client.lastLocation.await()
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
