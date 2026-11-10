package com.system.location.service.hook.runtime

import java.util.IdentityHashMap
import java.util.concurrent.TimeUnit

/**
 * Drains a generation without retaining its code in the successor. Only platform objects cross
 * the boundary: an Object monitor, primitive counters and an empty platform map of active threads.
 * Nested calls from an existing reader remain allowed while a writer waits, avoiding read/write
 * upgrade deadlocks inside hooked framework methods.
 */
internal class ReloadExecutionGate private constructor(private val shared: Array<Any>,
    private val generation: Long) {
    constructor() : this(arrayOf(java.lang.Object(), longArrayOf(0L, 0L, 0L),
        IdentityHashMap<Thread, Int>()), 0L)

    private val monitor get() = shared[0] as java.lang.Object
    private val counters get() = shared[1] as LongArray
    @Suppress("UNCHECKED_CAST")
    private val readers get() = shared[2] as IdentityHashMap<Thread, Int>
    private var transitionOwner: Thread? = null
    val isFrozen: Boolean get() = synchronized(monitor) { counters[FROZEN] != 0L }

    fun <T> enter(action: (currentGeneration: Boolean) -> T): T {
        val thread = Thread.currentThread()
        val current = synchronized(monitor) {
            // A queued old interceptor can outlive replacement of its hook. The caller must
            // re-enter the current framework hook chain when this returns false.
            while (counters[FROZEN] != 0L && readers[thread] == null && transitionOwner !== thread)
                monitor.wait()
            readers[thread] = (readers[thread] ?: 0) + 1
            counters[ACTIVE]++
            counters[GENERATION] == generation
        }
        try { return action(current) }
        finally {
            synchronized(monitor) {
                val remaining = checkNotNull(readers[thread]) - 1
                if (remaining == 0) readers.remove(thread) else readers[thread] = remaining
                counters[ACTIVE]--
                monitor.notifyAll()
            }
        }
    }

    /** A timeout cancels the freeze; the same generation remains usable. */
    fun freeze(timeoutMillis: Long): Boolean = synchronized(monitor) {
        require(timeoutMillis in 0L..60_000L)
        check(readers[Thread.currentThread()] == null) { "Cannot reload from inside a hooked call" }
        if (counters[FROZEN] != 0L || counters[GENERATION] != generation) return false
        counters[FROZEN] = 1L
        transitionOwner = Thread.currentThread()
        val started = System.nanoTime()
        val timeout = TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        try {
            while (counters[ACTIVE] != 0L) {
                val remaining = timeout - (System.nanoTime() - started)
                if (remaining <= 0L) { thaw(); return false }
                monitor.wait(remaining / 1_000_000L, (remaining % 1_000_000L).toInt())
            }
            true
        } catch (interrupted: InterruptedException) {
            thaw()
            throw interrupted
        }
    }

    fun transferState(): Array<Any> = synchronized(monitor) {
        check(counters[FROZEN] == 1L && counters[ACTIVE] == 0L && readers.isEmpty())
        check(counters[GENERATION] == generation)
        shared
    }

    fun resume() = synchronized(monitor) {
        check(counters[GENERATION] == generation) { "A retired generation cannot resume delivery" }
        thaw()
    }

    private fun thaw() {
        counters[FROZEN] = 0L
        transitionOwner = null
        monitor.notifyAll()
    }

    companion object {
        private const val ACTIVE = 0
        private const val FROZEN = 1
        private const val GENERATION = 2

        fun adopt(shared: Array<Any>): ReloadExecutionGate {
            require(shared.size == 3 && shared[0].javaClass == java.lang.Object::class.java &&
                shared[1] is LongArray && shared[2].javaClass == IdentityHashMap::class.java)
            val monitor = shared[0] as java.lang.Object
            return synchronized(monitor) {
                val counters = shared[1] as LongArray
                require(counters.size == 3 && counters[FROZEN] == 1L && counters[ACTIVE] == 0L &&
                    (shared[2] as Map<*, *>).isEmpty())
                counters[GENERATION] = Math.addExact(counters[GENERATION], 1L)
                ReloadExecutionGate(shared, counters[GENERATION]).also {
                    it.transitionOwner = Thread.currentThread()
                }
            }
        }
    }
}
