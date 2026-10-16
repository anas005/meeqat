package com.anas.meeqat

import com.example.meeqat.TimezoneBoundaryDatabase
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class TimezoneBoundaryDatabaseTest {
    private fun database(): TimezoneBoundaryDatabase = TimezoneBoundaryDatabase.open(
        File("src/main/assets/timezone-boundaries.tzm").readBytes(),
    )

    @Test
    fun resolvesCitiesIndependentlyOfDeviceTimezone() {
        val boundaries = database()
        assertEquals("America/Los_Angeles", boundaries.findTimeZoneId(37.3861, -122.0839))
        assertEquals("Europe/London", boundaries.findTimeZoneId(51.5072, -0.1276))
        assertEquals("Asia/Kolkata", boundaries.findTimeZoneId(13.0827, 80.2707))
        assertEquals("Australia/Sydney", boundaries.findTimeZoneId(-33.8688, 151.2093))
    }

    @Test
    fun mountainViewDaylightUsesTheResolvedLocalDateAndHasPositiveLength() {
        val date = LocalDate.of(2026, 10, 15)
        val zone = ZoneId.of(database().findTimeZoneId(37.3861, -122.0839)!!)
        val result = com.example.meeqat.AstroCalculator.compute(date, 37.3861, -122.0839, zone)

        assertEquals(date, result.sunrise?.toLocalDate())
        assertEquals(date, result.magrib?.toLocalDate())
        assertTrue("Sunset must follow sunrise", result.dayLength?.let { !it.isNegative && !it.isZero } == true)
    }

    @Test
    fun rejectsCoordinatesOutsideTheGlobeAndCorruptDatabaseBytes() {
        val boundaries = database()
        assertNull(boundaries.findTimeZoneId(91.0, 0.0))
        val damaged = File("src/main/assets/timezone-boundaries.tzm").readBytes().also { it[100] = (it[100].toInt() xor 1).toByte() }
        assertThrows(IllegalArgumentException::class.java) { TimezoneBoundaryDatabase.open(damaged) }
    }
}


