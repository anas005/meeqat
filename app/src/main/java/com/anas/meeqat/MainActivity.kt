package com.anas.meeqat

import android.Manifest
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.anas.meeqat.ui.theme.MeeqatTheme
import com.anas.meeqat.ui.theme.LocalMeeqatPalette
import com.anas.meeqat.ui.theme.ThemePreferences
import com.example.meeqat.DaylightYearWidget
import com.example.meeqat.MeeqatWidget
import com.anas.meeqat.ui.MeeqatBentoScreen
import com.anas.meeqat.ui.LocationPickerDrawer
import com.anas.meeqat.ui.LocationPickerState
import com.anas.meeqat.ui.SavedLocation
import com.example.meeqat.LocationResolver
import com.example.meeqat.LocationStore
import com.example.meeqat.LocationTimeZone
import androidx.glance.appwidget.updateAll
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val appWidgetId = intent.getIntExtra(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID,
        )
        val isWidgetConfiguration = appWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID
        if (isWidgetConfiguration) {
            setResult(
                RESULT_CANCELED,
                Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId),
            )
        }
        if (!isWidgetConfiguration) {
            val viewModel by viewModels<MeeqatViewModel>()
            setContent {
                var darkTheme by remember {
                    mutableStateOf(ThemePreferences.isDark(this@MainActivity))
                }
                val scope = rememberCoroutineScope()
                SideEffect {
                    WindowCompat.getInsetsController(this@MainActivity.window, this@MainActivity.window.decorView).apply {
                        isAppearanceLightStatusBars = !darkTheme
                        isAppearanceLightNavigationBars = !darkTheme
                    }
                }
                MeeqatTheme(darkTheme = darkTheme) {
                    MainBentoScreen(
                        viewModel = viewModel,
                        darkTheme = darkTheme,
                        onToggleTheme = {
                            darkTheme = !darkTheme
                            ThemePreferences.setDark(this@MainActivity, darkTheme)
                            scope.launch {
                                MeeqatWidget().updateAll(applicationContext)
                                DaylightYearWidget().updateAll(applicationContext)
                            }
                        },
                    )
                }
            }
        } else {
            setContent {
                val darkTheme = ThemePreferences.isDark(this@MainActivity)
                SideEffect {
                    WindowCompat.getInsetsController(this@MainActivity.window, this@MainActivity.window.decorView).apply {
                        isAppearanceLightStatusBars = !darkTheme
                        isAppearanceLightNavigationBars = !darkTheme
                    }
                }
                MeeqatTheme(darkTheme = darkTheme) {
                    WidgetLocationSetupScreen(
                        widgetId = appWidgetId,
                        onWidgetConfigured = { configuredId ->
                            setResult(
                                RESULT_OK,
                                Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, configuredId),
                            )
                            finish()
                        },
                        onDismiss = { finish() },
                    )
                }
            }
        }
    }

}

@Composable
private fun MainBentoScreen(
    viewModel: MeeqatViewModel,
    darkTheme: Boolean,
    onToggleTheme: () -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        if (grants.values.any { it }) viewModel.onUseCurrentLocation()
    }

    fun useCurrentLocation() {
        val fineGranted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
        val coarseGranted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
        if (fineGranted || coarseGranted) {
            viewModel.onUseCurrentLocation()
        } else {
            permissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                ),
            )
        }
    }

    MeeqatBentoScreen(
        state = state,
        darkTheme = darkTheme,
        onToggleTheme = onToggleTheme,
        onStepDay = viewModel::onStepDay,
        onToday = viewModel::onToday,
        onSelectDate = viewModel::onSelectDate,
        onSelectLocation = viewModel::onSelectLocation,
        onRemoveLocation = viewModel::onRemoveLocation,
        onSearchQueryChange = viewModel::onSearchQueryChange,
        onAddLocation = viewModel::onAddLocation,
        onAddManualLocation = viewModel::onAddManualLocation,
        onEditLocation = viewModel::onEditLocation,
        onUseCurrentLocation = ::useCurrentLocation,
    )
}

