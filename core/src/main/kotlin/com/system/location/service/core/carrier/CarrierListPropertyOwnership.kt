package com.system.location.service.core.carrier

import kotlinx.serialization.Serializable

@Serializable
data class CarrierListPropertyState(
    val baseline: Map<String, String?> = emptyMap(),
    val written: Map<String, String?> = emptyMap(),
) {
    fun validate() {
        require(baseline.size <= 16 && baseline.keys.all { key ->
            key.toIntOrNull()?.let { it in 0..15 && it.toString() == key } == true
        })
        require(written.size <= 2 && written.keys.all { key ->
            key.toIntOrNull()?.let { it in 0..1 && it.toString() == key } == true
        })
        require((baseline.values + written.values).all { it == null || it.length <= 1024 })
    }
}

data class CarrierListPropertyPlan(val values: List<String>, val state: CarrierListPropertyState)

/** Per-index ownership for framework telephony list properties. No persistent property is used. */
class CarrierListPropertyOwnership {
    private var baseline: Map<String, Any?> = emptyMap()
    private var written: Map<String, Any?> = emptyMap()

    fun plan(current: List<String>, next: Map<Int, String>): CarrierListPropertyPlan {
        require(current.size <= 16)
        require(next.keys.all { it in 0..1 })
        val values: Map<String, Any?> = current.mapIndexed { index, value -> index.toString() to value }.toMap()
        val restore = CarrierOverrideOwnership.restorePlan(baseline, values, written)
        val unpatched = values.toMutableMap().apply {
            restore.remove.forEach(::remove)
            putAll(restore.restore)
        }
        val nextBaseline = unpatched.toMap()
        val nextWritten = next.mapKeys { it.key.toString() }.toMutableMap()
        for (index in 0..(next.keys.maxOrNull() ?: -1)) {
            val key = index.toString()
            if (key !in unpatched && key !in nextWritten) nextWritten[key] = ""
        }
        unpatched.putAll(nextWritten)
        val last = unpatched.keys.maxOfOrNull { it.toInt() } ?: -1
        val result = (0..last).map { unpatched[it.toString()] as? String ?: "" }
        val state = CarrierListPropertyState(nextBaseline.mapValues { it.value as? String }, nextWritten)
        state.validate()
        return CarrierListPropertyPlan(result, state)
    }

    fun snapshot(): CarrierListPropertyState = CarrierListPropertyState(
        baseline.mapValues { it.value as? String }, written.mapValues { it.value as? String })

    fun restore(state: CarrierListPropertyState) {
        state.validate()
        baseline = state.baseline.toMap()
        written = state.written.toMap()
    }

    fun update(current: List<String>, next: Map<Int, String>, write: (List<String>) -> Unit): List<String> {
        val proposal = plan(current, next)
        if (proposal.values != current) write(proposal.values)
        restore(proposal.state)
        return proposal.values
    }

    fun ownsValues(): Boolean = written.isNotEmpty()
}
