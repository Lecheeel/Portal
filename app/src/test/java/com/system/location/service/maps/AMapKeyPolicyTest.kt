package com.system.location.service.maps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream

class AMapKeyPolicyTest {
    @Test fun boundedReadAcceptsLimitAndRejectsOversizedInput() {
        val bytes = ByteArray(1024) { it.toByte() }
        assertArrayEquals(bytes, AMapKeyPolicy.readBounded(ByteArrayInputStream(bytes), bytes.size))
        assertThrows(IllegalArgumentException::class.java) {
            AMapKeyPolicy.readBounded(ByteArrayInputStream(bytes), bytes.size - 1)
        }
    }

    @Test fun invalidStoredKeyFallsBackToBundledKeyWithoutLosingValidCustomKey() {
        assertEquals("bundled", AMapKeyPolicy.effectiveKey(true, { error("keystore unavailable") }, { "bundled" }))
        assertEquals("bundled", AMapKeyPolicy.effectiveKey(true, { null }, { "bundled" }))
        assertEquals("custom", AMapKeyPolicy.effectiveKey(true, { "custom" }, { "bundled" }))
        assertEquals("bundled", AMapKeyPolicy.effectiveKey(false, { "ignored" }, { "bundled" }))
        assertEquals("", AMapKeyPolicy.effectiveKey(false, { "ignored" }, { "" }))
    }

    @Test fun emptyValueSelectsBundledKeyAndNonEmptyValueIsTrimmed() {
        assertNull(AMapKeyPolicy.normalize("  "))
        assertEquals("Abcdef12-xy_Z", AMapKeyPolicy.normalize(" Abcdef12-xy_Z "))
    }

    @Test fun invalidKeyCharactersAndLengthsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { AMapKeyPolicy.normalize("short") }
        assertThrows(IllegalArgumentException::class.java) { AMapKeyPolicy.normalize("valid.key-1") }
        assertThrows(IllegalArgumentException::class.java) { AMapKeyPolicy.normalize("x".repeat(257)) }
    }

    @Test fun signerSha1MatchesStandardFingerprintFormatting() {
        assertEquals("A9:99:3E:36:47:06:81:6A:BA:3E:25:71:78:50:C2:6C:9C:D0:D8:9D",
            AMapKeyPolicy.signerSha1(listOf("abc".toByteArray())))
    }

    @Test fun signerSha1IsStableForMultipleSignersAndRejectsMissingCertificates() {
        val first = "86:F7:E4:37:FA:A5:A7:FC:E1:5D:1D:DC:B9:EA:EA:EA:37:76:67:B8"
        val second = "A9:99:3E:36:47:06:81:6A:BA:3E:25:71:78:50:C2:6C:9C:D0:D8:9D"
        assertEquals("$first\n$second", AMapKeyPolicy.signerSha1(listOf("abc".toByteArray(), "a".toByteArray())))
        assertEquals("$first\n$second", AMapKeyPolicy.signerSha1(listOf("a".toByteArray(), "abc".toByteArray())))
        assertThrows(IllegalArgumentException::class.java) { AMapKeyPolicy.signerSha1(emptyList()) }
    }
}
