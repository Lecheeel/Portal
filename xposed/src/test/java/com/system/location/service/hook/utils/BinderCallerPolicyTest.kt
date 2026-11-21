package com.system.location.service.hook.utils

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BinderCallerPolicyTest {
    @Test fun systemAppIdsArePreservedAcrossAndroidUsers() {
        for (uid in listOf(-1, 0, 1000, 1001, 101000, 101001, 99901000)) {
            assertTrue("uid=$uid", BinderUtils.systemCallForPackages(uid, listOf("example.app")))
        }
        for (uid in listOf(10000, 10225, 110225)) {
            assertFalse("uid=$uid", BinderUtils.systemCallForPackages(uid, listOf("example.app")))
        }
    }

    @Test fun aFusedPackageDoesNotOverrideASystemPackageSharingItsUid() {
        assertFalse(BinderUtils.systemCallForPackages(10225, listOf("com.android.location.fused")))
        assertTrue(BinderUtils.systemCallForPackages(10225,
            listOf("com.android.location.fused", "com.android.providers.settings")))
        assertTrue(BinderUtils.systemCallForPackages(10225,
            listOf("com.android.providers.settings", "com.android.location.fused")))
    }

    @Test fun unknownCallersAndTheAndroidPackageKeepSystemBehavior() {
        assertTrue(BinderUtils.systemCallForPackages(10225, null))
        assertTrue(BinderUtils.systemCallForPackages(10225, emptyList()))
        assertTrue(BinderUtils.isSystemPackages("android"))
        assertTrue(BinderUtils.isSystemPackages("com.oplus.wifi"))
        assertFalse(BinderUtils.isSystemPackages("example.com.android.reader"))
        assertFalse(BinderUtils.isSystemPackages("com.androidish.reader"))
    }
}
