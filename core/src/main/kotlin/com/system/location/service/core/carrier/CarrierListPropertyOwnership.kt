package com.system.location.service.core.carrier

/** Per-index ownership for framework telephony list properties. No persistent property is used. */
class CarrierListPropertyOwnership {
    private var baseline: Map<String, Any?> = emptyMap()
    private var written: Map<String, Any?> = emptyMap()

    fun update(current: List<String>, next: Map<Int, String>, write: (List<String>) -> Unit): List<String> {
        require(next.keys.all { it in 0..1 })
        val values: Map<String, Any?> = current.mapIndexed { index, value -> index.toString() to value }.toMap()
        val restore = CarrierOverrideOwnership.restorePlan(baseline, values, written)
        val unpatched = values.toMutableMap().apply {
            restore.remove.forEach(::remove)
            putAll(restore.restore)
        }
        val nextBaseline = unpatched.toMap()
        val nextWritten = next.mapKeys { it.key.toString() }
        unpatched.putAll(nextWritten)
        val last = unpatched.keys.maxOfOrNull { it.toInt() } ?: -1
        val result = (0..last).map { unpatched[it.toString()] as? String ?: "" }
        if (result != current) write(result)
        baseline = nextBaseline
        written = nextWritten
        return result
    }

    fun ownsValues(): Boolean = written.isNotEmpty()
}
