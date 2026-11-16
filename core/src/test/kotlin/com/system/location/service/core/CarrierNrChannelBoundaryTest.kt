package com.system.location.service.core

import com.system.location.service.core.carrier.CarrierNrChannel
import org.junit.Assert.*
import org.junit.Test

class CarrierNrChannelBoundaryTest {
    @Test fun downlinkRangesDoNotAcceptUplinkOrOutOfBandNumbers() {
        assertTrue(CarrierNrChannel.valid(26, 171800))
        assertTrue(CarrierNrChannel.valid(26, 178800))
        assertFalse(CarrierNrChannel.valid(26, 165000))
        assertTrue(CarrierNrChannel.valid(30, 472000))
        assertFalse(CarrierNrChannel.valid(30, 475000))
        assertTrue(CarrierNrChannel.valid(20, 164200))
        assertFalse(CarrierNrChannel.valid(48, 636666))
        assertTrue(CarrierNrChannel.valid(48, 636667))
    }
    @Test fun fddBandsRequire100KhzRasterNotJustFrequencyWithinRange() {
        for (band in listOf(1, 2, 3, 5, 7, 8, 12, 13, 14, 20, 25, 26, 28, 30, 66, 71)) {
            val channel = CarrierNrChannel.defaultArfcn(band)
            assertTrue("n$band default", CarrierNrChannel.valid(band, channel))
            assertFalse("n$band off raster", CarrierNrChannel.valid(band, channel + 1))
        }
        for (band in CarrierNrChannel.bands) {
            assertTrue("n$band default must be usable", CarrierNrChannel.valid(band, CarrierNrChannel.defaultArfcn(band)))
        }
    }
}
