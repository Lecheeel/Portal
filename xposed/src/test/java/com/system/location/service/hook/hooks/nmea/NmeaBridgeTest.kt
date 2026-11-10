package com.system.location.service.hook.hooks.nmea

import com.system.location.service.core.geo.Wgs84
import com.system.location.service.core.gnss.*
import com.system.location.service.hook.hooks.gnss.GnssReceiverRuntime
import org.junit.Assert.*
import org.junit.Test

class NmeaBridgeTest {
    private fun publication() = PublishedGnssFrame(1, ReceiverGnssFrame(1790000000000,
        Wgs84(31.0, 121.0), 10.0, listOf(
            GnssSatellite(Constellation.GPS, 1, 30f, 10f, 35f, false, true, true),
            GnssSatellite(Constellation.SBAS, 120, 25f, 50f, 32f, false, true, true),
            GnssSatellite(Constellation.GLONASS, 1, 20f, 80f, 30f, false, true, true),
            GnssSatellite(Constellation.QZSS, 193, 40f, 120f, 40f, false, true, true),
            GnssSatellite(Constellation.IRNSS, 3, 20f, 180f, 30f, false, true, true)),
        GnssGeometryResult(null, "test no solution"), ReceiverGnssSource.STATISTICAL_REPLAY), 0.0, 0.0)
    private fun fields(line: String) = line.substringBefore('*').removePrefix("$").split(',')

    @Test fun noCaptureDoesNotInventSentences() {
        GnssReceiverRuntime.configure(null)
        assertTrue(NmeaBridge.generatedSentences().isEmpty())
    }

    @Test fun allSupportedIdentitiesSurviveNmeaMappingAndNoFixIsExplicit() {
        val lines = NmeaBridge.generatedSentences(publication())
        val gsv = lines.filter { it.substringBefore(',').endsWith("GSV") }.map(::fields)
        assertEquals(5, gsv.sumOf { (it.size - 4) / 4 })
        assertEquals("65", gsv.first { it[0] == "GLGSV" }[4])
        assertEquals("01", gsv.first { it[0] == "GQGSV" }[4])
        assertEquals("03", gsv.first { it[0] == "GIGSV" }[4])
        assertEquals("33", gsv.first { it[0] == "GPGSV" }[8])
        val gga = fields(lines.first())
        assertEquals("0", gga[6])
        assertEquals("00", gga[7])
        assertEquals("", gga[8])
    }

    @Test fun publicationTimestampAndObserverDetermineOutputWithoutGlobalState() {
        val first = publication()
        assertEquals(NmeaBridge.generatedSentences(first), NmeaBridge.generatedSentences(first))
        val point = first.copy(frame = first.frame.copy(observer = Wgs84(-20.0, -60.0)))
        val gga = fields(NmeaBridge.generatedSentences(point).first())
        assertEquals("S", gga[3])
        assertEquals("W", gga[5])
        assertEquals(fields(NmeaBridge.generatedSentences(first).first())[1], gga[1])
    }
}
