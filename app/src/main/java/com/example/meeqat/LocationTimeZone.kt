package com.example.meeqat

import android.content.Context
import java.time.DateTimeException
import java.time.ZoneId

object LocationTimeZone {
    @Volatile
    private var database: TimezoneBoundaryDatabase? = null

    fun forPlace(context: Context, place: LocationStore.Place): ZoneId {
        return forLocation(context, place.id, place.lat, place.lon, place.timeZoneId)
    }

    fun forLocation(
        context: Context,
        placeId: String,
        latitude: Double,
        longitude: Double,
        cachedId: String? = null,
    ): ZoneId {
        validZone(cachedId)?.let { return it }
        val resolvedId = timeZoneId(context, latitude, longitude) ?: return ZoneId.of("UTC")
        val resolvedZone = validZone(resolvedId) ?: return ZoneId.of("UTC")
        LocationStore.cacheTimeZoneId(context, placeId, resolvedId)
        return resolvedZone
    }

    fun timeZoneId(context: Context, latitude: Double, longitude: Double): String? = runCatching {
        database(context).findTimeZoneId(latitude, longitude)
    }.getOrNull()

    private fun validZone(id: String?): ZoneId? = id?.let { runCatching { ZoneId.of(it) }.getOrNull() }

    private fun database(context: Context): TimezoneBoundaryDatabase {
        database?.let { return it }
        return synchronized(this) {
            database ?: context.applicationContext.assets.open("timezone-boundaries.tzm").use { input ->
                TimezoneBoundaryDatabase.open(input.readBytes()).also { database = it }
            }
        }
    }
}




