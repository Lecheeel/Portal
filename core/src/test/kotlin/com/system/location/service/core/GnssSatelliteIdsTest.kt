package com.system.location.service.core

import com.system.location.service.core.gnss.*
import org.junit.Assert.*
import org.junit.Test

class GnssSatelliteIdsTest {
    @Test fun knownReceiverNumberingMapsToNmeaTalkerLocalIdentities() {
        assertEquals(65, GnssSatelliteIds.nmeaId(Constellation.GLONASS, 1))
        assertEquals(1, GnssSatelliteIds.nmeaId(Constellation.QZSS, 193))
        assertEquals(33, GnssSatelliteIds.nmeaId(Constellation.SBAS, 120))
        assertEquals(7, GnssSatelliteIds.nmeaId(Constellation.IRNSS, 7))
        assertNull(GnssSatelliteIds.nmeaId(Constellation.GLONASS, 100))
        assertNull(GnssSatelliteIds.nmeaId(Constellation.SBAS, 183))
        assertNull(GnssSatelliteIds.nmeaId(Constellation.QZSS, 1))
    }
}
