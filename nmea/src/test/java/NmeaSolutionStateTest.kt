import moe.microbios.nmea.*
import org.junit.Assert.*
import org.junit.Test

class NmeaSolutionStateTest {
    private fun fix() = NmeaFix(31.999999999, 121.999999999, 10.0, speedMps = 0.0, bearingDeg = 0.0,
        timeMillis = 1790000000000, satellites = (1..16).map {
            NmeaSatellite(NmeaConstellation.GPS, it, 25f, it * 20f, 35f, true)
        }, hdop = 1.1, vdop = 1.7, pdop = 2.0)
    private fun fields(line: String) = line.substringBefore('*').removePrefix("$").split(',')

    @Test fun invalidSolutionDoesNotClaimAFixOrFiniteDop() {
        val lines = NmeaGenerator.generate(fix().copy(hdop = null, vdop = null, pdop = null))
        val gga = fields(lines.first { it.startsWith("\$GPGGA") })
        assertEquals("0", gga[6])
        assertEquals("00", gga[7])
        assertEquals("", gga[8])
        val rmc = fields(lines.first { it.startsWith("\$GPRMC") })
        assertEquals("V", rmc[2])
        val gsa = fields(lines.first { it.startsWith("\$GPGSA") })
        assertEquals("1", gsa[2])
        assertTrue(gsa.subList(3, 18).all { it.isEmpty() })
    }

    @Test fun gsaListsAllUsedIdentitiesAndUsesSolutionDop() {
        val lines = NmeaGenerator.generate(fix()).filter { it.startsWith("\$GPGSA") }.map(::fields)
        assertEquals(2, lines.size)
        assertEquals((1..16).map { it.toString() }, lines.flatMap { it.subList(3, 15) }.filter { it.isNotEmpty() })
        assertTrue(lines.all { it.subList(15, 18) == listOf("2.0", "1.1", "1.7") })
    }

    @Test fun coordinatesCarryRoundedMinutesAndUnknownGeoidStaysEmpty() {
        val gga = fields(NmeaGenerator.generate(fix()).first())
        assertEquals("3200.0000", gga[2])
        assertEquals("12200.0000", gga[4])
        assertEquals("", gga[9])
        assertEquals("", gga[11])
    }

    @Test fun zeroVisibleSatellitesStillProducesANoFixEpoch() {
        val lines = NmeaGenerator.generate(fix().copy(satellites = emptyList(), hdop = null, vdop = null, pdop = null))
        assertTrue(lines.first().startsWith("\$GNGGA"))
        assertEquals("0", fields(lines.first())[6])
    }
}
