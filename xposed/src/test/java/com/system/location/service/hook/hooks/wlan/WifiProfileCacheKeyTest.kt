package com.system.location.service.hook.hooks.wlan

import com.system.location.service.core.environment.EnvironmentSourceMode
import com.system.location.service.core.geo.Wgs84
import org.junit.Assert.assertNotEquals
import org.junit.Test

class WifiProfileCacheKeyTest {
    private val key = WifiProfileCacheKey(
        point = Wgs84(31.2, 121.5),
        seed = 7,
        apCount = 4,
        environmentVersion = 2,
        sourceMode = EnvironmentSourceMode.AUTO,
        fingerprintEnabled = false,
        fingerprintRevision = 0,
    )

    @Test fun profileIdentityAndSourceChangesInvalidateCacheKey() {
        assertNotEquals(key, key.copy(point = Wgs84(31.21, 121.5)))
        assertNotEquals(key, key.copy(seed = 8))
        assertNotEquals(key, key.copy(apCount = 5))
        assertNotEquals(key, key.copy(environmentVersion = 3))
        assertNotEquals(key, key.copy(sourceMode = EnvironmentSourceMode.PROCEDURAL))
        assertNotEquals(key, key.copy(fingerprintEnabled = true))
        assertNotEquals(key, key.copy(fingerprintRevision = 1))
    }
}
