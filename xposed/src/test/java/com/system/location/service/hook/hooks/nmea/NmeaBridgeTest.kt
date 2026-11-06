package com.system.location.service.hook.hooks.nmea

import com.system.location.service.hook.utils.FakeLoc
import java.util.concurrent.ConcurrentHashMap
import org.junit.Assert.assertEquals
import org.junit.Test

class NmeaBridgeTest {
    @Test fun disabledSimulationPassesIncomingThrough() {
        val enabled = FakeLoc.enable
        val nmea = FakeLoc.enableNMEA
        try {
            FakeLoc.enable = false
            FakeLoc.enableNMEA = true
            val incoming = "\$GPGGA,120000.00,3113.8240,N,12128.4220,E,1,12,0.9,42.5,M,-8.4,M,,*4E"
            assertEquals(incoming, NmeaBridge.rewrite(incoming, ConcurrentHashMap()))
        } finally {
            FakeLoc.enable = enabled
            FakeLoc.enableNMEA = nmea
        }
    }

    @Test fun disabledNmeaSuppressesRealSentences() {
        val enabled = FakeLoc.enable
        val nmea = FakeLoc.enableNMEA
        try {
            FakeLoc.enable = true
            FakeLoc.enableNMEA = false
            val incoming = "\$GPGGA,120000.00,3113.8240,N,12128.4220,E,1,12,0.9,42.5,M,-8.4,M,,*4E"
            assertEquals("", NmeaBridge.rewrite(incoming, ConcurrentHashMap()))
        } finally {
            FakeLoc.enable = enabled
            FakeLoc.enableNMEA = nmea
        }
    }
}
