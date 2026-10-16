package com.anas.meeqat.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.ZoneId
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sin
import com.example.meeqat.Lunar
import com.example.meeqat.MoonRenderer
import com.anas.meeqat.ui.theme.LocalMeeqatPalette

// ───────────────────────── Model (adapt to your existing classes) ─────────────────────────

data class SavedLocation(
    val id: String,
    val name: String,
    val lat: Double,
    val lon: Double,
    val isDefault: Boolean = false,
    val timeZoneId: String? = null,
)

data class DayTimes(
    val fajr: LocalTime?, val sunrise: LocalTime?, val magrib: LocalTime?,
    val midnight: LocalTime?, val lastThird: LocalTime?,
    val moonrise: LocalTime?, val moonset: LocalTime?, val moonAgeDays: Double?,
    val dayLength: Duration?,
    val timeZoneId: String? = null,
    val hijri: String?,                      // e.g. "4 Jumada I 1448"
)

data class MeeqatUiState(
    val locations: List<SavedLocation>,
    val selected: SavedLocation,
    val date: LocalDate,
    val times: DayTimes,
    val yearDayLengthHours: List<Float>,     // 365/366 values, Jan 1 → Dec 31, for the selected location
    val searchQuery: String = "",
    val searchResults: List<SavedLocation> = emptyList(),
    val statusMessage: String? = null,
)

data class LocationPickerState(
    val locations: List<SavedLocation>,
    val selectedLocationId: String,
    val searchQuery: String = "",
    val searchResults: List<SavedLocation> = emptyList(),
    val busy: Boolean = false,
    val statusMessage: String? = null,
)

private val timeFmt = DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH)
private val dateFmt = DateTimeFormatter.ofPattern("EEE, d MMM yyyy", Locale.getDefault())
private fun LocalTime?.fmt() = this?.format(timeFmt)?.uppercase(Locale.ENGLISH) ?: "—"
private fun Duration?.fmt() = this?.let { "${toHours()}h ${"%02d".format(toMinutes() % 60)}m" } ?: "—"

