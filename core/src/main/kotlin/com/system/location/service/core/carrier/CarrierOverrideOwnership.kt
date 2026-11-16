package com.system.location.service.core.carrier

/** Changes needed to undo an overlay while preserving updates made by another writer. */
data class CarrierOverrideRestorePlan(
    val remove: Set<String>,
    val restore: Map<String, Any?>,
)

object CarrierOverrideOwnership {
    fun restorePlan(
        baseline: Map<String, Any?>,
        current: Map<String, Any?>,
        written: Map<String, Any?>,
    ): CarrierOverrideRestorePlan {
        val matching = written.keys.filterTo(linkedSetOf()) { key ->
            current.containsKey(key) && current[key] == written[key]
        }
        return CarrierOverrideRestorePlan(matching, baseline.filterKeys { it in matching })
    }
}
