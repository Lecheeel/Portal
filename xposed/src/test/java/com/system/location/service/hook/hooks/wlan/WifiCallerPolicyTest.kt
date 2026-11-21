package com.system.location.service.hook.hooks.wlan

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WifiCallerPolicyTest {
    @Test fun frameworkAndRadioKeepPhysicalWifiRegardlessOfPackageName() {
        for (uid in listOf(-1, 0, 1000, 1001, 101000, 101001, 99901000)) {
            assertFalse("uid=$uid", WifiCallerPolicy.mayRewrite("example.client", uid))
        }
        assertFalse(WifiCallerPolicy.mayRewrite("android", 10225))
    }

    @Test fun applicationsAcrossUsersRemainEligibleButVendorServicesDoNot() {
        for (uid in listOf(10225, 110225)) {
            assertTrue(WifiCallerPolicy.mayRewrite("example.client", uid))
            assertFalse(WifiCallerPolicy.mayRewrite("com.oplus.wifi", uid))
            assertFalse(WifiCallerPolicy.mayRewrite("com.android.settings", uid))
        }
    }
}
