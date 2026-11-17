package com.system.location.service.core.carrier

import kotlinx.serialization.Serializable

/** Pending and committed footprints cover a crash on either side of the property setter. */
@Serializable
data class CarrierPropertyJournalEntry(
    val committed: CarrierListPropertyState,
    val pending: CarrierListPropertyState? = null,
) {
    fun recover(current: List<String>): List<String> {
        val owner = CarrierListPropertyOwnership()
        val candidate = pending?.let {
            owner.restore(it)
            owner.plan(current, emptyMap()).values
        } ?: current
        owner.restore(committed)
        return owner.plan(candidate, emptyMap()).values
    }
}

@Serializable
data class CarrierPropertyJournalData(
    val bootCount: Int,
    val version: Int = 1,
    val fields: Map<String, CarrierPropertyJournalEntry> = emptyMap(),
)
