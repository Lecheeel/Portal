package moe.microbios.nmea

import org.junit.Assert.*
import org.junit.Test

class NmeaRegressionTest {
    @Test fun existingCapturedSentencesRoundTrip() {
        data.forEach { sentence -> assertEquals(sentence, NMEA.valueOf(sentence).toNmeaString()) }
    }
    @Test fun checksumMatchesKnownFixAndMissingPrefixIsRejected() {
        assertEquals("47", NMEA.calculateNmeaChecksum("\$GPGGA,123519,4807.038,N,01131.000,E,1,08,0.9,545.4,M,46.9,M,,"))
        assertThrows(IllegalArgumentException::class.java) { NMEA.valueOf("GPGGA,invalid") }
    }
}
