package com.system.location.service.hook.scope

import org.junit.Assert.*
import org.junit.Test

class HookScopeResolverTest {
    private fun resolve(pkg: String, process: String = pkg, uid: Int = 1001, sdk: Int = 31) =
        HookScopeResolver.resolve(ProcessIdentity(pkg, process, uid, sdk, "test"))

    @Test fun everyExistingSystemBranchIsReachable() {
        assertEquals(HookScope.SYSTEM_SERVER, resolve("android", "system_server", 1000))
        assertEquals(HookScope.PHONE_PROCESS, resolve("com.android.phone"))
        assertEquals(HookScope.FUSED_PROCESS, resolve("com.android.location.fused"))
        assertEquals(HookScope.VENDOR_LOCATION_PROCESS, resolve("com.xiaomi.location.fused"))
        assertEquals(HookScope.VENDOR_LOCATION_PROCESS, resolve("com.oplus.location"))
    }

    @Test fun unrelatedProcessesAndSubstringMatchesAreRejected() {
        assertNull(resolve("android", uid = 10123))
        assertNull(resolve("com.android.phone.fake"))
        assertNull(resolve("com.android.phone", "com.android.phone:other"))
        assertNull(resolve("com.android.location.fused", sdk = 30))
        assertNull(resolve("com.oplus.location", uid = -1))
        assertNull(resolve("com.example.app"))
    }

    @Test fun targetAppsRequireExplicitExactOptIn() {
        val identity = ProcessIdentity("com.example.app", "com.example.app", 10123, 31, "test")
        assertEquals(HookScope.TARGET_APP, HookScopeResolver.resolve(identity, setOf(identity.packageName)))
        assertNull(HookScopeResolver.resolve(identity.copy(packageName = "com.example.app.fake"), setOf(identity.packageName)))
    }

    @Test fun installedIsNotTheSameAsMatched() {
        HookStatusRegistry.record("example", HookStatus(supported = true, installed = true))
        assertFalse(HookStatusRegistry.snapshot().getValue("example").matched)
        HookStatusRegistry.matched("example")
        assertTrue(HookStatusRegistry.snapshot().getValue("example").matched)
    }
}
