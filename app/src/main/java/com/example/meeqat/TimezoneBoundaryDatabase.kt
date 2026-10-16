package com.example.meeqat

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** Reads the TZF v1 M-profile (.tzm) boundary database without materializing its polygons. */
internal class TimezoneBoundaryDatabase private constructor(private val data: ByteBuffer) {
    private val timezoneNames: List<String>
    private val timezoneDirectory: Section
    private val polygonDirectory: Section
    private val ringDirectory: Section
    private val gridSection: Section
    private val pointsSection: Section
    private val timezoneCount: Int
    private val polygonCount: Int
    private val ringCount: Int
    private val gridLongitudeMin: Int
    private val gridLatitudeMin: Int
    private val gridLongitudeCells: Int
    private val gridLatitudeCells: Int
    private val gridCandidateCount: Int

    init {
        require(data.limit() >= HEADER_SIZE + 4) { "Timezone database is truncated" }
        require(data.getInt(0) == MAGIC) { "Timezone database has an invalid signature" }
        require(data.get(4).toInt() == 1 && data.get(48) == PROFILE_M) {
            "Unsupported timezone database format"
        }
        require(data.getShort(6).toInt() and 0xffff == HEADER_SIZE) { "Invalid timezone database header" }
        require(data.getInt(12) == COORDINATE_SCALE) { "Unsupported timezone coordinate scale" }
        require((data.getInt(16).toLong() and 0xffff_ffffL) == data.limit().toLong()) {
            "Timezone database size does not match its header"
        }
        timezoneCount = data.getInt(40)
        require(timezoneCount in 1..MAX_TIMEZONES) { "Invalid timezone count" }

        val expectedCrc = data.getInt(data.limit() - 4).toLong() and 0xffff_ffffL
        val crc = CRC32().apply {
            val bytes = ByteArray(min(32 * 1024, data.limit() - 4))
            var offset = 0
            while (offset < data.limit() - 4) {
                val length = min(bytes.size, data.limit() - 4 - offset)
                val slice = data.duplicate()
                slice.position(offset)
                slice.get(bytes, 0, length)
                update(bytes, 0, length)
                offset += length
            }
        }.value
        require(crc == expectedCrc) { "Timezone database checksum failed" }

        val sectionCount = data.getInt(20)
        require(sectionCount in 1..MAX_SECTIONS) { "Invalid timezone section count" }
        val sectionTableEnd = HEADER_SIZE + sectionCount * SECTION_ENTRY_SIZE
        require(sectionTableEnd <= data.limit() - 4) { "Timezone section table is truncated" }
        val sections = HashMap<Int, Section>(sectionCount)
        repeat(sectionCount) { index ->
            val entry = HEADER_SIZE + index * SECTION_ENTRY_SIZE
            val type = data.getInt(entry)
            val offset = data.getInt(entry + 4).toLong() and 0xffff_ffffL
            val length = data.getInt(entry + 8).toLong() and 0xffff_ffffL
            val end = offset + length
            require(offset >= sectionTableEnd && offset % 4L == 0L && end <= data.limit() - 4L) {
                "Invalid timezone section bounds"
            }
            require(sections.put(type, Section(offset.toInt(), length.toInt())) == null) {
                "Duplicate timezone section"
            }
        }

        val namesSection = sections.required(1)
        timezoneDirectory = sections.required(2)
        polygonDirectory = sections.required(3)
        ringDirectory = sections.required(13)
        gridSection = sections.required(8)
        pointsSection = sections.required(12)
        require(timezoneDirectory.length == timezoneCount * TZ_RECORD_SIZE && timezoneDirectory.length % TZ_RECORD_SIZE == 0) {
            "Invalid timezone directory"
        }
        require(polygonDirectory.length % POLYGON_RECORD_SIZE == 0) { "Invalid polygon directory" }
        require(ringDirectory.length % FLAT_RING_RECORD_SIZE == 0 && pointsSection.length % 8 == 0) {
            "Invalid timezone geometry sections"
        }
        polygonCount = polygonDirectory.length / POLYGON_RECORD_SIZE
        ringCount = ringDirectory.length / FLAT_RING_RECORD_SIZE

        val namesLength = data.getInt(namesSection.offset).toLong() and 0xffff_ffffL
        val namesBlobOffset = namesSection.offset.toLong() + 4L + 4L * (timezoneCount + 1)
        require(namesBlobOffset + namesLength == namesSection.offset.toLong() + namesSection.length) {
            "Invalid timezone names section"
        }
        timezoneNames = List(timezoneCount) { index ->
            val start = data.getInt(namesSection.offset + 4 + index * 4).toLong() and 0xffff_ffffL
            val end = data.getInt(namesSection.offset + 4 + (index + 1) * 4).toLong() and 0xffff_ffffL
            require(start < end && end <= namesLength) { "Invalid timezone name offset" }
            val nameBytes = ByteArray((end - start).toInt())
            val nameView = data.duplicate()
            nameView.position((namesBlobOffset + start).toInt())
            nameView.get(nameBytes)
            nameBytes.toString(Charsets.UTF_8)
        }

        require(gridSection.length >= GRID_HEADER_SIZE) { "Invalid timezone grid" }
        gridLongitudeMin = data.getShort(gridSection.offset).toInt()
        gridLatitudeMin = data.getShort(gridSection.offset + 2).toInt()
        gridLongitudeCells = data.getShort(gridSection.offset + 4).toInt() and 0xffff
        gridLatitudeCells = data.getShort(gridSection.offset + 6).toInt() and 0xffff
        gridCandidateCount = data.getInt(gridSection.offset + 8)
        require(gridLongitudeCells > 0 && gridLatitudeCells > 0 && gridCandidateCount >= 0) {
            "Invalid timezone grid dimensions"
        }
        val cellCount = gridLongitudeCells.toLong() * gridLatitudeCells
        require(GRID_HEADER_SIZE + cellCount * 4 + gridCandidateCount.toLong() * 2 == gridSection.length.toLong()) {
            "Invalid timezone grid data"
        }
    }

