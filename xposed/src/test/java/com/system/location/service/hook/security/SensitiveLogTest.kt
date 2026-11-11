package com.system.location.service.hook.security

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SensitiveLogTest {
    @Test fun locationAndConfigValuesAreRedacted() {
        val line = SensitiveLog.describe("update_location", mapOf(
            "lat" to "31.2304",
            "lon" to "121.4737",
            "sample_altitude" to "80.0",
            "environment_json" to "{\"wifi\":1}",
            "routing_json" to "{\"mode\":\"PER_APP\"}",
            "key" to "secret",
            "runtime_transfer" to "full-private-state",
            "gnss_navigation" to "compressed-navigation-payload",
            "proxy_session" to "private-session",
            "mode" to "=",
        ))
        assertFalse(line.contains("31.2304"))
        assertFalse(line.contains("121.4737"))
        assertFalse(line.contains("{\"wifi\":1}"))
        assertFalse(line.contains("secret"))
        assertFalse(line.contains("full-private-state"))
        assertFalse(line.contains("compressed-navigation-payload"))
        assertFalse(line.contains("private-session"))
        assertTrue(line.contains("mode=="))
        assertTrue(line.contains("<redacted>"))
    }

    @Test fun nonSensitiveKeysRemain() {
        val line = SensitiveLog.describe("move", mapOf("n" to "10.0", "bearing" to "90.0"))
        assertTrue(line.contains("n=10.0"))
        assertTrue(line.contains("bearing=90.0"))
    }

    @Test fun suffixMatchingCoversSampleFields() {
        assertTrue(SensitiveLog.isSensitive("sample_latitude"))
        assertTrue(SensitiveLog.isSensitive("some_longitude"))
        assertFalse(SensitiveLog.isSensitive("speed"))
        assertFalse(SensitiveLog.isSensitive("accuracy"))
    }
}
