package com.system.location.service.hook.runtime

/** A replacement starts only after the previous callback has returned, including after timeout. */
internal class DeliveryWorker(
    private val name: String,
    private val intervalMillis: () -> Long,
    private val delayFirstTick: Boolean = false,
    private val tick: () -> Unit,
    private val failure: (Exception) -> Unit,
) {
    private class Run {
        @Volatile var stopping = false
        lateinit var thread: Thread
    }

    private var current: Run? = null
    private var restartRequested = false

    @Synchronized fun start() {
        val run = current
        if (run != null) {
            if (run.stopping) restartRequested = true
            return
        }
        launch()
    }

    /** Returns true only when the stopped thread has exited. Never waits for itself. */
    fun stop(timeoutMillis: Long = 2000): Boolean {
        require(timeoutMillis in 0L..60_000L)
        val run = synchronized(this) {
            restartRequested = false
            current?.also { it.stopping = true; it.thread.interrupt() }
        } ?: return true
        if (run.thread === Thread.currentThread()) return false
        // Thread.join(0) means an unlimited wait, not a zero-duration poll.
        if (timeoutMillis > 0) run.thread.join(timeoutMillis)
        return !run.thread.isAlive
    }

    private fun launch() {
        val run = Run()
        run.thread = Thread({
            try {
                if (delayFirstTick) pause()
                while (!run.stopping && !Thread.currentThread().isInterrupted) {
                    try { tick() }
                    catch (interrupted: InterruptedException) { throw interrupted }
                    catch (error: Exception) { failure(error) }
                    if (!run.stopping) pause()
                }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            } finally {
                synchronized(this) {
                    check(current === run)
                    current = null
                    if (restartRequested) {
                        restartRequested = false
                        launch()
                    }
                }
            }
        }, name).apply { isDaemon = true }
        current = run
        run.thread.start()
    }

    private fun pause() = Thread.sleep(intervalMillis().also { require(it > 0) })
}
