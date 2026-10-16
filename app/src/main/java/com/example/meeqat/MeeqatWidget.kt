package com.example.meeqat

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ColumnScope
import androidx.glance.layout.Row
import androidx.glance.layout.RowScope
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.anas.meeqat.MainActivity
import com.anas.meeqat.ui.theme.DarkPalette
import com.anas.meeqat.ui.theme.LightPalette
import com.anas.meeqat.ui.theme.MeeqatPalette
import com.anas.meeqat.ui.theme.ThemePreferences
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

class MeeqatWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val appWidgetId = GlanceAppWidgetManager(context).getAppWidgetId(id)
        val appIntent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val setupIntent = Intent(context, MainActivity::class.java)
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        provideContent {
            val locationFlow = remember(context, appWidgetId) {
                LocationStore.forWidgetFlow(context, appWidgetId)
            }
            val place by locationFlow.collectAsState(
                initial = LocationStore.forWidget(context, appWidgetId),
            )
            val darkThemeFlow = remember(context) {
                ThemePreferences.darkThemeFlow(context)
            }
            val darkTheme by darkThemeFlow.collectAsState(initial = ThemePreferences.isDark(context))
            val palette = if (darkTheme) DarkPalette else LightPalette
            val data = place?.let { WidgetData.build(context, it) }
            WidgetContent(data, appIntent, setupIntent, palette)
        }
    }
}

data class WidgetData(
    val place: String, val dateLine: String,
    val fajr: String, val sunrise: String, val magrib: String, val dayLength: String,
    val midNight: String, val lastThird: String, val moonrise: String, val moonset: String,
    val moonAge: String, val hijriDay: String, val hijriYear: String,
) {
    companion object {
        private val timeFmt = DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH)
        private val dateFmt = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH)

        fun build(context: Context, place: LocationStore.Place): WidgetData {
            val zone = LocationTimeZone.forPlace(context, place)
            return build(place, LocalDate.now(zone), zone)
        }

        fun build(place: LocationStore.Place, date: LocalDate = LocalDate.now()): WidgetData {
            val zone = place.timeZoneId?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: ZoneId.of("UTC")
            return build(place, date, zone)
        }

        private fun build(place: LocationStore.Place, date: LocalDate, zone: ZoneId): WidgetData {
            val dateAtNoon = date.atTime(12, 0).atZone(zone)

            val r = AstroCalculator.compute(date, place.lat, place.lon, zone)
            val age = Lunar.ageDays(dateAtNoon.toInstant())
            val (hDay, hYear) = HijriCalendar.format(date)

            return WidgetData(
                place = place.name,
                dateLine = dateFmt.format(date),
                fajr = t(r.fajr), sunrise = t(r.sunrise), magrib = t(r.magrib),
                dayLength = dur(r.dayLength),
                midNight = t(r.midNight), lastThird = t(r.lastThird),
                moonrise = t(r.moonrise), moonset = t(r.moonset),
                moonAge = String.format(Locale.US, "%.1f days", age),
                hijriDay = hDay, hijriYear = hYear,
            )
        }

        private fun t(z: ZonedDateTime?) = z?.let { timeFmt.format(it).uppercase(Locale.ENGLISH) } ?: "--"

        private fun dur(d: Duration?) = d?.let { "${it.toHours()}h ${it.toMinutes() % 60}m" } ?: "--"

    }
}

@Composable
private fun WidgetContent(d: WidgetData?, appIntent: Intent, setupIntent: Intent, palette: MeeqatPalette) {
    val ink = ColorProvider(palette.ink)
    val muted = ColorProvider(palette.muted)
    val labelStyle = TextStyle(color = muted, fontSize = 10.sp)
    val valueStyle = TextStyle(color = ink, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    if (d == null) {
        Box(
            GlanceModifier.fillMaxSize()
                .background(ColorProvider(palette.background))
                .clickable(actionStartActivity(setupIntent)),
            contentAlignment = Alignment.Center,
        ) {
            Text("Select a location", style = TextStyle(color = ink, fontSize = 13.sp, fontWeight = FontWeight.Medium))
        }
    } else {
        Column(
            GlanceModifier.fillMaxSize()
                .background(ColorProvider(palette.background))
                .padding(horizontal = 10.dp, vertical = 9.dp)
                .clickable(actionStartActivity(appIntent)),
        ) {
            Row(
                GlanceModifier.fillMaxWidth().padding(bottom = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    d.place,
                    modifier = GlanceModifier.defaultWeight().padding(horizontal = 7.dp),
                    style = TextStyle(color = ink, fontSize = 12.sp, fontWeight = FontWeight.Bold),
                    maxLines = 1,
                )
                Text(
                    d.dateLine,
                    modifier = GlanceModifier.defaultWeight().padding(horizontal = 7.dp),
                    style = TextStyle(
                        color = ink,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Start,
                    ),
                    maxLines = 1,
                )
            }
            TileRow("Fajr", d.fajr, "Sunrise", d.sunrise, labelStyle, valueStyle)
            TileRow("Magrib", d.magrib, "Day length", d.dayLength, labelStyle, valueStyle)
            TileRow("Midnight", d.midNight, "Last third", d.lastThird, labelStyle, valueStyle)
            TileRow("Moonrise", d.moonrise, "Moonset", d.moonset, labelStyle, valueStyle)
            Row(GlanceModifier.fillMaxWidth().defaultWeight()) {
                Tile("Moon age", d.moonAge, labelStyle, valueStyle)
                Tile("Hijri ${d.hijriYear}", d.hijriDay, labelStyle, valueStyle)
            }
        }
    }
}

@Composable
private fun ColumnScope.TileRow(
    l1: String,
    v1: String,
    l2: String,
    v2: String,
    labelStyle: TextStyle,
    valueStyle: TextStyle,
) {
    Row(GlanceModifier.fillMaxWidth().defaultWeight()) {
        Tile(l1, v1, labelStyle, valueStyle)
        Tile(l2, v2, labelStyle, valueStyle)
    }
}

@Composable
private fun RowScope.Tile(label: String, value: String, labelStyle: TextStyle, valueStyle: TextStyle) {
    Box(
        GlanceModifier.defaultWeight()
            .fillMaxHeight()
            .padding(horizontal = 5.dp, vertical = 3.dp),
    ) {
        Column(
            GlanceModifier.fillMaxSize().padding(horizontal = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, style = labelStyle, maxLines = 1)
            Text(value, style = valueStyle, maxLines = 1)
        }
    }
}
