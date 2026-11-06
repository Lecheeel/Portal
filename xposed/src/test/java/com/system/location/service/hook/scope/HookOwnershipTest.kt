package com.system.location.service.hook.scope

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HookOwnershipTest {

    @Test
    fun reportsMissingSystemServerPoints() {
        val report = HookOwnership.audit(
            HookScope.SYSTEM_SERVER,
            mapOf("system-location" to HookStatus(supported = true, installed = true, matched = true)),
        )

        assertEquals(listOf("system-location"), report.recorded)
        assertTrue(report.missing.contains("wifi"))
        assertTrue(report.missing.contains("fused"))
        assertEquals(1, report.installed)
        assertEquals(1, report.matched)
        assertFalse(report.healthy)
    }

    @Test
    fun completeSystemServerScopeIsHealthy() {
        val statuses = HookOwnership.expectedFor(HookScope.SYSTEM_SERVER).associateWith {
            HookStatus(supported = true, installed = true, matched = true)
        }

        val report = HookOwnership.audit(HookScope.SYSTEM_SERVER, statuses)

        assertTrue(report.missing.isEmpty())
        assertEquals(report.expected.size, report.installed)
        assertEquals(report.expected.size, report.matched)
        assertEquals(0, report.failed)
        assertTrue(report.healthy)
    }

    @Test
    fun failedExpectedHookBreaksHealth() {
        val statuses = HookOwnership.expectedFor(HookScope.SYSTEM_SERVER).associateWith {
            HookStatus(supported = true, installed = true)
        } + ("wifi" to HookStatus(supported = true, installed = true, failed = true, reason = "VerifyError"))

        val report = HookOwnership.audit(HookScope.SYSTEM_SERVER, statuses)

        assertTrue(report.missing.isEmpty())
        assertEquals(1, report.failed)
        assertFalse(report.healthy)
    }

    @Test
    fun phoneScopeOnlyExpectsPhoneHook() {
        val report = HookOwnership.audit(HookScope.PHONE_PROCESS, emptyMap())

        assertEquals(listOf("phone"), report.expected)
        assertEquals(listOf("phone"), report.missing)
        assertFalse(report.healthy)
    }

    @Test
    fun targetAppOwnsNoHooksByDesign() {
        val report = HookOwnership.audit(HookScope.TARGET_APP, emptyMap())

        assertTrue(report.expected.isEmpty())
        assertTrue(report.missing.isEmpty())
        assertTrue(report.healthy)
    }
}
