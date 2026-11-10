package com.system.location.service.core.runtime

/** Orders complete states within an explicitly established receiver session. */
class SessionSequenceGate {
    private var expectedSession: String? = null
    private var latestSequence = 0L

    @Synchronized fun reset(session: String) {
        require(session.isNotBlank() && session.length <= 128)
        expectedSession = session
        latestSequence = 0L
    }

    /** Validation and restore must succeed before advancing; reset cannot interleave with restore. */
    @Synchronized fun apply(session: String, sequence: Long, restore: () -> Boolean): Boolean {
        if (session != expectedSession || sequence <= latestSequence) return false
        if (!restore()) return false
        latestSequence = sequence
        return true
    }
}