@Composable
private fun WidgetLocationSetupScreen(
    widgetId: Int,
    onWidgetConfigured: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var places by remember { mutableStateOf(LocationStore.all(context)) }
    var selectedWidgetLocationId by remember(widgetId) {
        mutableStateOf(LocationStore.forWidget(context, widgetId)?.id.orEmpty())
    }
    var searchQuery by remember { mutableStateOf("") }
    var searchResults by remember { mutableStateOf(emptyList<SavedLocation>()) }
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    suspend fun refreshLocations() {
        places = withContext(Dispatchers.IO) { LocationStore.all(context) }
        selectedWidgetLocationId = LocationStore.forWidget(context, widgetId)?.id.orEmpty()
    }

    suspend fun updateWidgets() {
        MeeqatWidget().updateAll(context)
        DaylightYearWidget().updateAll(context)
    }

    fun runLocationAction(action: suspend () -> Unit) {
        scope.launch {
            busy = true
            status = null
            try {
                action()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                status = error.message ?: "Couldn't update this widget's location."
            } finally {
                busy = false
            }
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        if (grants.values.any { it }) {
            runLocationAction {
                val candidate = LocationResolver.current(context)
                withContext(Dispatchers.IO) {
                    val zoneId = LocationTimeZone.timeZoneId(context, candidate.lat, candidate.lon)
                    val place = LocationStore.upsertCurrent(
                        context,
                        candidate.lat,
                        candidate.lon,
                        candidate.name,
                        zoneId,
                    )
                    LocationStore.assignWidget(context, widgetId, place.id)
                }
                updateWidgets()
                onWidgetConfigured(widgetId)
            }
        } else {
            status = "Location permission is needed to get your current location."
        }
    }

    fun requestCurrentLocation() {
        val hasFine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        val hasCoarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        if (hasFine || hasCoarse) {
            runLocationAction {
                val candidate = LocationResolver.current(context)
                withContext(Dispatchers.IO) {
                    val zoneId = LocationTimeZone.timeZoneId(context, candidate.lat, candidate.lon)
                    val place = LocationStore.upsertCurrent(
                        context,
                        candidate.lat,
                        candidate.lon,
                        candidate.name,
                        zoneId,
                    )
                    LocationStore.assignWidget(context, widgetId, place.id)
                }
                updateWidgets()
                onWidgetConfigured(widgetId)
            }
        } else {
            permissionLauncher.launch(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
            )
        }
    }

    LaunchedEffect(searchQuery) {
        val query = searchQuery.trim()
        if (query.isBlank()) {
            searchResults = emptyList()
        } else {
            delay(350)
            try {
                searchResults = LocationResolver.search(query).mapIndexed { index, candidate ->
                    SavedLocation(
                        id = "search:$index:${candidate.lat}:${candidate.lon}",
                        name = candidate.name,
                        lat = candidate.lat,
                        lon = candidate.lon,
                    )
                }
                if (searchResults.isEmpty()) status = "No matching places found."
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                status = error.message ?: "Couldn't search for that place."
            }
        }
    }

    fun selectLocation(location: SavedLocation) {
        runLocationAction {
            withContext(Dispatchers.IO) {
                LocationStore.assignWidget(context, widgetId, location.id)
            }
            updateWidgets()
            onWidgetConfigured(widgetId)
        }
    }

    fun addLocation(location: SavedLocation) {
        runLocationAction {
            val place = withContext(Dispatchers.IO) {
                val zoneId = LocationTimeZone.timeZoneId(context, location.lat, location.lon)
                LocationStore.add(context, location.lat, location.lon, location.name, zoneId).also {
                    LocationStore.assignWidget(context, widgetId, it.place.id)
                }
            }
            selectedWidgetLocationId = place.place.id
            updateWidgets()
            onWidgetConfigured(widgetId)
        }
    }

    fun addManualLocation(name: String, lat: Double, lon: Double) {
        addLocation(SavedLocation(id = "", name = name, lat = lat, lon = lon))
    }

    fun editLocation(location: SavedLocation, name: String, lat: Double, lon: Double) {
        runLocationAction {
            withContext(Dispatchers.IO) {
                val zoneId = LocationTimeZone.timeZoneId(context, lat, lon)
                LocationStore.update(context, location.id, name, lat, lon, zoneId)
            }
            refreshLocations()
            updateWidgets()
        }
    }

    fun removeLocation(location: SavedLocation) {
        runLocationAction {
            withContext(Dispatchers.IO) { LocationStore.remove(context, location.id) }
            refreshLocations()
            updateWidgets()
            status = "${location.name} removed."
        }
    }

    val pickerState = LocationPickerState(
        locations = places.map { place ->
            SavedLocation(
                id = place.id,
                name = place.name,
                lat = place.lat,
                lon = place.lon,
                isDefault = false,
                timeZoneId = place.timeZoneId,
            )
        },
        selectedLocationId = selectedWidgetLocationId,
        searchQuery = searchQuery,
        searchResults = searchResults,
        busy = busy,
        statusMessage = status,
    )

    Box(
        modifier = Modifier.fillMaxSize().background(LocalMeeqatPalette.current.background),
    ) {
        LocationPickerDrawer(
            state = pickerState,
            onDismissRequest = onDismiss,
            onSelect = ::selectLocation,
            onRemove = ::removeLocation,
            onQueryChange = { searchQuery = it; status = null },
            onAdd = ::addLocation,
            onAddManual = ::addManualLocation,
            onEdit = ::editLocation,
            onUseCurrentLocation = ::requestCurrentLocation,
        )
    }
}
