package com.anas.meeqat

import android.app.Application
import android.util.Log
import androidx.glance.appwidget.updateAll
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.anas.meeqat.ui.DayTimes
import com.anas.meeqat.ui.MeeqatUiState
import com.anas.meeqat.ui.SavedLocation
import com.example.meeqat.AstroCalculator
import com.example.meeqat.DaylightChart
import com.example.meeqat.DaylightYearWidget
import com.example.meeqat.HijriCalendar
import com.example.meeqat.LocationResolver
import com.example.meeqat.LocationStore
import com.example.meeqat.LocationTimeZone
import com.example.meeqat.Lunar
import com.example.meeqat.MeeqatWidget
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview

class MeeqatViewModel(application: Application) : AndroidViewModel(application) {
    private val appContext = application.applicationContext
    private val initialSnapshot = loadLocationSnapshot()
    private val initialSelected = initialSnapshot.locations.first { it.isDefault }
    private val initialDate = LocalDate.now()

    private val _uiState = MutableStateFlow(
        MeeqatUiState(
            locations = initialSnapshot.locations,
            selected = initialSelected,
            date = initialDate,
            times = DayTimes(
                fajr = null,
                sunrise = null,
                magrib = null,
                midnight = null,
                lastThird = null,
                moonrise = null,
                moonset = null,
                moonAgeDays = null,
                dayLength = null,
                hijri = null,
            ),
            yearDayLengthHours = emptyList(),
        ),
    )
    val uiState: StateFlow<MeeqatUiState> = _uiState.asStateFlow()

    private data class TimeRequest(val date: LocalDate, val location: SavedLocation)
    private data class YearKey(val locationId: String, val lat: Double, val lon: Double, val year: Int)
    private data class LocationSnapshot(val locations: List<SavedLocation>)

    private val timeRequests = MutableStateFlow(TimeRequest(initialDate, initialSelected))
    private val searchQueries = MutableStateFlow("")
    private var yearKey: YearKey? = null
    private var yearJob: Job? = null

    init {
        viewModelScope.launch {
            timeRequests.collectLatest { request ->
                val times = withContext(Dispatchers.Default) {
                    calculateTimes(request.date, request.location)
                }
                _uiState.update { state ->
                    if (state.date == request.date && state.selected.id == request.location.id) {
                        val selected = state.selected.copy(timeZoneId = times.timeZoneId)
                        state.copy(
                            times = times,
                            selected = selected,
                            locations = state.locations.map { if (it.id == selected.id) selected else it },
                        )
                    } else {
                        state
                    }
                }
            }
        }
        observeLocationSearch()
        requestYear(initialSelected, initialDate.year)
        viewModelScope.launch {
            val localToday = withContext(Dispatchers.IO) {
                val place = LocationStore.default(appContext)
                LocalDate.now(LocationTimeZone.forPlace(appContext, place))
            }
            if (_uiState.value.date == initialDate) setDate(localToday)
        }
    }

    fun onStepDay(delta: Int) {
        if (delta == 0) return
        setDate(_uiState.value.date.plusDays(delta.toLong()))
    }

    fun onSelectDate(date: LocalDate) {
        setDate(date)
    }

    fun onToday() {
        val selected = _uiState.value.selected
        viewModelScope.launch {
            val localToday = withContext(Dispatchers.IO) {
                val place = LocationStore.all(appContext).firstOrNull { it.id == selected.id }
                    ?: LocationStore.default(appContext)
                LocalDate.now(LocationTimeZone.forPlace(appContext, place))
            }
            setDate(localToday)
        }
    }

    private fun setDate(date: LocalDate) {
        _uiState.update { it.copy(date = date) }
        val selected = _uiState.value.selected
        timeRequests.value = TimeRequest(date, selected)
        requestYear(selected, date.year)
    }

