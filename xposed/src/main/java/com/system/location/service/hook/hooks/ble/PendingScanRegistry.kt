package com.system.location.service.hook.hooks.ble

internal sealed interface PendingScanSelection<out V> {
    data object None : PendingScanSelection<Nothing>
    data object Ambiguous : PendingScanSelection<Nothing>
    data class Unique<V>(val filters: List<V>) : PendingScanSelection<V>
}

/** Keeps PendingIntent filters paired with their registrations instead of sharing the last scan. */
internal class PendingScanRegistry<K : Any, V> {
    private val registrations = linkedMapOf<K, List<V>>()

    @Synchronized
    fun register(key: K, filters: List<V>) {
        registrations[key] = filters.toList()
    }

    @Synchronized
    fun unregister(key: K) {
        registrations.remove(key)
    }

    @Synchronized
    fun selection(): PendingScanSelection<V> = when (registrations.size) {
        0 -> PendingScanSelection.None
        1 -> registrations.values.firstOrNull()?.let { PendingScanSelection.Unique(it) }
            ?: PendingScanSelection.None
        else -> PendingScanSelection.Ambiguous
    }
}
