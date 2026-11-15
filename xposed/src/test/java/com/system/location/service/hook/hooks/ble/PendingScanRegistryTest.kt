package com.system.location.service.hook.hooks.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PendingScanRegistryTest {
    @Test fun soleRegistrationKeepsItsOwnFilterSnapshot() {
        val registry = PendingScanRegistry<Any, String>()
        val token = Any()
        registry.register(token, listOf("beacon-a"))

        assertEquals(PendingScanSelection.Unique(listOf("beacon-a")), registry.selection())
    }

    @Test fun multipleRegistrationsAreAmbiguousInsteadOfUsingTheMostRecentFilter() {
        val registry = PendingScanRegistry<Any, String>()
        registry.register(Any(), listOf("beacon-a"))
        registry.register(Any(), listOf("beacon-b"))

        assertEquals(PendingScanSelection.Ambiguous, registry.selection())
    }

    @Test fun stoppingOneRegistrationRestoresTheRemainingFilterSnapshot() {
        val registry = PendingScanRegistry<Any, String>()
        val first = Any()
        val second = Any()
        registry.register(first, listOf("beacon-a"))
        registry.register(second, listOf("beacon-b"))
        registry.unregister(first)

        val selected = registry.selection()
        assertTrue(selected is PendingScanSelection.Unique)
        assertEquals(listOf("beacon-b"), (selected as PendingScanSelection.Unique).filters)
    }
}
