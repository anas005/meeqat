package com.example.meeqat

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.chrono.HijrahDate
import java.time.temporal.ChronoField
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/** Low-precision lunar maths (Astronomical Almanac style), shared by moon times, moon age and the phase image. */
object Lunar {

    data class Moon(val lonDeg: Double, val raDeg: Double, val decDeg: Double)

    fun jd(instant: Instant): Double = instant.toEpochMilli() / 86_400_000.0 + 2_440_587.5

    private fun rad(d: Double) = Math.toRadians(d)
    private fun norm360(x: Double) = ((x % 360.0) + 360.0) % 360.0

    fun moon(jd: Double): Moon {
        val t = (jd - 2_451_545.0) / 36_525.0
        fun s(a: Double, b: Double) = sin(rad(a + b * t))
        val lon = 218.32 + 481267.881 * t +
            6.29 * s(135.0, 477198.87) - 1.27 * s(259.3, -413335.36) +
            0.66 * s(235.7, 890534.22) + 0.21 * s(269.9, 954397.74) -
            0.19 * s(357.5, 35999.05) - 0.11 * s(186.5, 966404.03)
        val lat = 5.13 * s(93.3, 483202.02) + 0.28 * s(228.2, 960400.89) -
            0.28 * s(318.3, 6003.15) - 0.17 * s(217.6, -407332.21)
        val eps = 23.439 - 0.0130 * t
        val l = cos(rad(lat)) * cos(rad(lon))
        val m = cos(rad(eps)) * cos(rad(lat)) * sin(rad(lon)) - sin(rad(eps)) * sin(rad(lat))
        val n = sin(rad(eps)) * cos(rad(lat)) * sin(rad(lon)) + cos(rad(eps)) * sin(rad(lat))
        return Moon(norm360(lon), norm360(Math.toDegrees(atan2(m, l))), Math.toDegrees(asin(n)))
    }

    /** Geocentric altitude of the moon's centre in degrees. */
    fun altitude(jd: Double, latDeg: Double, lonDeg: Double): Double {
        val mo = moon(jd)
        val gmst = 280.46061837 + 360.98564736629 * (jd - 2_451_545.0)
        val h = rad(gmst + lonDeg - mo.raDeg)
        val sinAlt = sin(rad(latDeg)) * sin(rad(mo.decDeg)) +
            cos(rad(latDeg)) * cos(rad(mo.decDeg)) * cos(h)
        return Math.toDegrees(asin(sinAlt))
    }

    private fun sunLongitude(jd: Double): Double {
        val d = jd - 2_451_545.0
        val l = 280.460 + 0.9856474 * d
        val g = rad(357.528 + 0.9856003 * d)
        return norm360(l + 1.915 * sin(g) + 0.020 * sin(2 * g))
    }

    /** Moon minus sun ecliptic longitude, 0..360. 0 = new moon, 180 = full moon. */
    fun elongation(instant: Instant): Double {
        val j = jd(instant)
        return norm360(moon(j).lonDeg - sunLongitude(j))
    }

    /** Days since the last new moon. */
    fun ageDays(now: Instant): Double {
        val perDayDeg = 12.19
        var t = now.minusMillis((elongation(now) / perDayDeg * 86_400_000).toLong())
        repeat(4) {
            var e = elongation(t)
            if (e > 180.0) e -= 360.0
            t = t.minusMillis((e / perDayDeg * 86_400_000).toLong())
        }
        return Duration.between(t, now).toMillis() / 86_400_000.0
    }
}

object HijriCalendar {
    /** Manual correction in days (-1, 0 or +1) to match local moon sighting. */
    const val OFFSET_DAYS = 0L

    private val months = listOf(
        "Muharram", "Safar", "Rabi I", "Rabi II", "Jumada I", "Jumada II",
        "Rajab", "Shaban", "Ramadan", "Shawwal", "Dhul Qadah", "Dhul Hijjah",
    )

    /** Returns ("28 Rabi II", "1448") using the Umm al-Qura calendar. */
    fun format(date: LocalDate): Pair<String, String> {
        val h = HijrahDate.from(date.plusDays(OFFSET_DAYS))
        val day = h.get(ChronoField.DAY_OF_MONTH)
        val month = months[h.get(ChronoField.MONTH_OF_YEAR) - 1]
        return Pair("$day $month", h.get(ChronoField.YEAR).toString())
    }
}
