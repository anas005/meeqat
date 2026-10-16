package com.example.meeqat

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.tan

/** Offline sun and moon times. Accuracy is about 1 to 2 minutes for the sun and 2 to 4 minutes for the moon. */
object AstroCalculator {

    data class Result(
        val fajr: ZonedDateTime?,
        val sunrise: ZonedDateTime?,
        val magrib: ZonedDateTime?,
        val dayLength: Duration?,
        val midNight: ZonedDateTime?,
        val lastThird: ZonedDateTime?,
        val moonrise: ZonedDateTime?,
        val moonset: ZonedDateTime?,
    )

    // Zenith angles: 90.833 = official sunrise/sunset, 108 = astronomical twilight (true dawn, Fajr).
    private const val OFFICIAL = 90.833
    private const val ASTRONOMICAL = 108.0
    private const val MOON_HORIZON_DEG = 0.125

    fun compute(date: LocalDate, lat: Double, lon: Double, zone: ZoneId): Result {
        val sunrise = sunEvent(date, lat, lon, zone, OFFICIAL, true)
        val sunset = sunEvent(date, lat, lon, zone, OFFICIAL, false)
        val fajr = sunEvent(date, lat, lon, zone, ASTRONOMICAL, true)
        val fajrNext = sunEvent(date.plusDays(1), lat, lon, zone, ASTRONOMICAL, true)

        val dayLength = if (sunrise != null && sunset != null) Duration.between(sunrise, sunset) else null

        // Night = tonight's sunset to tomorrow's Fajr.
        var mid: ZonedDateTime? = null
        var third: ZonedDateTime? = null
        if (sunset != null && fajrNext != null) {
            val night = Duration.between(sunset, fajrNext)
            mid = sunset.plus(night.dividedBy(2))
            third = sunset.plus(night.multipliedBy(2).dividedBy(3))
        }

        val (rise, set) = moonEvents(date, lat, lon, zone)
        return Result(fajr, sunrise, sunset, dayLength, mid, third, rise, set)
    }

    // Almanac for Computers (1990) sunrise algorithm. Longitude is east-positive.
    private fun sunEvent(
        date: LocalDate, lat: Double, lon: Double, zone: ZoneId, zenith: Double, rising: Boolean,
    ): ZonedDateTime? {
        val lngHour = lon / 15.0
        val t = date.dayOfYear + ((if (rising) 6.0 else 18.0) - lngHour) / 24.0
        val m = 0.9856 * t - 3.289
        val l = norm360(m + 1.916 * sinD(m) + 0.020 * sinD(2 * m) + 282.634)

        var ra = norm360(Math.toDegrees(atan(0.91764 * tanD(l))))
        ra += floor(l / 90) * 90 - floor(ra / 90) * 90
        ra /= 15.0

        val sinDec = 0.39782 * sinD(l)
        val cosDec = cos(asin(sinDec))
        val cosH = (cosD(zenith) - sinDec * sinD(lat)) / (cosDec * cosD(lat))
        if (cosH > 1.0 || cosH < -1.0) return null // sun never reaches this angle today

        val hDeg = Math.toDegrees(acos(cosH))
        val h = (if (rising) 360.0 - hDeg else hDeg) / 15.0

        val localMean = h + ra - 0.06571 * t - 6.622
        var ut = (localMean - lngHour) % 24.0
        if (ut < 0) ut += 24.0

        var instant = date.atStartOfDay(ZoneOffset.UTC).toInstant().plusMillis((ut * 3_600_000).toLong())
        // Keep the result on the requested local date.
        val local = instant.atZone(zone).toLocalDate()
        if (local.isAfter(date)) instant = instant.minus(1, ChronoUnit.DAYS)
        else if (local.isBefore(date)) instant = instant.plus(1, ChronoUnit.DAYS)
        return instant.atZone(zone)
    }

    // Scan the local day in 10-minute steps for the moon crossing the horizon.
    private fun moonEvents(date: LocalDate, lat: Double, lon: Double, zone: ZoneId): Pair<ZonedDateTime?, ZonedDateTime?> {
        val start = date.atStartOfDay(zone).toInstant()
        val end = date.plusDays(1).atStartOfDay(zone).toInstant()
        val stepMs = 10 * 60_000L
        var rise: Instant? = null
        var set: Instant? = null

        var prevT = start
        var prev = Lunar.altitude(Lunar.jd(start), lat, lon) - MOON_HORIZON_DEG
        var t = start.plusMillis(stepMs)
        while (!t.isAfter(end)) {
            val cur = Lunar.altitude(Lunar.jd(t), lat, lon) - MOON_HORIZON_DEG
            val crossing = { prevT.plusMillis((stepMs * (prev / (prev - cur))).toLong()) }
            if (prev < 0 && cur >= 0 && rise == null) rise = crossing()
            if (prev >= 0 && cur < 0 && set == null) set = crossing()
            prevT = t
            prev = cur
            t = t.plusMillis(stepMs)
        }
        return Pair(rise?.atZone(zone), set?.atZone(zone))
    }

    private fun norm360(x: Double): Double = ((x % 360.0) + 360.0) % 360.0
    private fun sinD(x: Double) = sin(Math.toRadians(x))
    private fun cosD(x: Double) = cos(Math.toRadians(x))
    private fun tanD(x: Double) = tan(Math.toRadians(x))
}
