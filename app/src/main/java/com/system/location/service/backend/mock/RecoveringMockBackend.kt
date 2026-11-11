package com.system.location.service.backend.mock

import com.system.location.service.core.backend.*

interface PhysicalLocationRecovery {
    fun cancel()
    fun request()
}

/** Recovery starts only after both mock channels stop, and never changes cleanup success. */
class RecoveringMockBackend(
    private val delegate: LocationBackend,
    private val recovery: PhysicalLocationRecovery,
) : LocationBackend by delegate {
    private var started = false

    override suspend fun prepare(): BackendResult {
        recovery.cancel()
        return delegate.prepare()
    }

    override suspend fun start(): BackendResult = delegate.start().also {
        if (it is BackendResult.Success) started = true
    }

    override suspend fun stop(): BackendResult = delegate.stop().also {
        if (it is BackendResult.Success && started) {
            started = false
            recovery.request()
        }
    }

    override suspend fun release() = stop()
}
