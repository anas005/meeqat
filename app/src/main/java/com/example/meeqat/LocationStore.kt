package com.example.meeqat

import android.content.Context
import android.content.SharedPreferences
import android.location.Location
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

object LocationStore {
    private const val PREFS = "meeqat_prefs"
    private const val KEY_LOCATIONS = "saved_locations"
    private const val KEY_DEFAULT_ID = "default_location_id"
    private const val WIDGET_LOCATION_PREFIX = "widget_location_"

    data class Place(
        val id: String,
        val lat: Double,
        val lon: Double,
        val name: String,
        val timeZoneId: String? = null,
    )

    data class AddResult(val place: Place, val alreadySaved: Boolean)

    private val DEFAULT = Place("default-chennai", 12.991, 80.215, "Chennai")

    @Synchronized
    fun all(context: Context): List<Place> {
        val prefs = preferences(context)
        val stored = prefs.getString(KEY_LOCATIONS, null)
        if (stored != null) {
            val json = JSONArray(stored)
            val places = (0 until json.length()).map { index ->
                val item = json.getJSONObject(index)
                Place(
                    id = item.getString("id"),
                    lat = item.getDouble("lat"),
                    lon = item.getDouble("lon"),
                    name = item.getString("name"),
                    timeZoneId = item.optString("timeZoneId").takeIf { it.isNotBlank() },
                )
            }
            if (places.isNotEmpty()) return places
        }

        val initial = if (prefs.contains("lat")) {
            Place(
                id = "migrated-location",
                lat = Double.fromBits(prefs.getLong("lat", DEFAULT.lat.toRawBits())),
                lon = Double.fromBits(prefs.getLong("lon", DEFAULT.lon.toRawBits())),
                name = prefs.getString("name", DEFAULT.name) ?: DEFAULT.name,
            )
        } else {
            DEFAULT
        }
        saveAll(prefs, listOf(initial))
        prefs.edit().putString(KEY_DEFAULT_ID, initial.id).apply()
        return listOf(initial)
    }

    fun default(context: Context): Place {
        val places = all(context)
        val prefs = preferences(context)
        val defaultId = prefs.getString(KEY_DEFAULT_ID, null)
        return places.firstOrNull { it.id == defaultId } ?: places.first().also {
            setDefault(context, it.id)
        }
    }

    fun forWidget(context: Context, widgetId: Int): Place? {
        val places = all(context)
        val assignedId = preferences(context).getString(widgetKey(widgetId), null)
        return places.firstOrNull { it.id == assignedId }
    }

    fun forWidgetFlow(context: Context, widgetId: Int): Flow<Place?> = callbackFlow {
        val prefs = preferences(context)
        val key = widgetKey(widgetId)
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, changedKey ->
            if (changedKey == key || changedKey == KEY_LOCATIONS) {
                trySend(forWidget(context, widgetId))
            }
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        trySend(forWidget(context, widgetId))
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }.distinctUntilChanged()

    fun add(context: Context, lat: Double, lon: Double, name: String, timeZoneId: String? = null): AddResult {
        val places = all(context)
        val existing = places.firstOrNull { distanceMeters(it, lat, lon) <= 1_000f }
        if (existing != null) return AddResult(existing, alreadySaved = true)

        val place = Place(UUID.randomUUID().toString(), lat, lon, name, timeZoneId)
        saveAll(preferences(context), places + place)
        return AddResult(place, alreadySaved = false)
    }

    fun upsertCurrent(context: Context, lat: Double, lon: Double, name: String, timeZoneId: String? = null): Place {
        val places = all(context)
        val existing = places.firstOrNull { distanceMeters(it, lat, lon) <= 10_000f }
        val updated = existing?.copy(
            lat = lat,
            lon = lon,
            name = name.takeUnless { it == "Current location" } ?: existing.name,
            timeZoneId = timeZoneId,
        ) ?: Place(UUID.randomUUID().toString(), lat, lon, name, timeZoneId)

        val next = if (existing == null) places + updated else places.map {
            if (it.id == existing.id) updated else it
        }
        saveAll(preferences(context), next)
        setDefault(context, updated.id)
        return updated
    }

    fun setDefault(context: Context, placeId: String) {
        require(all(context).any { it.id == placeId }) { "Unknown location" }
        preferences(context).edit().putString(KEY_DEFAULT_ID, placeId).apply()
    }

    fun assignWidget(context: Context, widgetId: Int, placeId: String) {
        require(widgetId >= 0) { "Invalid widget ID" }
        require(all(context).any { it.id == placeId }) { "Unknown location" }
        preferences(context).edit().putString(widgetKey(widgetId), placeId).apply()
    }

    fun remove(context: Context, placeId: String): Boolean {
        val prefs = preferences(context)
        val places = all(context)
        if (places.size <= 1 || places.none { it.id == placeId }) return false

        val remaining = places.filterNot { it.id == placeId }
        val fallback = remaining.first()
        val editor = prefs.edit().putString(KEY_LOCATIONS, serialize(remaining))
        if (prefs.getString(KEY_DEFAULT_ID, null) == placeId) {
            editor.putString(KEY_DEFAULT_ID, fallback.id)
        }
        prefs.all.forEach { (key, value) ->
            if (key.startsWith(WIDGET_LOCATION_PREFIX) && value == placeId) {
                editor.remove(key)
            }
        }
        editor.apply()
        return true
    }

    fun clearWidgetAssignments(context: Context, widgetIds: IntArray) {
        if (widgetIds.isEmpty()) return
        val editor = preferences(context).edit()
        widgetIds.forEach { editor.remove(widgetKey(it)) }
        editor.apply()
    }

    @Synchronized
    fun cacheTimeZoneId(context: Context, placeId: String, timeZoneId: String) {
        val prefs = preferences(context)
        val places = all(context)
        val updated = places.map { place ->
            if (place.id == placeId && place.timeZoneId != timeZoneId) place.copy(timeZoneId = timeZoneId) else place
        }
        if (updated != places) saveAll(prefs, updated)
    }

    private fun distanceMeters(place: Place, lat: Double, lon: Double): Float {
        val result = FloatArray(1)
        Location.distanceBetween(place.lat, place.lon, lat, lon, result)
        return result[0]
    }

    private fun preferences(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun widgetKey(widgetId: Int) = "$WIDGET_LOCATION_PREFIX$widgetId"

    private fun saveAll(prefs: SharedPreferences, places: List<Place>) {
        prefs.edit().putString(KEY_LOCATIONS, serialize(places)).apply()
    }

    private fun serialize(places: List<Place>): String {
        val json = JSONArray()
        places.forEach { place ->
            json.put(
                JSONObject()
                    .put("id", place.id)
                    .put("lat", place.lat)
                    .put("lon", place.lon)
                    .put("name", place.name)
                    .put("timeZoneId", place.timeZoneId),
            )
        }
        return json.toString()
    }
}
