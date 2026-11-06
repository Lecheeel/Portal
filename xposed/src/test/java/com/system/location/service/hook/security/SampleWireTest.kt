package com.system.location.service.hook.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SampleWireTest {
    @Test
    fun acceptsV1AndV2Only() {
        assertFalse(SampleWire.isCompatible(0))
        assertTrue(SampleWire.isCompatible(1))
        assertTrue(SampleWire.isCompatible(2))
        assertFalse(SampleWire.isCompatible(3))
        assertEquals(2, SampleWire.VERSION)
        assertEquals(1, SampleWire.MIN_SUPPORTED)
    }

    @Test
    fun metadataDefaultsAreEmpty() {
        val metadata = SampleWire.Metadata()
        assertEquals(null, metadata.provider)
        assertEquals(0, metadata.fieldsMask)
        assertEquals(0L, metadata.environmentVersion)
        assertEquals(0L, metadata.configRevision)
    }
}
