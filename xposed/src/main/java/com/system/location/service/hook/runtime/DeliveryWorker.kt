package com.system.location.service.hook.runtime

import java.util.concurrent.TimeUnit

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
        val wakeup = Object()
        var requested = false // guarded by wakeup; multiple publications coalesce
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

    /** Wake the existing producer without running callbacks on the publishing thread. */
    fun requestTick() {
        val run = synchronized(this) { current?.takeUnless { it.stopping } } ?: return
        synchronized(run.wakeup) {
            run.requested = true
            run.wakeup.notifyAll()
        }
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
                if (delayFirstTick) pause(run)
                while (!run.stopping && !Thread.currentThread().isInterrupted) {
                    try { tick() }
                    catch (interrupted: InterruptedException) { throw interrupted }
                    catch (error: Exception) { failure(error) }
                    if (!run.stopping) pause(run)
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

    private fun pause(run: Run) {
        val duration = TimeUnit.MILLISECONDS.toNanos(intervalMillis().also { require(it > 0) })
        val deadline = System.nanoTime() + duration
        synchronized(run.wakeup) {
            var remaining = duration
            while (!run.requested && !run.stopping && remaining > 0) {
                TimeUnit.NANOSECONDS.timedWait(run.wakeup, remaining)
                remaining = deadline - System.nanoTime()
            }
            run.requested = false
        }
    }
}
