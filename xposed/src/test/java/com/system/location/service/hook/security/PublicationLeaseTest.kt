package com.system.location.service.hook.security

import org.junit.Assert.*
import org.junit.Test

class PublicationLeaseTest {
    @Test fun fullStateRestoreAcceptsRepeatedSampleWithoutExtendingDeadline() {
        val lease = PublicationLease(100)
        assertTrue(lease.restore(1000, 1001))
        assertTrue(lease.restore(1000, 1099))
        assertFalse(lease.expired(1099))
        assertTrue(lease.expired(1100))
        assertFalse(lease.restore(1000, 1100))
        assertFalse(lease.renew(1000, 1100))
        assertFalse(lease.restore(1200, 1199))
    }
    @Test fun invalidDurationAndOverflowCannotCreateNonExpiringLease() {
        assertThrows(IllegalArgumentException::class.java) { PublicationLease(0) }
        val lease = PublicationLease(100)
        assertFalse(lease.renew(Long.MAX_VALUE, Long.MAX_VALUE))
        assertFalse(lease.restore(Long.MAX_VALUE - 99, Long.MAX_VALUE))
        assertTrue(lease.restore(Long.MAX_VALUE - 100, Long.MAX_VALUE - 1))
        assertTrue(lease.expired(Long.MAX_VALUE))
    }
    @Test fun staleFutureAndRepeatedSamplesAreRejected() {
        val lease = PublicationLease(100)
        assertFalse(lease.renew(-1, 0))
        assertFalse(lease.renew(201, 200))
        assertFalse(lease.renew(0, 200))
        assertTrue(lease.renew(150, 200))
        assertFalse(lease.renew(150, 200))
        assertFalse(lease.renew(149, 200))
    }
    @Test fun silenceExpiresAtSharedSampleDeadlineAndCanRestart() {
        val system = PublicationLease(100); val peer = PublicationLease(100)
        assertTrue(system.renew(1000, 1000)); assertTrue(peer.renew(1000, 1050))
        assertFalse(system.expired(1099)); assertFalse(peer.expired(1099))
        assertTrue(system.expired(1100)); assertTrue(peer.expired(1100))
        system.clear(); assertFalse(system.expired(1200))
        assertTrue(system.renew(1200, 1200))
    }
}
