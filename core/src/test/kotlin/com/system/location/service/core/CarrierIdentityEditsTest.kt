package com.system.location.service.core

import com.system.location.service.core.carrier.*
import org.junit.Assert.*
import org.junit.Test

class CarrierIdentityEditsTest {
    @Test fun reopeningPickerPreservesSimTargetsAndAppliesDeselection() {
        val previous = listOf(CarrierAppRoute("keep.app", CarrierTarget.SIM2),
            CarrierAppRoute("remove.app", CarrierTarget.SIM1),
            CarrierAppRoute("real.app", CarrierTarget.REAL))
        val result = reconcileCarrierRoutes(previous, listOf("keep.app", "real.app", "new.app"))
        assertEquals(listOf(previous[0], previous[2], CarrierAppRoute("new.app")), result)
        assertEquals(3, previous.size)
    }

    @Test fun clearingPickerRemovesAllRoutesAndDuplicatePackageNamesDoNotResetTargets() {
        val existing = listOf(CarrierAppRoute("keep.app", CarrierTarget.SIM2))
        assertTrue(reconcileCarrierRoutes(existing, emptyList()).isEmpty())
        assertEquals(existing, reconcileCarrierRoutes(existing, listOf("KEEP.APP", "keep.app")))
    }

    @Test fun rejectsTooManyRoutesBeforeChangingDraft() {
        val existing = listOf(CarrierAppRoute("existing.app", CarrierTarget.REAL))
        assertThrows(IllegalArgumentException::class.java) {
            reconcileCarrierRoutes(existing, (0..CarrierIdentityConfig.MAX_ROUTES).map { "app.$it" })
        }
        assertEquals(CarrierTarget.REAL, existing.single().target)
    }
}
