package com.system.location.service.hook.runtime

/** A bounded, coalesced connection job. Scheduling never runs Binder work on the caller. */
internal class DeferredServiceConnection(
    private val schedule: (Long, () -> Unit) -> Unit,
    private val connect: () -> Boolean,
    private val onFailure: (Exception) -> Unit,
    private val maxAttempts: Int = 60,
) {
    private val lock = Any()
    private var running = false
    private var connected = false

    init { require(maxAttempts > 0) }

    fun start() {
        synchronized(lock) {
            if (running || connected) return
            running = true
        }
        enqueue(0)
    }

    private fun enqueue(attempt: Int) {
        try {
            schedule(if (attempt == 0) 0L else minOf(250L * attempt, 2_000L)) {
                attemptConnection(attempt)
            }
        } catch (failure: Exception) {
            synchronized(lock) { running = false }
            onFailure(failure)
        }
    }

    private fun attemptConnection(attempt: Int) {
        var interrupted = false
        val ready = try { connect() } catch (failure: Exception) {
            if (failure is InterruptedException) {
                Thread.currentThread().interrupt()
                interrupted = true
            }
            onFailure(failure)
            false
        }
        synchronized(lock) {
            if (ready) connected = true
            if (ready || interrupted || attempt + 1 >= maxAttempts) {
                running = false
                return
            }
        }
        enqueue(attempt + 1)
    }
}