    fun findTimeZoneId(latitude: Double, longitude: Double): String? {
        if (!latitude.isFinite() || !longitude.isFinite() || latitude !in -90.0..90.0 || longitude !in -180.0..180.0) {
            return null
        }
        val column = floor(longitude).toInt() - gridLongitudeMin
        val row = floor(latitude).toInt() - gridLatitudeMin
        if (column !in 0 until gridLongitudeCells || row !in 0 until gridLatitudeCells) return null

        val gridData = gridSection.offset + GRID_HEADER_SIZE
        val cellIndex = row * gridLongitudeCells + column
        val cellWord = data.getInt(gridData + cellIndex * 4)
        val candidateCount = cellWord ushr 28
        val candidateOffset = cellWord and 0x0fff_ffff
        if (candidateCount == 0 || candidateOffset.toLong() + candidateCount > gridCandidateCount.toLong()) return null
        val candidateBase = gridData + gridLongitudeCells * gridLatitudeCells * 4

        // The TZF grid guarantees a single candidate covers the whole cell except near poles/dateline.
        if (candidateCount == 1 && longitude > -179.0 && longitude < 179.0 && latitude > -89.0 && latitude < 89.0) {
            return timezoneNames[candidateAt(candidateBase, candidateOffset)]
        }
        val x = longitude * COORDINATE_SCALE
        val y = latitude * COORDINATE_SCALE
        repeat(candidateCount) { index ->
            val timezoneIndex = candidateAt(candidateBase, candidateOffset + index)
            if (timezoneContains(timezoneIndex, x, y)) return timezoneNames[timezoneIndex]
        }
        return null
    }

    private fun candidateAt(base: Int, index: Int): Int {
        val value = data.getShort(base + index * 2).toInt() and 0xffff
        require(value < timezoneCount) { "Timezone grid candidate is out of range" }
        return value
    }

