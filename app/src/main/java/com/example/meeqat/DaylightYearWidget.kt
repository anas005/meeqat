package com.example.meeqat

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.text.TextUtils
import android.text.TextPaint
import androidx.compose.runtime.remember
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.toArgb
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.ContentScale
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.padding
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.anas.meeqat.MainActivity
import com.anas.meeqat.ui.theme.DarkPalette
import com.anas.meeqat.ui.theme.LightPalette
import com.anas.meeqat.ui.theme.MeeqatPalette
import com.anas.meeqat.ui.theme.ThemePreferences
import java.time.Duration
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/** 4x1 widget: day length across the year, shaded orange above 12 hours and blue below, with today marked. */
class DaylightYearWidget : GlanceAppWidget() {
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
            if (place == null) {
                Box(
                    GlanceModifier.fillMaxSize()
                        .background(ColorProvider(palette.background))
                        .clickable(actionStartActivity(setupIntent)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "Select a location",
                        style = TextStyle(color = ColorProvider(palette.ink)),
                    )
                }
            } else {
                val selectedPlace = requireNotNull(place)
                val zone = remember(selectedPlace) {
                    LocationTimeZone.forPlace(context, selectedPlace)
                }
                val date = LocalDate.now(zone)
                val bitmap = remember(selectedPlace, darkTheme, date) {
                    DaylightChart.render(
                        selectedPlace.lat,
                        selectedPlace.lon,
                        date,
                        zone,
                        locationName = selectedPlace.name,
                        palette = palette,
                    )
                }
                Box(
                    GlanceModifier.fillMaxSize()
                        .background(ColorProvider(palette.background))
                        .padding(horizontal = 10.dp, vertical = 5.dp)
                        .clickable(actionStartActivity(appIntent)),
                    contentAlignment = Alignment.Center,
                ) {
                    Image(
                        ImageProvider(bitmap),
                        contentDescription = "Day length through the year",
                        modifier = GlanceModifier.fillMaxSize(),
                        contentScale = ContentScale.Fit,
                    )
                }
            }
        }
    }
}

class DaylightYearWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = DaylightYearWidget()

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        MidnightWorker.schedule(context)
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        MidnightWorker.cancelIfNoWidgets(context)
    }
}