    fun onSelectLocation(location: SavedLocation) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { LocationStore.setDefault(appContext, location.id) }
                refreshLocationState(location.id)
                updateWidgets()
            } catch (error: Exception) {
                Log.e(TAG, "Couldn't select location ${location.name}", error)
            }
        }
    }

    fun onRemoveLocation(location: SavedLocation) {
        viewModelScope.launch {
            try {
                val removed = withContext(Dispatchers.IO) {
                    if (LocationStore.all(appContext).size <= 1) false
                    else LocationStore.remove(appContext, location.id)
                }
                if (removed) {
                    refreshLocationState(preferredId = null)
                    updateWidgets()
                }
            } catch (error: Exception) {
                Log.e(TAG, "Couldn't remove location ${location.name}", error)
            }
        }
    }

    fun onSearchQueryChange(query: String) {
        _uiState.update { it.copy(searchQuery = query, searchResults = emptyList(), statusMessage = null) }
        searchQueries.value = query
    }

    fun onAddLocation(location: SavedLocation) {
        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    val zoneId = LocationTimeZone.timeZoneId(appContext, location.lat, location.lon)
                    LocationStore.add(appContext, location.lat, location.lon, location.name, zoneId)
                }
                _uiState.update {
                    it.copy(
                        searchQuery = "",
                        searchResults = emptyList(),
                        statusMessage = if (result.alreadySaved) {
                            "${result.place.name} is already saved."
                        } else {
                            "${result.place.name} added."
                        },
                    )
                }
                refreshLocationState(preferredId = null)
            } catch (error: Exception) {
                Log.e(TAG, "Couldn't add location ${location.name}", error)
            }
        }
    }

    /** Call only after the Activity has confirmed fine or coarse location permission. */
    fun onUseCurrentLocation() {
        viewModelScope.launch {
            try {
                val candidate = LocationResolver.current(appContext)
                val saved = withContext(Dispatchers.IO) {
                    val zoneId = LocationTimeZone.timeZoneId(appContext, candidate.lat, candidate.lon)
                    LocationStore.upsertCurrent(
                        appContext,
                        candidate.lat,
                        candidate.lon,
                        candidate.name,
                        zoneId,
                    )
                }
                refreshLocationState(saved.id)
                updateWidgets()
            } catch (error: Exception) {
                Log.e(TAG, "Couldn't update the current location", error)
            }
        }
    }

    @OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
    private fun observeLocationSearch() {
        viewModelScope.launch {
            searchQueries
                .debounce(350)
                .distinctUntilChanged()
                .collectLatest { query ->
                    if (query.isBlank()) return@collectLatest
                    val results = try {
                        LocationResolver.search(appContext, query).mapIndexed { index, candidate ->
                            SavedLocation(
                                id = "search:$index:${candidate.lat}:${candidate.lon}",
                                name = candidate.name,
                                lat = candidate.lat,
                                lon = candidate.lon,
                            )
                        }
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        Log.w(TAG, "Place search failed for '$query'", error)
                        emptyList()
                    }
                    _uiState.update { state ->
                        if (state.searchQuery.trim() == query.trim()) state.copy(searchResults = results) else state
                    }
                }
        }
    }

    private suspend fun refreshLocationState(preferredId: String?) {
        val snapshot = withContext(Dispatchers.IO) { loadLocationSnapshot() }
        val current = _uiState.value
        val selected = snapshot.locations.firstOrNull { it.id == preferredId }
            ?: snapshot.locations.firstOrNull { it.id == current.selected.id }
            ?: snapshot.locations.first { it.isDefault }
        val updated = current.copy(locations = snapshot.locations, selected = selected)
        _uiState.value = updated
        timeRequests.value = TimeRequest(updated.date, selected)
        requestYear(selected, updated.date.year)
    }

    private fun requestYear(location: SavedLocation, year: Int) {
        val key = YearKey(location.id, location.lat, location.lon, year)
        if (key == yearKey) return
        yearKey = key
        yearJob?.cancel()
        _uiState.update { it.copy(yearDayLengthHours = emptyList()) }
        yearJob = viewModelScope.launch {
            val values = withContext(Dispatchers.Default) {
                DaylightChart.yearDayLengthHours(location.lat, year)
            }
            _uiState.update { state ->
                if (state.date.year == year && state.selected.matches(key)) {
                    state.copy(yearDayLengthHours = values)
                } else {
                    state
                }
            }
        }
    }

    private suspend fun updateWidgets() {
        MeeqatWidget().updateAll(appContext)
        DaylightYearWidget().updateAll(appContext)
    }

    private fun calculateTimes(date: LocalDate, location: SavedLocation): DayTimes {
        val zone = LocationTimeZone.forLocation(
            appContext,
            location.id,
            location.lat,
            location.lon,
            location.timeZoneId,
        )
        val result = AstroCalculator.compute(date, location.lat, location.lon, zone)
        val noon = date.atTime(12, 0).atZone(zone).toInstant()
        val (hijriDay, hijriYear) = HijriCalendar.format(date)
        return DayTimes(
            fajr = result.fajr?.toLocalTime(),
            sunrise = result.sunrise?.toLocalTime(),
            magrib = result.magrib?.toLocalTime(),
            midnight = result.midNight?.toLocalTime(),
            lastThird = result.lastThird?.toLocalTime(),
            moonrise = result.moonrise?.toLocalTime(),
            moonset = result.moonset?.toLocalTime(),
            moonAgeDays = Lunar.ageDays(noon),
            dayLength = result.dayLength,
            timeZoneId = zone.id,
            hijri = "$hijriDay $hijriYear",
        )
    }

    private fun loadLocationSnapshot(): LocationSnapshot {
        val places = LocationStore.all(appContext)
        val default = LocationStore.default(appContext)
        return LocationSnapshot(
            locations = places.map { place -> place.toSavedLocation(place.id == default.id) },
        )
    }

    private fun LocationStore.Place.toSavedLocation(isDefault: Boolean) =
        SavedLocation(id = id, name = name, lat = lat, lon = lon, isDefault = isDefault, timeZoneId = timeZoneId)

    private fun SavedLocation.matches(key: YearKey) =
        id == key.locationId && lat == key.lat && lon == key.lon

    override fun onCleared() {
        yearJob?.cancel()
        super.onCleared()
    }

    private companion object {
        const val TAG = "MeeqatViewModel"
    }
}

