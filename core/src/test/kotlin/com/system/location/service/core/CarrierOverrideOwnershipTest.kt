package com.system.location.service.core

import com.system.location.service.core.carrier.CarrierOverrideOwnership
import org.junit.Assert.*
import org.junit.Test

class CarrierOverrideOwnershipTest {
    private fun restored(baseline: Map<String, Any?>, current: Map<String, Any?>,
        written: Map<String, Any?>): Map<String, Any?> {
        val plan = CarrierOverrideOwnership.restorePlan(baseline, current, written)
        return current.toMutableMap().apply { plan.remove.forEach(::remove); putAll(plan.restore) }
    }

    @Test fun absentOriginalKeysAreRemovedWithoutRemovingOtherOverrides() {
        val baseline = mapOf("volte" to true)
        val patch = mapOf("name" to "Test", "home" to true)
        assertEquals(baseline, restored(baseline, baseline + patch, patch))
    }

    @Test fun restoresExistingOverridesAndPreservesNewUnrelatedValues() {
        val baseline = mapOf("name" to "Original", "home" to false, "volte" to true)
        val patch = mapOf("name" to "Test", "home" to true)
        val current = baseline + patch + mapOf("wifi_calling" to true, "volte" to false)
        assertEquals(mapOf("name" to "Original", "home" to false,
            "volte" to false, "wifi_calling" to true), restored(baseline, current, patch))
    }

    @Test fun preservesAnotherWritersReplacementOrRemovalOfOwnedFields() {
        val baseline = mapOf("name" to "Original", "home" to false)
        val patch = mapOf("name" to "Test", "home" to true)
        assertEquals(mapOf("name" to "Other tool", "home" to false),
            restored(baseline, mapOf("name" to "Other tool", "home" to true), patch))
        assertTrue(restored(baseline, emptyMap(), patch).isEmpty())
    }

    @Test fun repeatedApplyRebasesExternalChangesButKeepsOwnedOriginalValues() {
        val baseline = mapOf("name" to "Original", "home" to false)
        val written = mapOf("name" to "Portal", "home" to true)
        val current = mapOf("name" to "External update", "home" to true)
        val next = mapOf("name" to "Portal new", "home" to true)
        val rebased = CarrierOverrideOwnership.rebase(baseline, current, written, next.keys)
        assertEquals(mapOf("name" to "External update", "home" to false),
            restored(rebased, current + next, next))
        assertEquals("Original", baseline["name"])
    }

    @Test fun repeatedApplyPreservesExternalDeletionAndExplicitNull() {
        val baseline = mapOf("name" to "Original")
        val written = mapOf("name" to "Portal")
        val next = mapOf("name" to "Portal new")
        val deleted = CarrierOverrideOwnership.rebase(baseline, emptyMap(), written, next.keys)
        assertFalse(restored(deleted, next, next).containsKey("name"))
        val nulled = CarrierOverrideOwnership.rebase(baseline, mapOf("name" to null), written, next.keys)
        assertTrue(restored(nulled, next, next).containsKey("name"))
        assertNull(restored(nulled, next, next)["name"])
    }

    @Test fun ownRepeatedUpdatesDoNotReplaceBaselineWithPreviousPortalValue() {
        val baseline = mapOf("name" to "Original")
        val written = mapOf("name" to "Portal")
        assertEquals(baseline, CarrierOverrideOwnership.rebase(baseline, written, written, written.keys))
    }

    @Test fun absentAndExplicitNullHaveDifferentRestorationSemantics() {
        val patch = mapOf("name" to "Test")
        val result = restored(mapOf("name" to null), patch, patch)
        assertTrue(result.containsKey("name"))
        assertNull(result["name"])
        assertTrue(restored(emptyMap(), patch, patch).isEmpty())
    }
}
