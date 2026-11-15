package com.system.location.service.hook.hooks.wlan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SyntheticWifiIdentityTest {
    @Test
    fun syntheticMacIsStableAndLocallyAdministered() {
        val first = syntheticWifiMac(42L, "A4:50:46:12:34:56")

        assertEquals(first, syntheticWifiMac(42L, "a4:50:46:12:34:56"))
        assertEquals(6, first.split(':').size)
        assertEquals("02", first.substringBefore(':'))
    }

    @Test
    fun syntheticIpIsStableAndUsesPrivateAddressSpace() {
        val first = syntheticWifiIpv4("A4:50:46:12:34:56")
        val bytes = (0..3).map { (first ushr (it * 8)) and 0xff }

        assertEquals(first, syntheticWifiIpv4("a4:50:46:12:34:56"))
        assertEquals(10, bytes[0])
        assertTrue(bytes[1] in 64..127)
        assertTrue(bytes[3] in 2..127)
    }
}