// ───────────────────────── Screen ─────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MeeqatBentoScreen(
    state: MeeqatUiState,
    darkTheme: Boolean,
    onToggleTheme: () -> Unit,
    onStepDay: (Int) -> Unit,
    onToday: () -> Unit,
    onSelectDate: (LocalDate) -> Unit,
    onSelectLocation: (SavedLocation) -> Unit,
    onRemoveLocation: (SavedLocation) -> Unit,
    onSearchQueryChange: (String) -> Unit,
    onAddLocation: (SavedLocation) -> Unit,
    onUseCurrentLocation: () -> Unit,
) {
    var sheetOpen by remember { mutableStateOf(false) }
    var datePickerOpen by remember { mutableStateOf(false) }
    val palette = LocalMeeqatPalette.current
    val t = state.times

    Column(
        Modifier.fillMaxSize().background(palette.background).statusBarsPadding().navigationBarsPadding(),
    ) {
        // Header: tap city to open the location sheet
        Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.heightIn(min = 48.dp).clickable(role = Role.Button) { sheetOpen = true }) {
                Text("${state.selected.name} ▾", fontSize = 26.sp, fontWeight = FontWeight.ExtraBold, color = palette.ink)
                Text("%.3f, %.3f".format(state.selected.lat, state.selected.lon), fontSize = 13.sp, color = palette.muted)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ThemeToggleButton(darkTheme, onToggleTheme)
                Pill("Today", onToday)
            }
        }

        // Date stepper: hold either arrow to keep stepping; Gregorian + Hijri inside one container
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 18.dp)
                .clip(RoundedCornerShape(30.dp)).background(palette.surface).padding(6.dp),
            horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically,
        ) {
            HoldToRepeatButton("Previous day", { onStepDay(-1) }) { Text("‹", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = palette.ink) }
            Column(
                modifier = Modifier.clickable(role = Role.Button) { datePickerOpen = true }
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(state.date.format(dateFmt), fontSize = 15.sp, fontWeight = FontWeight.ExtraBold, color = palette.ink)
                Text(t.hijri ?: "—", fontSize = 13.sp, color = palette.muted)
            }
            HoldToRepeatButton("Next day", { onStepDay(1) }) { Text("›", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = palette.ink) }
        }

        Column(
            Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(palette.surface).padding(16.dp)) {
            Text("Daylight through the year", fontSize = 13.sp, color = palette.muted)
            Spacer(Modifier.height(8.dp))
            DaylightChart(
                state.yearDayLengthHours,
                state.date.dayOfYear - 1,
                t.dayLength.fmt(),
                Modifier.fillMaxWidth().height(130.dp),
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TimeTile("Fajr", t.fajr.fmt(), Modifier.weight(1f))
            TimeTile("Sunrise", t.sunrise.fmt(), Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TimeTile("Magrib", t.magrib.fmt(), Modifier.weight(1f))
            TimeTile("Day length", t.dayLength.fmt(), Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TimeTile("Midnight", t.midnight.fmt(), Modifier.weight(1f))
            TimeTile("Last third", t.lastThird.fmt(), Modifier.weight(1f))
        }

        MoonStrip(t, state.date, state.selected.lat, t.timeZoneId)

        }
    }

    if (datePickerOpen) {
        val datePickerState = rememberDatePickerState(
            initialSelectedDateMillis = state.date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        )
        DatePickerDialog(
            onDismissRequest = { datePickerOpen = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        datePickerState.selectedDateMillis?.let { millis ->
                            onSelectDate(Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate())
                        }
                        datePickerOpen = false
                    },
                    enabled = datePickerState.selectedDateMillis != null,
                ) { Text("OK") }
            },
            dismissButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(
                        onClick = {
                            onToday()
                            datePickerOpen = false
                        },
                    ) { Text("Today") }
                    TextButton(onClick = { datePickerOpen = false }) { Text("Cancel") }
                }
            },
        ) {
            DatePicker(state = datePickerState)
        }
    }

    if (sheetOpen) {
        LocationPickerDrawer(
            state = LocationPickerState(
                locations = state.locations,
                selectedLocationId = state.selected.id,
                searchQuery = state.searchQuery,
                searchResults = state.searchResults,
                statusMessage = state.statusMessage,
            ),
            onDismissRequest = { sheetOpen = false },
            onSelect = { onSelectLocation(it); sheetOpen = false },
            onRemove = onRemoveLocation,
            onQueryChange = onSearchQueryChange,
            onAdd = onAddLocation,
            onUseCurrentLocation = onUseCurrentLocation,
        )
    }
}
        @OptIn(ExperimentalMaterial3Api::class)
        @Composable
        fun LocationPickerDrawer(
            state: LocationPickerState,
            onDismissRequest: () -> Unit,
            onSelect: (SavedLocation) -> Unit,
            onRemove: (SavedLocation) -> Unit,
            onQueryChange: (String) -> Unit,
            onAdd: (SavedLocation) -> Unit,
            onUseCurrentLocation: () -> Unit,
        ) {
            ModalBottomSheet(onDismissRequest = onDismissRequest, containerColor = LocalMeeqatPalette.current.surface) {
                LocationPickerContent(
                    state = state,
                    onSelect = onSelect,
                    onRemove = onRemove,
                    onQueryChange = onQueryChange,
                    onAdd = onAdd,
                    onUseCurrentLocation = onUseCurrentLocation,
                )
            }
        }


// ───────────────────────── Pieces ─────────────────────────

@Composable
private fun ThemeToggleButton(darkTheme: Boolean, onClick: () -> Unit) {
    val palette = LocalMeeqatPalette.current
    Box(
        Modifier.size(44.dp)
            .clip(CircleShape)
            .background(palette.surface)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics {
                contentDescription = if (darkTheme) "Switch to light theme" else "Switch to dark theme"
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(20.dp)) {
            val center = Offset(size.width / 2, size.height / 2)
            if (darkTheme) {
                val radius = 4.dp.toPx()
                drawCircle(palette.ink, radius, center)
                for (ray in 0 until 8) {
                    val angle = Math.PI * ray / 4
                    val direction = Offset(cos(angle).toFloat(), sin(angle).toFloat())
                    drawLine(
                        color = palette.ink,
                        start = center + direction * (radius * 1.5f),
                        end = center + direction * (radius * 2.2f),
                        strokeWidth = 1.5.dp.toPx(),
                    )
                }
            } else {
                val radius = 8.dp.toPx()
                drawCircle(palette.ink, radius, center)
                drawCircle(
                    palette.surface,
                    radius,
                    center + Offset(radius * 0.75f, -radius * 0.45f),
                )
            }
        }
    }
}

