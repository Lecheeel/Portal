import moe.microbios.nmea.NMEA
import moe.microbios.nmea.NmeaValue
import org.junit.Assert.*
import org.junit.Test

class NmeaGsvParsingTest {
    @Test fun finalCn0IsPreservedWithoutOptionalSignalId() {
        val gsv = NMEA.valueOf("\$GPGSV,1,1,01,12,30,120,45*00").value as NmeaValue.GSV
        assertEquals(45, gsv.satellites.single().snr)
        assertNull(gsv.infoId)
    }
    @Test fun optionalSignalIdDoesNotBecomeASatelliteField() {
        val gsv = NMEA.valueOf("\$GPGSV,1,1,01,12,30,120,,1*00").value as NmeaValue.GSV
        assertNull(gsv.satellites.single().snr)
        assertEquals("1", gsv.infoId)
    }
}
