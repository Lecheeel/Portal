package com.system.location.service.hook.scope

import java.util.concurrent.ConcurrentHashMap

data class HookStatus(val supported: Boolean = false, val installed: Boolean = false,
    val matched: Boolean = false, val skipped: Boolean = false, val reason: String? = null,
    val failed: Boolean = false)

/** Bounded, in-memory diagnostics. No log or disk writes on a location callback. */
object HookStatusRegistry {
    private val entries = ConcurrentHashMap<String, HookStatus>()
    fun record(point: String, status: HookStatus) {
        if (entries.size < 2048 || entries.containsKey(point)) entries[point] = status
    }
    fun matched(point: String) {
        entries.computeIfPresent(point) { _, old -> old.copy(matched = true) }
    }
    fun installed(point: String) {
        entries.compute(point) { _, old -> (old ?: HookStatus()).copy(supported = true, installed = true) }
    }
    fun snapshot(): Map<String, HookStatus> = entries.toSortedMap()
}
