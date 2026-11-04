package com.system.location.service.privacy

import org.junit.Assert.*
import org.junit.Test

class SdkConsentControllerTest {
    private class Store(var state: SdkConsent = SdkConsent(), var writable: Boolean = true) : SdkConsentStore {
        override fun read() = state
        override fun write(value: SdkConsent) { check(writable) { "Storage unavailable" }; state = value }
    }
    private class Sdk : ConsentSdk {
        var mapsStarts = 0
        val crashChanges = mutableListOf<Boolean>()
        var failCrashStart = false
        var failMaps = false
        var failCrashStop = false
        override fun initializeMaps() { mapsStarts++; check(!failMaps) { "Maps unavailable" } }
        override fun setCrashReporting(enabled: Boolean) {
            crashChanges += enabled
            check(!enabled || !failCrashStart) { "Crash SDK unavailable" }
            check(enabled || !failCrashStop) { "Crash SDK stop failed" }
        }
    }
    @Test fun freshInstallDoesNotInitializeThirdPartySdks() {
        val sdk = Sdk()
        SdkConsentController(Store(), sdk).restore()
        assertEquals(0, sdk.mapsStarts)
        assertTrue(sdk.crashChanges.isEmpty())
    }
    @Test fun acceptingMapsDoesNotOptIntoCrashReporting() {
        val store = Store(); val sdk = Sdk(); val controller = SdkConsentController(store, sdk)
        controller.acceptMaps()
        assertEquals(SdkConsent(mapsAccepted = true), store.state)
        assertEquals(1, sdk.mapsStarts)
        assertTrue(sdk.crashChanges.isEmpty())
    }
    @Test fun withdrawalNeverRequiresMapConsent() {
        val store = Store(SdkConsent(false, true)); val sdk = Sdk()
        val controller = SdkConsentController(store, sdk)
        controller.restore()
        controller.setCrashReporting(false)
        assertFalse(store.state.crashReporting)
        assertEquals(0, sdk.mapsStarts)
    }

    @Test fun crashReportingRequiresMapConsent() {
        val store = Store(); val sdk = Sdk()
        try { SdkConsentController(store, sdk).setCrashReporting(true); fail("Consent required") }
        catch (_: IllegalStateException) { }
        assertFalse(store.state.crashReporting)
        assertTrue(sdk.crashChanges.isEmpty())
    }
    @Test fun optInAndOptOutApplyImmediatelyAndSurviveRestart() {
        val store = Store(SdkConsent(mapsAccepted = true)); val sdk = Sdk()
        val controller = SdkConsentController(store, sdk)
        controller.restore()
        controller.setCrashReporting(true)
        controller.setCrashReporting(true)
        controller.setCrashReporting(false)
        assertEquals(listOf(true, false), sdk.crashChanges)
        val restarted = Sdk()
        SdkConsentController(store, restarted).restore()
        assertEquals(1, restarted.mapsStarts)
        assertTrue(restarted.crashChanges.isEmpty())
    }
    @Test fun restoredOptInStartsCrashSdkOnlyOnce() {
        val sdk = Sdk(); val controller = SdkConsentController(Store(SdkConsent(true, true)), sdk)
        controller.restore(); controller.restore()
        assertEquals(1, sdk.mapsStarts)
        assertEquals(listOf(true), sdk.crashChanges)
    }
    @Test fun failedMapInitializationDuringOptInCannotLeaveReportingRequested() {
        val store = Store(SdkConsent(true)); val sdk = Sdk().apply { failMaps = true }
        val controller = SdkConsentController(store, sdk)
        controller.restore()
        assertTrue(runCatching { controller.setCrashReporting(true) }.isFailure)
        assertFalse(controller.crashReportingRequested)
        assertFalse(store.state.crashReporting)
        assertTrue(sdk.crashChanges.isEmpty())
    }

    @Test fun sdkStopFailureStillSavesWithdrawalAndCannotShowEnabled() {
        val store = Store(SdkConsent(true, true))
        val sdk = Sdk(); val controller = SdkConsentController(store, sdk)
        controller.restore()
        sdk.failCrashStop = true
        val result = runCatching { controller.setCrashReporting(false) }
        assertTrue(result.isFailure)
        assertFalse(controller.crashReportingRequested)
        assertFalse(controller.crashReportingEnabled)
        assertFalse(store.state.crashReporting)
        sdk.failCrashStop = false
        controller.setCrashReporting(false)
        assertEquals("A failed stop must be retried", listOf(true, false, false), sdk.crashChanges)
    }

    @Test fun failedMapRestoreDoesNotClaimSdkReadyOrStartCrashSdk() {
        val sdk = Sdk().apply { failMaps = true }
        val controller = SdkConsentController(Store(SdkConsent(true, true)), sdk)
        controller.restore()
        assertTrue(controller.mapsAccepted)
        assertFalse(controller.mapsReady)
        assertFalse(controller.crashReportingEnabled)
        assertNotNull(controller.lastFailure)
        assertTrue(sdk.crashChanges.isEmpty())
    }

    @Test fun restoreFailureAndFailedCleanupSaveKeepEffectiveReportingOff() {
        val sdk = Sdk().apply { failCrashStart = true }
        val store = Store(SdkConsent(true, true), writable = false)
        val controller = SdkConsentController(store, sdk)
        controller.restore()
        assertFalse(controller.crashReportingEnabled)
        assertFalse(controller.crashReportingRequested)
        assertNotNull(controller.lastFailure)
    }

