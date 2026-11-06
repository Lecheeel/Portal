import moe.microbios.nmea.NMEA
import moe.microbios.nmea.NmeaConstellation
import moe.microbios.nmea.NmeaFix
import moe.microbios.nmea.NmeaGenerator
import moe.microbios.nmea.NmeaSatellite
import moe.microbios.nmea.NmeaValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class NmeaGeneratorTest {
    private val satellites = buildList {
        repeat(10) { index ->
            add(NmeaSatellite(NmeaConstellation.GPS, index + 1, 20f + index * 5, 40f + index * 30, 30f + index, index < 6))
        }
        repeat(4) { index ->
            add(NmeaSatellite(NmeaConstellation.BEIDOU, index + 1, 25f + index * 6, 60f + index * 40, 28f + index, index < 3))
        }
        repeat(3) { index ->
            add(NmeaSatellite(NmeaConstellation.GLONASS, index + 1, 18f + index * 7, 200f + index * 35, 25f + index, index < 2))
        }
        repeat(2) { index ->
            add(NmeaSatellite(NmeaConstellation.GALILEO, index + 1, 35f + index * 5, 300f + index * 20, 33f + index, true))
        }
    }

    private val fix = NmeaFix(
        latitude = 31.2304,
        longitude = 121.4737,
        altitudeMeters = 42.5,
        geoidSeparationMeters = -8.4,
        speedMps = 5.5,
        bearingDeg = 87.2,
        timeMillis = 1_790_000_000_000,
        satellites = satellites,
        hdop = 0.9,
        vdop = 1.5,
        pdop = 1.8,
    )

    private fun parsed(sentence: String): NmeaValue = NMEA.valueOf(sentence.trim()).value

    private fun supportedValues(): List<NmeaValue> = NmeaGenerator.generate(fix)
        .filter { line ->
            val id = line.trim().removePrefix("$").substringBefore(',').substring(2)
            id in setOf("GGA", "RMC", "GSA", "GSV")
        }
        .map(::parsed)

    @Test
    fun everySentenceHasAValidChecksum() {
        val lines = NmeaGenerator.generate(fix)
        assertTrue(lines.size > 5)
        lines.forEach { line ->
            assertTrue(line.startsWith("$"))
            assertTrue(line.endsWith("\r\n"))
            val body = line.trim().removePrefix("$").substringBefore('*')
            val checksum = line.trim().substringAfter('*')
            assertEquals(NMEA.calculateNmeaChecksum("\$$body"), checksum)
        }
    }

    @Test
    fun ggaAndRmcCarryTheSameFix() {
        val values = supportedValues()
        val gga = values.filterIsInstance<NmeaValue.GGA>().single()
        assertEquals(1, gga.fixQuality)
        assertEquals(satellites.size, gga.satellites)
        assertEquals(42.5, gga.antennaAltitude ?: Double.NaN, 0.1)
        assertTrue(abs((gga.latitude ?: 0.0) - 3113.8240) < 0.01)
        assertTrue(abs((gga.longitude ?: 0.0) - 12128.4220) < 0.01)

        val rmc = values.filterIsInstance<NmeaValue.RMC>().single()
        assertEquals("A", rmc.status)
        assertTrue((rmc.speedKnots ?: 0.0) > 10.0)
        assertEquals(87.2, rmc.trackAngle ?: Double.NaN, 0.1)
    }

    @Test
    fun gsvTotalMatchesVisibleSatellitesAndIsGroupedByFour() {
        val gsv = supportedValues().filterIsInstance<NmeaValue.GSV>()
        val gps = gsv.filter { it.statementId == "GSV" && it.totalSatellitesInView == 10 }
        assertEquals(3, gps.size)
        assertEquals(listOf(1, 2, 3), gps.map { it.messageNumber })
        assertEquals(10, gps.sumOf { it.satellites.size })
        assertEquals(10, gps.first().totalSatellitesInView)

        val total = gsv.map { it.totalSatellitesInView }.distinct().sum()
        assertEquals(satellites.size, total)
        assertTrue(gsv.any { it.satellites.size == 2 })
    }

    @Test
    fun gsaUsesOnlyUsedInFixSatellites() {
        val gsa = supportedValues().filterIsInstance<NmeaValue.GSA>()
        assertTrue(gsa.isNotEmpty())
        gsa.forEach { sentence ->
            assertEquals("A", sentence.mode)
            assertTrue(sentence.prn.filterNotNull().isNotEmpty())
        }
        val gps = gsa.first { it.prn.count { prn -> prn != null } == 6 }
        assertEquals(3, gps.fixStatus)
    }

    @Test
    fun invalidFixIsRejected() {
        assertThrows { fix.copy(latitude = 91.0) }
        assertThrows { fix.copy(speedMps = -1.0) }
        assertThrows { fix.copy(satellites = emptyList()) }
        assertThrows { NmeaSatellite(NmeaConstellation.GPS, 1, 91f, 0f, 30f, true) }
    }

    private fun assertThrows(block: () -> Unit) {
        try {
            block()
        } catch (_: IllegalArgumentException) {
            return
        }
        throw AssertionError("Expected IllegalArgumentException")
    }
}
