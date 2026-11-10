package com.system.location.service.hook.runtime

/** A rejected/failed handoff must leave the current generation able to resume delivery. */
internal object ReloadPreparation {
    fun prepare(pauseLocation: () -> Boolean, pauseGnss: () -> Boolean,
        save: () -> Unit, abort: () -> Unit): Boolean {
        var prepared = false
        var failure: Throwable? = null
        try {
            // Always quiesce both producers, including when the first one times out.
            val locationStopped = pauseLocation()
            val gnssStopped = pauseGnss()
            if (!locationStopped || !gnssStopped) return false
            save()
            prepared = true
            return true
        } catch (error: Throwable) {
            failure = error
            throw error
        } finally {
            if (!prepared) try { abort() } catch (rollback: Throwable) {
                if (failure == null) throw rollback
                if (rollback !== failure) failure.addSuppressed(rollback)
            }
        }
    }
}
