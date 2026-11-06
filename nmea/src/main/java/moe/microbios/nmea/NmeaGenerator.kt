package moe.microbios.nmea

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

enum class NmeaConstellation(val talker: String, val systemId: Int) {
    GPS("GP", 1),
    GLONASS("GL", 2),
    GALILEO("GA", 3),
    BEIDOU("BD", 4),
}

data class NmeaSatellite(
    val constellation: NmeaConstellation,
    val prn: Int,
    val elevationDeg: Float,
    val azimuthDeg: Float,
    val cn0DbHz: Float,
    val usedInFix: Boolean,
) {
    init {
        require(prn in 1..999) { "prn out of range: $prn" }
        require(elevationDeg in 0f..90f) { "elevation out of range: $elevationDeg" }
        require(azimuthDeg in 0f..360f) { "azimuth out of range: $azimuthDeg" }
        require(cn0DbHz in 0f..99f) { "CN0 out of range: $cn0DbHz" }
    }
}

data class NmeaFix(
    val latitude: Double,
    val longitude: Double,
    val altitudeMeters: Double,
    val geoidSeparationMeters: Double = 0.0,
    val speedMps: Double,
    val bearingDeg: Double,
    val timeMillis: Long,
    val satellites: List<NmeaSatellite>,
    val hdop: Double,
    val vdop: Double,
    val pdop: Double,
) {
    init {
        require(latitude in -90.0..90.0) { "latitude out of range" }
        require(longitude in -180.0..180.0) { "longitude out of range" }
        require(altitudeMeters.isFinite() && geoidSeparationMeters.isFinite()) { "invalid altitude" }
        require(speedMps.isFinite() && speedMps >= 0.0) { "invalid speed" }
        require(bearingDeg.isFinite() && bearingDeg in 0.0..360.0) { "invalid bearing" }
        require(timeMillis >= 0L) { "timeMillis must be non-negative" }
        require(satellites.isNotEmpty()) { "at least one satellite is required" }
        require(hdop > 0.0 && vdop > 0.0 && pdop > 0.0) { "DOP values must be positive" }
    }
}

/**
 * Single deterministic NMEA generator used by the GNSS hook (P3-03).
 *
 * Sentences are produced from one [NmeaFix]; GGA/RMC/GSA/GSV share the same satellite list and UTC
 * timestamp. GGA's satellite count equals the number of visible satellites in GSV, keeping the
 * detector's cross-check self-consistent; GSA carries the used-in-fix subset.
 */
object NmeaGenerator {
    private val TIME = DateTimeFormatter.ofPattern("HHmmss.SS", Locale.US)
    private val DATE = DateTimeFormatter.ofPattern("ddMMyy", Locale.US)
    private val DAY = DateTimeFormatter.ofPattern("dd", Locale.US)
    private val MONTH = DateTimeFormatter.ofPattern("MM", Locale.US)
    private val YEAR = DateTimeFormatter.ofPattern("yyyy", Locale.US)

    fun generate(fix: NmeaFix): List<String> {
        val zoned = Instant.ofEpochMilli(fix.timeMillis).atZone(ZoneOffset.UTC)
        val utc = TIME.format(zoned)
        val date = DATE.format(zoned)
        val lines = mutableListOf<String>()

        lines += gga(fix, utc)
        lines += rmc(fix, utc, date)
        fix.satellites.groupBy { it.constellation }.toSortedMap(compareBy { it.ordinal }).forEach { (constellation, satellites) ->
            lines += gsa(constellation, satellites)
            lines += gsv(constellation, satellites)
        }
        lines += gst(fix, utc)
        lines += zda(zoned, utc)
        return lines
    }

    private fun gga(fix: NmeaFix, utc: String): String {
        val (lat, ns) = latitude(fix.latitude)
        val (lon, ew) = longitude(fix.longitude)
        val quality = if (fix.satellites.any { it.usedInFix }) 1 else 0
        val payload = "GPGGA,$utc,$lat,$ns,$lon,$ew,$quality," +
            "${fix.satellites.size.toString().padStart(2, '0')},${format1(fix.hdop)}," +
            "${format1(fix.altitudeMeters)},M,${format1(fix.geoidSeparationMeters)},M,,"
        return sentence(payload)
    }