    private fun timezoneContains(index: Int, x: Double, y: Double): Boolean {
        val zoneRecord = timezoneDirectory.offset + index * TZ_RECORD_SIZE
        val firstPolygon = data.getInt(zoneRecord)
        val count = data.getShort(zoneRecord + 4).toInt() and 0xffff
        if (!bboxContains(zoneRecord + 8, x, y)) return false
        if (firstPolygon < 0 || firstPolygon.toLong() + count > polygonCount) return false
        for (polygonOffset in 0 until count) {
            val polygonRecord = polygonDirectory.offset + (firstPolygon + polygonOffset) * POLYGON_RECORD_SIZE
            if (!bboxContains(polygonRecord + 8, x, y)) continue
            val firstRing = data.getInt(polygonRecord)
            val ringTotal = data.getShort(polygonRecord + 4).toInt() and 0xffff
            if (firstRing < 0 || ringTotal == 0 || firstRing.toLong() + ringTotal > ringCount) continue
            if (!ringContains(firstRing, x, y, allowOnEdge = true)) continue
            var excludedByHole = false
            for (holeOffset in 1 until ringTotal) {
                val holeIndex = firstRing + holeOffset
                val holeRecord = ringDirectory.offset + holeIndex * FLAT_RING_RECORD_SIZE
                if (bboxContains(holeRecord + 8, x, y) && ringContains(holeIndex, x, y, allowOnEdge = false)) {
                    excludedByHole = true
                    break
                }
            }
            if (!excludedByHole) return true
        }
        return false
    }

    private fun ringContains(index: Int, x: Double, y: Double, allowOnEdge: Boolean): Boolean {
        val ringRecord = ringDirectory.offset + index * FLAT_RING_RECORD_SIZE
        if (!bboxContains(ringRecord + 8, x, y)) return false
        val first = data.getInt(ringRecord).toLong()
        val count = data.getInt(ringRecord + 4).toLong()
        if (first < 0 || count < 3 || first + count > pointsSection.length / 8L) return false
        var inside = false
        var previousX = pointX(first + count - 1)
        var previousY = pointY(first + count - 1)
        for (pointIndex in first until first + count) {
            val currentX = pointX(pointIndex)
            val currentY = pointY(pointIndex)
            if (isOnSegment(x, y, previousX, previousY, currentX, currentY)) return allowOnEdge
            if ((currentY > y) != (previousY > y)) {
                val crossingX = (previousX - currentX) * (y - currentY) / (previousY - currentY) + currentX
                if (x < crossingX) inside = !inside
            }
            previousX = currentX
            previousY = currentY
        }
        return inside
    }

    private fun pointX(index: Long): Double = data.getInt(pointsSection.offset + (index * 8).toInt()).toDouble()

    private fun pointY(index: Long): Double = data.getInt(pointsSection.offset + (index * 8 + 4).toInt()).toDouble()

    private fun bboxContains(offset: Int, x: Double, y: Double): Boolean {
        val minX = data.getInt(offset).toDouble()
        val minY = data.getInt(offset + 4).toDouble()
        val maxX = data.getInt(offset + 8).toDouble()
        val maxY = data.getInt(offset + 12).toDouble()
        return x in minX..maxX && y in minY..maxY
    }

    private fun isOnSegment(x: Double, y: Double, ax: Double, ay: Double, bx: Double, by: Double): Boolean {
        val cross = (x - ax) * (by - ay) - (y - ay) * (bx - ax)
        if (abs(cross) > EDGE_EPSILON) return false
        return x >= min(ax, bx) - EDGE_EPSILON && x <= max(ax, bx) + EDGE_EPSILON &&
            y >= min(ay, by) - EDGE_EPSILON && y <= max(ay, by) + EDGE_EPSILON
    }

    private data class Section(val offset: Int, val length: Int)

    private fun Map<Int, Section>.required(type: Int): Section = getValue(type)

    companion object {
        private const val HEADER_SIZE = 64
        private const val SECTION_ENTRY_SIZE = 16
        private const val MAX_SECTIONS = 14
        private const val MAX_TIMEZONES = 65_535
        private const val MAGIC = 0x4246_5a54
        private const val PROFILE_M: Byte = 1
        private const val COORDINATE_SCALE = 100_000
        private const val TZ_RECORD_SIZE = 24
        private const val POLYGON_RECORD_SIZE = 24
        private const val FLAT_RING_RECORD_SIZE = 24
        private const val GRID_HEADER_SIZE = 12
        private const val EDGE_EPSILON = 1e-7

        fun open(bytes: ByteArray): TimezoneBoundaryDatabase =
            TimezoneBoundaryDatabase(ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN))
    }
}


