package com.system.location.service.hook.runtime

import java.util.concurrent.Executor
import java.util.concurrent.ForkJoinPool
import java.util.concurrent.atomic.AtomicBoolean

/** Reuse platform workers; a slow Binder call must not stall a looper or build a polling queue. */
internal class BackgroundQuery(private val executor: Executor = ForkJoinPool.commonPool()) {
    private val pending = AtomicBoolean()

    fun <T> submit(read: () -> T, receive: (Result<T>) -> Unit): Boolean {
        if (!pending.compareAndSet(false, true)) return false
        try {
            executor.execute {
                try { receive(runCatching(read)) }
                finally { pending.set(false) }
            }
        } catch (failure: RuntimeException) {
            pending.set(false)
            throw failure
        }
        return true
    }
}
