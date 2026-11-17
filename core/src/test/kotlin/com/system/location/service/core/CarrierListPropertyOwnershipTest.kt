package com.system.location.service.core

import com.system.location.service.core.carrier.CarrierListPropertyOwnership
import org.junit.Assert.*
import org.junit.Test

class CarrierListPropertyOwnershipTest {
    @Test fun dualSlotsRestoreMissingOriginalElementsAndPreserveUnownedUpdates() {
        val owner = CarrierListPropertyOwnership()
        var current = listOf("real")
        fun set(next: Map<Int, String>) { owner.update(current, next) { current = it } }
        set(mapOf(0 to "One", 1 to "Two"))
        assertEquals(listOf("One", "Two"), current)
        set(mapOf(1 to "New two"))
        assertEquals(listOf("real", "New two"), current)
        current = listOf("external", "New two", "third slot untouched")
        set(emptyMap())
        assertEquals(listOf("external", "", "third slot untouched"), current)
        assertFalse(owner.ownsValues())
    }
    @Test fun externalSameSlotChangeBecomesLatestBaselineOnReapply() {
        val owner = CarrierListPropertyOwnership()
        var current = listOf("original")
        owner.update(current, mapOf(0 to "Portal")) { current = it }
        current = listOf("external")
        owner.update(current, mapOf(0 to "Portal again")) { current = it }
        owner.update(current, emptyMap()) { current = it }
        assertEquals(listOf("external"), current)
    }
    @Test fun failedWriteDoesNotLosePreviousOwnershipAndEmptyOriginalRestoresEmpty() {
        val owner = CarrierListPropertyOwnership()
        var current = emptyList<String>()
        owner.update(current, mapOf(0 to "Portal")) { current = it }
        assertThrows(IllegalStateException::class.java) {
            owner.update(current, emptyMap()) { error("property denied") }
        }
        assertTrue(owner.ownsValues())
        owner.update(current, emptyMap()) { current = it }
        assertTrue(current.isEmpty())
    }
}
