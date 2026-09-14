package com.system.location.service.core

import com.system.location.service.core.runtime.ServiceStartGate
import org.junit.Assert.*
import org.junit.Test

class ServiceStartGateTest {
    @Test fun lateServiceCannotAttachAfterTimeoutOrReplaceNewerService() {
        val gate = ServiceStartGate()
        val old = gate.begin(); gate.cancel()
        assertFalse(gate.accept(old))
        val current = gate.begin()
        assertFalse(gate.accept(old)); assertTrue(gate.accept(current))
        assertFalse(gate.accept(current))
    }
}