    @Test fun failedEnableWriteRollsBackVisibleStateWithoutStartingCrashSdk() {
        val store = object : SdkConsentStore {
            var state = SdkConsent(true)
            override fun read() = state
            override fun write(value: SdkConsent) { state = value; error("Disk full") }
        }
        val sdk = Sdk(); val controller = SdkConsentController(store, sdk)
        controller.restore()
        runCatching { controller.setCrashReporting(true) }
        assertFalse(controller.crashReportingRequested)
        assertFalse(controller.crashReportingEnabled)
        assertFalse(store.read().crashReporting)
        assertTrue(sdk.crashChanges.isEmpty())
    }

    @Test fun failedRollbackCannotGrantConsentThroughStoreMemory() {
        val sdk = Sdk()
        val store = object : SdkConsentStore {
            var state = SdkConsent()
            override fun read() = state
            override fun write(value: SdkConsent) { state = value; error("Disk unavailable") }
        }
        val controller = SdkConsentController(store, sdk)
        runCatching { controller.acceptMaps() }
        // Even a subsequent unrelated SDK request must use confirmed consent.
        runCatching { controller.setCrashReporting(true) }
        assertEquals(0, sdk.mapsStarts)
        assertTrue(sdk.crashChanges.isEmpty())
    }

    @Test fun failedCrashStartCannotBeRetriedAfterSdkShutdown() {
        val sdk = Sdk().apply { failCrashStart = true }
        val controller = SdkConsentController(Store(SdkConsent(true)), sdk)
        controller.restore()
        runCatching { controller.setCrashReporting(true) }
        sdk.failCrashStart = false
        controller.setCrashReporting(true)
        assertEquals(listOf(true, false), sdk.crashChanges)
    }

    @Test fun unreadablePreferencesDoNotAbortApplicationRestore() {
        val sdk = Sdk()
        val store = object : SdkConsentStore {
            override fun read(): SdkConsent = error("Unreadable preferences")
            override fun write(value: SdkConsent) = error("Unreadable preferences")
        }
        SdkConsentController(store, sdk).restore()
        assertEquals(0, sdk.mapsStarts)
        assertTrue(sdk.crashChanges.isEmpty())
    }

    @Test fun closedCrashSdkIsNotRestartedUntilNextProcess() {
        val store = Store(SdkConsent(true, true)); val sdk = Sdk()
        val controller = SdkConsentController(store, sdk)
        controller.restore()
        controller.setCrashReporting(false)
        controller.setCrashReporting(true)
        controller.restore()
        assertFalse(controller.crashReportingEnabled)
        assertTrue(controller.crashReportingPendingRestart)
        assertTrue(controller.crashReportingRequested)
        assertEquals("closeBugly shuts down its executor permanently", listOf(true, false), sdk.crashChanges)
        assertTrue("New opt-in is saved for next process", store.state.crashReporting)
        val nextProcess = Sdk()
        SdkConsentController(store, nextProcess).restore()
        assertEquals(listOf(true), nextProcess.crashChanges)
    }

    @Test fun restoreIsolatesOptionalSdkFailureAndDisablesSavedOptIn() {
        val store = Store(SdkConsent(true, true))
        val sdk = Sdk().apply { failCrashStart = true }
        SdkConsentController(store, sdk).restore()
        assertFalse("A failed SDK must not be shown as enabled", store.state.crashReporting)
        assertEquals(listOf(true, false), sdk.crashChanges)
        assertEquals(1, sdk.mapsStarts)
    }

    @Test fun disableStillStopsSdkWhenSavingFails() {
        val sdk = Sdk(); val store = Store(SdkConsent(true, true))
        val controller = SdkConsentController(store, sdk)
        controller.restore()
        store.writable = false
        try { controller.setCrashReporting(false); fail("Write should fail") }
        catch (_: IllegalStateException) { }
        assertEquals("Withdrawal must precede disk write", listOf(true, false), sdk.crashChanges)
    }

    @Test fun commitThatChangesMemoryBeforeFailingRollsBackConsent() {
        val store = object : SdkConsentStore {
            var state = SdkConsent()
            var failNext = true
            override fun read() = state
            override fun write(value: SdkConsent) {
                state = value // SharedPreferences.commit updates memory even when disk write fails.
                if (failNext) { failNext = false; error("Disk full") }
            }
        }
        val sdk = Sdk()
        val controller = SdkConsentController(store, sdk)
        try { controller.acceptMaps(); fail("Write should fail") }
        catch (_: IllegalStateException) { }
        assertFalse("Failed agreement must not remain visible in preferences", store.read().mapsAccepted)
        assertEquals(0, sdk.mapsStarts)
    }

    @Test fun failedConsentWriteDoesNotInitializeSdk() {
        val sdk = Sdk(); val store = Store(writable = false)
        try { SdkConsentController(store, sdk).acceptMaps(); fail("Write should fail") }
        catch (_: IllegalStateException) { }
        assertEquals(0, sdk.mapsStarts)
        assertFalse(store.state.mapsAccepted)
    }
}
