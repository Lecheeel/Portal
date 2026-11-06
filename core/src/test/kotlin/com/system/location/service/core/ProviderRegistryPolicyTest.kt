package com.system.location.service.core

import com.system.location.service.core.providers.ProviderRegistryPolicy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderRegistryPolicyTest {
    @Test fun hidesPortalAndTestProviders() {
        assertTrue(ProviderRegistryPolicy.isHidden("fused_ext"))
        assertTrue(ProviderRegistryPolicy.isHidden("com.portal.test"))
        assertTrue(ProviderRegistryPolicy.isHidden("mock_provider"))
        assertTrue(ProviderRegistryPolicy.isHidden("PORTAL"))
    }

    @Test fun keepsRealProviders() {
        assertFalse(ProviderRegistryPolicy.isHidden("gps"))
        assertFalse(ProviderRegistryPolicy.isHidden("network"))
        assertFalse(ProviderRegistryPolicy.isHidden("fused"))
        assertFalse(ProviderRegistryPolicy.isHidden("passive"))
    }
}
