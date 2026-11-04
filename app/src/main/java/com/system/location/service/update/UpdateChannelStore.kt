package com.system.location.service.update

/** Confirmed choice remains authoritative when storage mutates memory then reports failure. */
internal class UpdateChannelStore<C>(private val read: () -> C, private val write: (C) -> Unit) {
    private var confirmed = read()
    @Synchronized fun get(): C = confirmed
    @Synchronized fun set(value: C) {
        val old = confirmed
        try { write(value) } catch (failure: Exception) {
            try { write(old) } catch (rollback: Exception) { failure.addSuppressed(rollback) }
            throw failure
        }
        confirmed = value
    }
}
