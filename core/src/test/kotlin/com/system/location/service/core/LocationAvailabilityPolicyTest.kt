package com.system.location.service.core

import com.system.location.service.core.privacy.LocationAvailabilityPolicy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocationAvailabilityPolicyTest {
    @Test fun synthesizesOnlyForThirdPartyWhileEnabled() {
        assertTrue(LocationAvailabilityPolicy.shouldSynthesize(true, 10_500))
        assertFalse(LocationAvailabilityPolicy.shouldSynthesize(false, 10_500))
    }

    @Test fun keepsSystemAndSettingsReal() {
        assertFalse(LocationAvailabilityPolicy.shouldSynthesize(true, 1000))
        assertFalse(LocationAvailabilityPolicy.shouldSynthesize(true, 9_999))
    }
}
