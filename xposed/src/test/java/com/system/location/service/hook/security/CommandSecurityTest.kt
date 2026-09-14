package com.system.location.service.hook.security

import org.junit.Assert.*
import org.junit.Test

class CommandSecurityTest {
    @Test fun identityRequiresOwnedExactPackageAndUid() {
        val pkg = CallerPolicy.CONTROLLER_PACKAGE
        assertEquals(CallerRole.CONTROLLER, CallerPolicy.resolve(10123, setOf(pkg), 10123, emptySet()))
        assertEquals(CallerRole.DENIED, CallerPolicy.resolve(10124, setOf(pkg), 10123, emptySet()))
        assertEquals(CallerRole.DENIED, CallerPolicy.resolve(10123, setOf("$pkg.fake"), 10123, emptySet()))
        assertEquals(CallerRole.DENIED, CallerPolicy.resolve(-1, emptySet(), null, emptySet()))
        assertEquals(CallerRole.DENIED, CallerPolicy.resolve(0, setOf("untrusted"), null, emptySet()))
        assertEquals(CallerRole.PEER, CallerPolicy.resolve(1001, setOf("com.android.phone"), null, setOf("com.android.phone")))
        assertEquals(CallerRole.DENIED, CallerPolicy.resolve(11000, setOf("com.android.phone"), null, emptySet()))
    }

    @Test fun deniedExchangeCannotAuthorizeAnyCommand() {
        val gate = CommandSecurity { "secret" }
        assertNull(gate.exchange(10123, CallerRole.DENIED))
        assertFalse(gate.accept(10123, CallerRole.DENIED, "exchange_key", "start", 1, 1, 100, 100))
    }

    @Test fun sessionIsBoundToUidAndRejectsMalformedReplayAndStaleRequests() {
        var tokens = 0
        val gate = CommandSecurity { "key${++tokens}" }
        val token = gate.exchange(10123, CallerRole.CONTROLLER)!!
        fun accept(command: String? = "start", seq: Long = 1, sent: Long = 100, version: Int = 1) =
            gate.accept(10123, CallerRole.CONTROLLER, token, command, version, seq, sent, 100)
        assertFalse(gate.accept(10124, CallerRole.CONTROLLER, token, "start", 1, 1, 100, 100))
        assertFalse(accept(null)); assertFalse(accept("unknown")); assertFalse(accept(version = 2))
        assertFalse(accept(sent = 101)); assertFalse(accept(sent = -1))
        assertFalse(gate.accept(10123, CallerRole.CONTROLLER, token, "start", 1, 1, 0, 30_001))
        assertTrue(accept()); assertFalse(accept()); assertTrue(accept(seq = 2))
        gate.exchange(10123, CallerRole.CONTROLLER)
        assertFalse(accept(seq = 3))
    }

    @Test fun peersCanSyncButCannotControlSimulation() {
        val gate = CommandSecurity { "peer" }
        gate.exchange(1001, CallerRole.PEER)
        assertFalse(gate.accept(1001, CallerRole.PEER, "peer", "start", 1, 1, 100, 100))
        assertTrue(gate.accept(1001, CallerRole.PEER, "peer", "sync_config", 1, 1, 100, 100))
        assertTrue(gate.accept(1001, CallerRole.PEER, "peer", "set_proxy", 1, 2, 100, 100))
    }
}
