package com.system.location.service.update

import org.junit.Assert.*
import org.junit.Test

class UpdateChannelStoreTest {
    @Test fun failedCommitRestoresConfirmedChannelEvenAfterMemoryChanged() {
        var disk = "stable"
        var commitFails = true
        val store = UpdateChannelStore(read = { disk }, write = {
            disk = it
            if (commitFails) throw IllegalStateException("commit failed")
        })
        try { store.set("beta"); fail("Expected failure") } catch (_: IllegalStateException) { }
        assertEquals("stable", store.get())
        assertEquals("stable", disk)
        commitFails = false
        store.set("beta")
        assertEquals("beta", store.get())
        assertEquals("beta", disk)
    }
}