    private fun rmc(fix: NmeaFix, utc: String, date: String): String {
        val (lat, ns) = latitude(fix.latitude)
        val (lon, ew) = longitude(fix.longitude)
        val knots = fix.speedMps * MPS_TO_KNOTS
        val payload = "GPRMC,$utc,A,$lat,$ns,$lon,$ew,${format2(knots)},${format1(fix.bearingDeg)}," +
            "$date,,,A"
        return sentence(payload)
    }

    private fun gsa(constellation: NmeaConstellation, satellites: List<NmeaSatellite>): String {
        val used = satellites.filter { it.usedInFix }.take(MAX_GSA_PRNS)
        val fixStatus = if (used.size >= MIN_3D_FIX_SATELLITES) 3 else 1
        val prns = buildList {
            used.forEach { add(it.prn.toString()) }
            while (size < MAX_GSA_PRNS) add("")
        }
        val payload = "${constellation.talker}GSA,A,$fixStatus,${prns.joinToString(",")},1.8,0.9,1.5,${constellation.systemId}"
        return sentence(payload)
    }

    private fun gsv(constellation: NmeaConstellation, satellites: List<NmeaSatellite>): List<String> {
        val chunks = satellites.chunked(SATELLITES_PER_GSV)
        if (chunks.isEmpty()) return emptyList()
        return chunks.mapIndexed { index, chunk ->
            val satelliteFields = chunk.joinToString(",") { satellite ->
                "${satellite.prn.toString().padStart(2, '0')}," +
                    "${satellite.elevationDeg.toInt().toString().padStart(2, '0')}," +
                    "${satellite.azimuthDeg.toInt().toString().padStart(3, '0')}," +
                    satellite.cn0DbHz.toInt().toString().padStart(2, '0')
            }
            val payload = "${constellation.talker}GSV,${chunks.size},${index + 1}," +
                "${satellites.size.toString().padStart(2, '0')},$satelliteFields,${constellation.systemId}"
            sentence(payload)
        }
    }

    private fun gst(fix: NmeaFix, utc: String): String {
        val horizontal = fix.hdop * DOP_TO_METERS
        val vertical = fix.vdop * DOP_TO_METERS
        val payload = "GPGST,$utc,0.5,0.4,0.3,45.0," +
            "${format1(horizontal)},${format1(horizontal * 1.2)},${format1(vertical)}"
        return sentence(payload)
    }

    private fun zda(zoned: java.time.ZonedDateTime, utc: String): String {
        val payload = "GPZDA,$utc,${DAY.format(zoned)},${MONTH.format(zoned)},${YEAR.format(zoned)},00,00"
        return sentence(payload)
    }

    private fun sentence(payload: String): String {
        val checksum = NMEA.calculateNmeaChecksum("\$$payload")
        return "\$$payload*$checksum\r\n"
    }

    private fun latitude(value: Double): Pair<String, String> {
        val hemisphere = if (value >= 0) "N" else "S"
        return formatCoordinate(abs(value), 2) to hemisphere
    }

    private fun longitude(value: Double): Pair<String, String> {
        val hemisphere = if (value >= 0) "E" else "W"
        return formatCoordinate(abs(value), 3) to hemisphere
    }

    private fun formatCoordinate(value: Double, degreeDigits: Int): String {
        val degrees = value.toInt()
        val minutes = (value - degrees) * 60.0
        return String.format(Locale.US, "%0${degreeDigits}d%07.4f", degrees, minutes)
    }

    private fun format1(value: Double): String = String.format(Locale.US, "%.1f", value)

    private fun format2(value: Double): String = String.format(Locale.US, "%.2f", value)

    private const val MPS_TO_KNOTS = 1.9438444924406
    private const val MAX_GSA_PRNS = 12
    private const val MIN_3D_FIX_SATELLITES = 4
    private const val SATELLITES_PER_GSV = 4
    private const val DOP_TO_METERS = 2.5
}
