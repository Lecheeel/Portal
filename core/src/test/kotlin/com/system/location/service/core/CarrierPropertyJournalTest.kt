package com.system.location.service.core

import com.system.location.service.core.carrier.*
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class CarrierPropertyJournalTest {
    private fun roundTrip(entry: CarrierPropertyJournalEntry): CarrierPropertyJournalEntry =
        Json.decodeFromString(CarrierPropertyJournalEntry.serializer(),
            Json.encodeToString(CarrierPropertyJournalEntry.serializer(), entry))

    @Test fun crashBeforeOrAfterSetterRestoresOldAndPendingFootprints() {
        val owner = CarrierListPropertyOwnership()
        val old = owner.plan(listOf("real"), mapOf(0 to "old modeled")).state
        owner.restore(old)
        val next = owner.plan(listOf("old modeled"), mapOf(0 to "new modeled", 1 to "two"))
        val journal = roundTrip(CarrierPropertyJournalEntry(old, next.state))
        assertEquals(listOf("real"), journal.recover(listOf("old modeled")))
        assertEquals(listOf("real"), journal.recover(next.values))
        assertEquals(listOf("outside", "outside two"), journal.recover(listOf("outside", "outside two")))
    }

    @Test fun crashDuringWithdrawalAndEmptyOriginalPreserveAbsence() {
        val owner = CarrierListPropertyOwnership()
        val proposal = owner.plan(emptyList(), mapOf(1 to "modeled"))
        owner.restore(proposal.state)
        val withdrawal = owner.plan(proposal.values, emptyMap())
        val journal = roundTrip(CarrierPropertyJournalEntry(owner.snapshot(), withdrawal.state))
        assertTrue(journal.recover(proposal.values).isEmpty())
        assertTrue(journal.recover(withdrawal.values).isEmpty())
    }

    @Test fun planningDoesNotCommitAndRecoveryPreservesOutsidePartialEdits() {
        val owner = CarrierListPropertyOwnership()
        val next = owner.plan(listOf("base one", "base two"), mapOf(0 to "One", 1 to "Two"))
        assertFalse(owner.ownsValues())
        val journal = CarrierPropertyJournalEntry(CarrierListPropertyState(), next.state)
        assertEquals(listOf("outside", "base two"), journal.recover(listOf("outside", "Two")))
    }

    @Test fun malformedStateCannotSelectArbitraryListPositions() {
        val owner = CarrierListPropertyOwnership()
        assertThrows(IllegalArgumentException::class.java) {
            owner.restore(CarrierListPropertyState(written = mapOf("40" to "modeled")))
        }
        assertThrows(IllegalArgumentException::class.java) {
            owner.restore(CarrierListPropertyState(baseline = mapOf("invalid" to "value")))
        }
        assertThrows(IllegalArgumentException::class.java) {
            owner.restore(CarrierListPropertyState(written = mapOf("0" to "a".repeat(1025))))
        }
    }
}
