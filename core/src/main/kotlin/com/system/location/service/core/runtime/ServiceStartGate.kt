package com.system.location.service.core.runtime

/** Rejects service callbacks belonging to a timed-out or superseded start request. */
class ServiceStartGate {
    private var next = 0L
    private var pending: Long? = null
    @Synchronized fun begin(): Long = (++next).also { pending = it }
    @Synchronized fun accept(request: Long): Boolean {
        if (pending != request) return false
        pending = null
        return true
    }
    @Synchronized fun cancel() { pending = null }
}