@Composable
private fun Pill(text: String, onClick: () -> Unit) {
    val palette = LocalMeeqatPalette.current
    Box(
        Modifier.heightIn(min = 44.dp).clip(CircleShape).background(palette.surface).clickable(role = Role.Button, onClick = onClick).padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = palette.ink) }
}

@Composable
private fun TimeTile(label: String, value: String, modifier: Modifier) {
    val palette = LocalMeeqatPalette.current
    Column(modifier.clip(RoundedCornerShape(24.dp)).background(palette.surface).padding(horizontal = 18.dp, vertical = 16.dp)) {
        Text(label, fontSize = 13.sp, color = palette.muted)
        Text(value, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, color = palette.ink, modifier = Modifier.padding(top = 6.dp))
    }
}

/** Fires once on press, then repeats (after 350 ms, every 60 ms) until released. */
@Composable
private fun HoldToRepeatButton(label: String, onStep: () -> Unit, content: @Composable () -> Unit) {
    val scope = rememberCoroutineScope()
    val step by rememberUpdatedState(onStep)
    val palette = LocalMeeqatPalette.current
    Box(
        Modifier.size(48.dp).clip(CircleShape).background(palette.tint)
            .semantics { contentDescription = label; role = Role.Button; onClick { step(); true } }
            .pointerInput(Unit) {
                detectTapGestures(onPress = {
                    step()
                    val job = scope.launch { delay(350); while (true) { step(); delay(60) } }
                    try { awaitRelease() } finally { job.cancel() }
                })
            },
        contentAlignment = Alignment.Center,
    ) { content() }
}

@Composable
private fun MoonStrip(t: DayTimes, date: LocalDate, latitude: Double, timeZoneId: String?) {
    val palette = LocalMeeqatPalette.current
    val phaseImage = remember(date, latitude, timeZoneId) {
        val zone = timeZoneId?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: ZoneId.of("UTC")
        val instant = date.atTime(12, 0).atZone(zone).toInstant()
        MoonRenderer.render(Lunar.elongation(instant), northern = latitude >= 0.0, size = 120).asImageBitmap()
    }
    Row(
        Modifier.fillMaxWidth().clip(CircleShape).background(palette.surface).padding(start = 14.dp, end = 18.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        androidx.compose.foundation.Image(
            bitmap = phaseImage,
            contentDescription = t.moonAgeDays?.let { "Moon phase, %.1f days old".format(it) } ?: "Moon phase",
            modifier = Modifier.size(40.dp),
            contentScale = ContentScale.Fit,
        )
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.SpaceBetween) {
            MoonStat("Moonrise", t.moonrise.fmt()); MoonStat("Moonset", t.moonset.fmt()); MoonStat("Moon age", t.moonAgeDays?.let { "%.1f days".format(it) } ?: "—")
        }
    }
}

@Composable
private fun MoonStat(label: String, value: String) = Column {
    val palette = LocalMeeqatPalette.current
    Text(label, fontSize = 13.sp, color = palette.muted)
    Text(value, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold, color = palette.ink)
}

