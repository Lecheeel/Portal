package com.system.location.service.core.carrier

/** Changes needed to undo an overlay while preserving updates made by another writer. */
data class CarrierOverrideRestorePlan(
    val remove: Set<String>,
    val restore: Map<String, Any?>,
)

object CarrierOverrideOwnership {
    /** Update the restore baseline only where the temporary layer no longer matches our write. */
    fun rebase(
        baseline: Map<String, Any?>,
        current: Map<String, Any?>,
        written: Map<String, Any?>,
        nextKeys: Set<String>,
    ): Map<String, Any?> = baseline.toMutableMap().apply {
        nextKeys.forEach { key ->
            val stillOwned = written.containsKey(key) && current.containsKey(key) &&
                current[key] == written[key]
            if (!stillOwned) {
                if (current.containsKey(key)) put(key, current[key]) else remove(key)
            }
        }
    }

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
