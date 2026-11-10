package moe.microbios.nmea

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong

enum class NmeaConstellation(val talker: String, val systemId: Int) {
    GPS("GP", 1),
    GLONASS("GL", 2),
    GALILEO("GA", 3),
    BEIDOU("BD", 4),
    QZSS("GQ", 5),
    IRNSS("GI", 6),
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
        require(azimuthDeg >= 0f && azimuthDeg < 360f) { "azimuth out of range: $azimuthDeg" }
        require(cn0DbHz in 0f..99f) { "CN0 out of range: $cn0DbHz" }
    }
}

data class NmeaFix(
    val latitude: Double,
    val longitude: Double,
    val altitudeMeters: Double,
    val geoidSeparationMeters: Double? = null,
    val speedMps: Double,
    val bearingDeg: Double,
    val timeMillis: Long,
    val satellites: List<NmeaSatellite>,
    val hdop: Double?,
    val vdop: Double?,
    val pdop: Double?,
) {
    init {
        require(latitude in -90.0..90.0) { "latitude out of range" }
        require(longitude in -180.0..180.0) { "longitude out of range" }
        require(altitudeMeters.isFinite() && (geoidSeparationMeters == null || geoidSeparationMeters.isFinite())) { "invalid altitude" }
        require(speedMps.isFinite() && speedMps >= 0.0) { "invalid speed" }
        require(bearingDeg.isFinite() && bearingDeg in 0.0..360.0) { "invalid bearing" }
        require(timeMillis >= 0L) { "timeMillis must be non-negative" }
        require(satellites.map { it.constellation to it.prn }.distinct().size == satellites.size) { "duplicate NMEA satellite" }
        val dop = listOf(hdop, vdop, pdop)
        require(dop.all { it == null } || dop.all { it != null && it.isFinite() && it > 0 }) { "DOP must be finite positive or all unavailable" }
    }

    val hasFix: Boolean get() = hdop != null && satellites.count { it.usedInFix } >= 4
    val talker: String get() = if (satellites.map { it.constellation }.distinct().size == 1) satellites.first().constellation.talker else "GN"
}

/**
 * Single deterministic NMEA generator used by the GNSS hook (P3-03).
 *
 * Sentences are produced from one [NmeaFix]; GGA/RMC/GSA/GSV share the same satellite list and UTC
 * timestamp. GGA/GSA describe the used subset; GSV describes all visible satellites.
 * Unknown solution DOP and geoid separation remain empty. GST needs measured error statistics
 * (DOP alone is insufficient) and is therefore not synthesized.
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
            lines += gsa(fix, constellation, satellites)
            lines += gsv(constellation, satellites)
        }
        if (fix.satellites.isEmpty()) lines += gsa(fix, NmeaConstellation.GPS, emptyList())
        lines += zda(fix.talker, zoned, utc)
        return lines
    }

    private fun gga(fix: NmeaFix, utc: String): String {
        val (lat, ns) = latitude(fix.latitude)
        val (lon, ew) = longitude(fix.longitude)
        val quality = if (fix.hasFix) 1 else 0
        val used = if (fix.hasFix) fix.satellites.count { it.usedInFix } else 0
        val altitude = if (fix.hasFix && fix.geoidSeparationMeters != null) format1(fix.altitudeMeters - fix.geoidSeparationMeters) else ""
        val payload = "${fix.talker}GGA,$utc,$lat,$ns,$lon,$ew,$quality," +
            "${used.toString().padStart(2, '0')},${if (fix.hasFix) format1(fix.hdop) else ""}," +
            "$altitude,M,${format1(fix.geoidSeparationMeters)},M,,"
        return sentence(payload)
    }

    private fun rmc(fix: NmeaFix, utc: String, date: String): String {
        val (lat, ns) = latitude(fix.latitude)
        val (lon, ew) = longitude(fix.longitude)
        val knots = fix.speedMps * MPS_TO_KNOTS
        val status = if (fix.hasFix) "A" else "V"
        val mode = if (fix.hasFix) "A" else "N"
        val payload = "${fix.talker}RMC,$utc,$status,$lat,$ns,$lon,$ew,${format2(knots)},${format1(fix.bearingDeg)}," +
            "$date,,,$mode"
        return sentence(payload)
    }

    private fun gsa(fix: NmeaFix, constellation: NmeaConstellation, satellites: List<NmeaSatellite>): List<String> {
        val used = if (fix.hasFix) satellites.filter { it.usedInFix } else emptyList()
        val chunks = used.chunked(MAX_GSA_PRNS).ifEmpty { listOf(emptyList()) }
        return chunks.map { chunk ->
            val prns = chunk.map { it.prn.toString() } + List(MAX_GSA_PRNS - chunk.size) { "" }
            val dop = if (fix.hasFix) listOf(fix.pdop, fix.hdop, fix.vdop).joinToString(",", transform = ::format1) else ",,"
            sentence("${fix.talker}GSA,A,${if (fix.hasFix) 3 else 1},${prns.joinToString(",")},$dop,${constellation.systemId}")
        }
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
                "${satellites.size.toString().padStart(2, '0')},$satelliteFields"
            sentence(payload)
        }
    }

    private fun zda(talker: String, zoned: java.time.ZonedDateTime, utc: String): String {
        val payload = "${talker}ZDA,$utc,${DAY.format(zoned)},${MONTH.format(zoned)},${YEAR.format(zoned)},00,00"
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
        val ticks = (value * 60 * 10_000).roundToLong()
        val degrees = ticks / 600_000
        val minutes = (ticks % 600_000) / 10_000.0
        return String.format(Locale.US, "%0${degreeDigits}d%07.4f", degrees, minutes)
    }

    private fun format1(value: Double?): String = value?.let { String.format(Locale.US, "%.1f", it) }.orEmpty()

    private fun format2(value: Double): String = String.format(Locale.US, "%.2f", value)

    private const val MPS_TO_KNOTS = 1.9438444924406
    private const val MAX_GSA_PRNS = 12
    private const val SATELLITES_PER_GSV = 4
}
