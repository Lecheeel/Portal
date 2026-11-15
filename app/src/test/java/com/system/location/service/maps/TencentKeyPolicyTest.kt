package com.system.location.service.maps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class TencentKeyPolicyTest {
    @Test fun blankKeyClearsTheCustomKey() {
        assertNull(TencentKeyPolicy.normalize("  "))
    }

    @Test fun normalizesWhitespaceAndAllowedCharacters() {
        assertEquals("Abcd-1234_xy", TencentKeyPolicy.normalize(" Abcd-1234_xy "))
    }

    @Test fun rejectsInvalidLengthAndCharacters() {
        assertThrows(IllegalArgumentException::class.java) { TencentKeyPolicy.normalize("short") }
        assertThrows(IllegalArgumentException::class.java) { TencentKeyPolicy.normalize("valid.key-1") }
        assertThrows(IllegalArgumentException::class.java) { TencentKeyPolicy.normalize("x".repeat(129)) }
    }
}