@Composable
private fun DaylightChart(hours: List<Float>, todayIndex: Int, label: String, modifier: Modifier) {
    val measurer = rememberTextMeasurer()
    val palette = LocalMeeqatPalette.current
    val labelStyle = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Bold, color = palette.ink)
    val axisStyle = TextStyle(fontSize = 9.sp, color = palette.muted)
    Canvas(modifier) {
        if (hours.size < 2) return@Canvas
        val padX = 10.dp.toPx(); val axisH = 16.dp.toPx(); val padTop = 22.dp.toPx()
        val w = size.width - 2 * padX; val h = size.height - axisH - padTop
        val mid = 12f; val span = maxOf(hours.max() - mid, mid - hours.min(), .2f) // ± around 12h
        fun x(i: Int) = padX + w * i / (hours.size - 1)
        fun y(v: Float) = padTop + h / 2 - (v - mid) / span * (h / 2)
        val y12 = y(mid)

        val curve = Path().apply { hours.forEachIndexed { i, v -> if (i == 0) moveTo(x(i), y(v)) else lineTo(x(i), y(v)) } }
        val area = Path().apply {
            moveTo(x(0), y12); hours.forEachIndexed { i, v -> lineTo(x(i), y(v)) }; lineTo(x(hours.lastIndex), y12); close()
        }
        clipRect(top = 0f, bottom = y12) { drawPath(area, palette.dayFill) }
        clipRect(top = y12, bottom = size.height) { drawPath(area, palette.nightFill) }
        drawLine(palette.muted, Offset(padX, y12), Offset(padX + w, y12), 1.dp.toPx(),
            pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(8f, 8f)))
        drawPath(curve, palette.chartAccent, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round))

        val i = todayIndex.coerceIn(0, hours.lastIndex); val mx = x(i); val my = y(hours[i])
        drawLine(palette.ink, Offset(mx, my), Offset(mx, padTop + h), 1.dp.toPx())
        drawCircle(palette.ink, 5.dp.toPx(), Offset(mx, my))
        val lm = measurer.measure(label, labelStyle)
        drawText(lm, topLeft = Offset((mx - lm.size.width / 2f).coerceIn(0f, size.width - lm.size.width), my - lm.size.height - 8.dp.toPx()))

        for (m in 1..12) { // month numbers
            val tm = measurer.measure(m.toString(), axisStyle)
            drawText(tm, topLeft = Offset(padX + w * (m - .5f) / 12 - tm.size.width / 2f, size.height - tm.size.height))
        }
    }
}

@Composable
fun LocationPickerContent(
    state: LocationPickerState,
    onSelect: (SavedLocation) -> Unit, onRemove: (SavedLocation) -> Unit,
    onQueryChange: (String) -> Unit, onAdd: (SavedLocation) -> Unit,
    onUseCurrentLocation: () -> Unit,
) {
    val palette = LocalMeeqatPalette.current
    LazyColumn(Modifier.fillMaxWidth().padding(horizontal = 18.dp).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { Text("Locations", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = palette.ink) }
        item {
            Button(
                onClick = onUseCurrentLocation,
                enabled = !state.busy,
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                colors = ButtonDefaults.buttonColors(containerColor = palette.forest),
                shape = RoundedCornerShape(26.dp),
            ) { Text("Add Current Location", fontWeight = FontWeight.Bold) }
        }
        items(state.locations, key = { it.id }) { loc ->
            val current = loc.id == state.selectedLocationId
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(palette.background)
                    .clickable(enabled = !state.busy, role = Role.RadioButton) { onSelect(loc) }.padding(start = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = current, onClick = null)
                Column(Modifier.weight(1f).padding(12.dp)) {
                    Text(loc.name, fontWeight = FontWeight.Bold, color = palette.ink)
                    Text("%.3f, %.3f%s".format(loc.lat, loc.lon, if (loc.isDefault) " · Default" else ""), fontSize = 12.sp, color = palette.muted)
                }
                if (state.locations.size > 1) TextButton(onClick = { onRemove(loc) }, enabled = !state.busy) { Text("Remove") }
            }
        }
        item {
            OutlinedTextField(
                value = state.searchQuery, onValueChange = onQueryChange, singleLine = true,
                placeholder = { Text("Search for a city or place") },
                enabled = !state.busy,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                shape = RoundedCornerShape(16.dp),
            )
        }
        if (state.busy) {
            item {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
        }
        state.statusMessage?.let { message ->
            item { Text(message, fontSize = 13.sp, color = palette.muted) }
        }
        items(state.searchResults, key = { "r-" + it.id }) { r ->
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(palette.background).padding(start = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(vertical = 12.dp)) {
                    Text(r.name, fontWeight = FontWeight.Bold, color = palette.ink)
                    Text("%.3f, %.3f".format(r.lat, r.lon), fontSize = 12.sp, color = palette.muted)
                }
                TextButton(onClick = { onAdd(r) }, enabled = !state.busy) { Text("Add") }
            }
        }
        item { Spacer(Modifier.height(16.dp)) }
    }
}