object DaylightChart {
    private const val W = 300f
    private const val H = 82f
    private const val GRAPH_TOP = 12f
    private const val X0 = 6f
    private const val X1 = W - 6f
    private val dateFmt = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH)

    fun yearDayLengthHours(lat: Double, year: Int): List<Float> {
        val daysInYear = LocalDate.of(year, 1, 1).lengthOfYear()
        val latRad = Math.toRadians(lat)
        return List(daysInYear) { day -> dayLength(day + 1, daysInYear, latRad).toFloat() }
    }

    fun render(
        lat: Double,
        lon: Double,
        date: LocalDate,
        zone: ZoneId,
        scale: Float = 3f,
        palette: MeeqatPalette = LightPalette,
        locationName: String = "",
    ): Bitmap {
        val bmp = Bitmap.createBitmap((W * scale).toInt(), (H * scale).toInt(), Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.scale(scale, scale)

        val n = date.lengthOfYear()
        val today = date.dayOfYear
        val latRad = Math.toRadians(lat)
        val lens = DoubleArray(n) { dayLength(it + 1, n, latRad) }
        val dev = DoubleArray(n) { (lens[it] - 12.0) * 60.0 }

        val maxPos = max(dev.maxOrNull() ?: 0.0, 0.0)
        val maxNeg = max(-(dev.minOrNull() ?: 0.0), 0.0)
        val kD = min(32.0 / max(maxPos + maxNeg, 1.0), 0.5)
        val k = kD.toFloat()
        val zero = (22 + maxPos * kD + (32 - (maxPos + maxNeg) * kD) / 2).toFloat() + GRAPH_TOP

        fun x(day: Int) = X0 + (day - 1f) / (n - 1f) * (X1 - X0)
        fun y(day: Int) = zero - dev[day - 1].toFloat() * k

        val pos = Path().apply { moveTo(x(1), zero) }
        val neg = Path().apply { moveTo(x(1), zero) }
        val curve = Path()
        for (d in 1..n) {
            val v = dev[d - 1].toFloat() * k
            pos.lineTo(x(d), zero - max(v, 0f))
            neg.lineTo(x(d), zero - min(v, 0f))
            if (d == 1) curve.moveTo(x(d), y(d)) else curve.lineTo(x(d), y(d))
        }
        pos.lineTo(X1, zero); pos.close()
        neg.lineTo(X1, zero); neg.close()

        val tx = x(today)
        val ty = y(today)

        val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = palette.ink.toArgb()
            textSize = 12f
            textAlign = Paint.Align.LEFT
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
        }
        val dateLabel = dateFmt.format(date)
        val datePaint = TextPaint(titlePaint).apply { textAlign = Paint.Align.RIGHT }
        val locationWidth = X1 - X0 - datePaint.measureText(dateLabel) - 12f
        val clippedName = TextUtils.ellipsize(
            locationName,
            titlePaint,
            locationWidth,
            TextUtils.TruncateAt.END,
        ).toString()
        c.drawText(clippedName, X0, 12f, titlePaint)
        c.drawText(dateLabel, X1, 12f, datePaint)

        c.drawPath(pos, fill(palette.dayFill.toArgb(), 0.28f))
        c.drawPath(neg, fill(palette.nightFill.toArgb(), 0.40f))
        c.save()
        c.clipRect(0f, 0f, tx, H)
        c.drawPath(pos, fill(palette.dayFill.toArgb(), 0.70f))
        c.drawPath(neg, fill(palette.nightFill.toArgb(), 0.95f))
        c.restore()

        c.drawLine(X0, zero, X1, zero, stroke(palette.muted.toArgb(), 1f, 0.75f, floatArrayOf(3f, 3f)))
        c.drawText("12h", X0, zero - 3, text(palette.muted.toArgb(), 8f, Paint.Align.LEFT))
        c.drawPath(curve, stroke(palette.chartAccent.toArgb(), 2f, 1f))

        c.drawLine(tx, ty, tx, H - 14, stroke(palette.sun.toArgb(), 1f, 0.35f, floatArrayOf(2f, 3f)))
        c.drawCircle(tx, ty, 8f, fill(palette.sun.toArgb(), 0.2f))
        c.drawCircle(tx, ty, 4.5f, fill(palette.sun.toArgb(), 1f))
        val exact = AstroCalculator.compute(date, lat, lon, zone).dayLength
        val daylight = exact ?: Duration.ofMinutes((lens[today - 1] * 60).roundToInt().toLong())
        val label = "${daylight.toHours()}h ${daylight.toMinutes() % 60}m"
        val lp = text(palette.ink.toArgb(), 12f, Paint.Align.CENTER).apply {
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        c.drawText(label, tx.coerceIn(22f, W - 22f), max(ty - 13, GRAPH_TOP + 11), lp)

        val mp = text(palette.muted.toArgb(), 9f, Paint.Align.CENTER)
        for (m in 1..12) {
            val first = LocalDate.of(date.year, m, 1).dayOfYear
            val mid = first + YearMonth.of(date.year, m).lengthOfMonth() / 2
            c.drawText(m.toString(), x(mid), H - 2, mp)
        }
        return bmp
    }

    // Spencer's declination and the official-sunrise horizon (-0.833 degrees).
    private fun dayLength(day: Int, daysInYear: Int, latRad: Double): Double {
        val g = 2 * PI * (day - 1) / daysInYear
        val decl = 0.006918 - 0.399912 * cos(g) + 0.070257 * sin(g) -
            0.006758 * cos(2 * g) + 0.000907 * sin(2 * g) -
            0.002697 * cos(3 * g) + 0.00148 * sin(3 * g)
        val cosH = (cos(Math.toRadians(90.833)) - sin(latRad) * sin(decl)) / (cos(latRad) * cos(decl))
        return 2 * Math.toDegrees(acos(cosH.coerceIn(-1.0, 1.0))) / 15.0
    }

    private fun fill(color: Int, alpha: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        this.alpha = (alpha * 255).roundToInt()
    }

    private fun stroke(color: Int, width: Float, alpha: Float, dash: FloatArray? = null) =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = width
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            this.color = color
            this.alpha = (alpha * 255).roundToInt()
            if (dash != null) pathEffect = DashPathEffect(dash, 0f)
        }

    private fun text(color: Int, size: Float, align: Paint.Align) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        textSize = size
        textAlign = align
    }
}
