package com.system.location.service.privacy

internal data class SdkConsent(val mapsAccepted: Boolean = false, val crashReporting: Boolean = false)
internal interface SdkConsentStore {
    fun read(): SdkConsent
    fun write(value: SdkConsent)
}
internal interface ConsentSdk {
    fun initializeMaps()
    fun setCrashReporting(enabled: Boolean)
}
internal class SdkConsentController(private val store: SdkConsentStore, private val sdk: ConsentSdk) {
    private var mapsInitialized = false
    private var crashEnabled = false
    private var crashClosed = false
    private var stopPending = false
    private var confirmed = SdkConsent()
    private var loaded = false
    private fun load() {
        if (!loaded) { confirmed = store.read(); loaded = true }
    }
    val mapsAccepted: Boolean @Synchronized get() = confirmed.mapsAccepted
    val mapsReady: Boolean @Synchronized get() = mapsInitialized
    val crashReportingRequested: Boolean @Synchronized get() = confirmed.crashReporting
    val crashReportingEnabled: Boolean @Synchronized get() = crashEnabled
    val crashReportingPendingRestart: Boolean @Synchronized get() = confirmed.crashReporting && crashClosed
    var lastFailure: Throwable? = null
        private set

    private fun persist(value: SdkConsent) {
        val previous = confirmed
        try {
            store.write(value)
            confirmed = value
        } catch (failure: Throwable) {
            try { store.write(previous) } catch (rollback: Throwable) { failure.addSuppressed(rollback) }
            throw failure
        }
    }
    @Synchronized fun restore() {
        lastFailure = null
        try { load(); apply(confirmed) } catch (failure: Throwable) { lastFailure = failure }
    }
    @Synchronized fun acceptMaps() {
        load()
        val next = confirmed.copy(mapsAccepted = true)
        persist(next)
        apply(next)
    }
    @Synchronized fun setCrashReporting(enabled: Boolean) {
        load()
        val current = confirmed
        check(!enabled || current.mapsAccepted) { "请先确认地图隐私说明" }
        val next = current.copy(crashReporting = enabled)
        if (!enabled) {
            val wasRunning = crashEnabled || stopPending
            crashEnabled = false
            confirmed = next // Withdrawal wins over both stop and storage failures.
            var stopFailure: Throwable? = null
            if (wasRunning) {
                crashClosed = true
                try { sdk.setCrashReporting(false); stopPending = false }
                catch (failure: Throwable) { stopPending = true; stopFailure = failure }
            }
            try { persist(next) } catch (failure: Throwable) {
                stopFailure?.let { failure.addSuppressed(it) }
                throw failure
            }
            stopFailure?.let { throw it }
            return
        }
        // Validate mandatory SDK readiness before persisting an optional opt-in.
        if (!mapsInitialized) { sdk.initializeMaps(); mapsInitialized = true }
        persist(next)
        apply(next)
    }
    private fun apply(consent: SdkConsent) {
        if (!consent.mapsAccepted) return
        if (!mapsInitialized) { sdk.initializeMaps(); mapsInitialized = true }
        if (consent.crashReporting && crashClosed) return
        if (consent.crashReporting != crashEnabled) {
            try {
                sdk.setCrashReporting(consent.crashReporting)
                crashEnabled = consent.crashReporting
            } catch (failure: Throwable) {
                crashClosed = true
                crashEnabled = false
                try { sdk.setCrashReporting(false); stopPending = false }
                catch (stop: Throwable) { stopPending = true; failure.addSuppressed(stop) }
                confirmed = confirmed.copy(crashReporting = false)
                try { persist(confirmed) } catch (save: Throwable) { failure.addSuppressed(save) }
                throw failure
            }
        }
    }
}
