package com.system.location.service.core

import com.system.location.service.core.privacy.XposedMetadataVisibilityPolicy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class XposedMetadataVisibilityPolicyTest {
    @Test fun hidesOnlyOwnPackageFromThirdParties() {
        assertTrue(XposedMetadataVisibilityPolicy.shouldHide("com.system.location.service", 10123))
        assertFalse(XposedMetadataVisibilityPolicy.shouldHide("com.system.location.service", 1000))
        assertFalse(XposedMetadataVisibilityPolicy.shouldHide("com.system.location.service", 0))
        assertFalse(XposedMetadataVisibilityPolicy.shouldHide("com.example.other", 10123))
    }

    @Test fun includesLegacyMetadataKeys() {
        assertTrue(XposedMetadataVisibilityPolicy.HIDDEN_KEYS.contains("xposedmodule"))
        assertTrue(XposedMetadataVisibilityPolicy.HIDDEN_KEYS.contains("xposedscope"))
    }
}
