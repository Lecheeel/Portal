package com.system.location.service.data.persistence

import com.system.location.service.core.runtime.HookConfiguration
import com.system.location.service.core.runtime.PreparedRuntimeConfiguration
import com.system.location.service.core.runtime.RuntimeSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class HookConfigurationDisclosureTest {
    @Test fun hideMockDefaultsToTrueAndCanBeDisabledForABTesting() {
        val defaults = HookConfigurationPreferences.read(emptyMap<String, Any>(), RuntimeSnapshot())
        assertEquals(true, defaults["hide_mock"])
        assertEquals(
            true,
            PreparedRuntimeConfiguration.prepare(defaults).applyTo(RuntimeSnapshot()).configuration.hideMock,
        )

        val off = HookConfigurationPreferences.read(mapOf("hideMock" to false), RuntimeSnapshot())
        assertEquals(false, off["hide_mock"])
        assertEquals(
            false,
            PreparedRuntimeConfiguration.prepare(off).applyTo(RuntimeSnapshot()).configuration.hideMock,
        )
    }

    @Test fun experimentalAntiDetectionDefaultsOffAndIsWireControlled() {
        val defaults = HookConfigurationPreferences.read(emptyMap<String, Any>(), RuntimeSnapshot())
        assertEquals(false, defaults["experimental_anti_detect"])
        assertEquals(false, defaults["experimental_stack_trace"])
        val configuration =
            PreparedRuntimeConfiguration.prepare(defaults).applyTo(RuntimeSnapshot()).configuration
        assertEquals(false, configuration.antiDetectEnabled)
        assertEquals(false, configuration.stackTraceCleanEnabled)

        val enabled = HookConfigurationPreferences.read(
            mapOf("antiDetectEnabled" to true, "stackTraceCleanEnabled" to true),
            RuntimeSnapshot(),
        )
        val enabledConfiguration =
            PreparedRuntimeConfiguration.prepare(enabled).applyTo(RuntimeSnapshot()).configuration
        assertEquals(true, enabledConfiguration.antiDetectEnabled)
        assertEquals(true, enabledConfiguration.stackTraceCleanEnabled)
    }

    @Test fun networkLocationIsNotRelabelledByDefault() {
        assertFalse(HookConfiguration().disableNetworkLocation)
    }
}
