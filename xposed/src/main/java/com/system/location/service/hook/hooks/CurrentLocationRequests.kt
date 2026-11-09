package com.system.location.service.hook.hooks

import java.lang.ref.ReferenceQueue
import java.lang.ref.WeakReference

/** Associates a one-shot callback with its request without retaining cancelled callbacks. */
internal class CurrentLocationRequests {
    private val collectedCallbacks = ReferenceQueue<Any>()
    private val providers = HashMap<CallbackReference, String>()

    @Synchronized
    fun register(callback: Any, provider: String) {
        discardCollectedCallbacks()
        providers[CallbackReference(callback, collectedCallbacks)] = provider
    }

    /** Consumes only this callback's provider, even when callback objects compare equal. */
    @Synchronized
    fun take(callback: Any): String? {
        discardCollectedCallbacks()
        return providers.remove(CallbackReference(callback))
    }

    /** Drops an association when installing or registering the callback fails. */
    @Synchronized
    fun remove(callback: Any) {
        discardCollectedCallbacks()
        providers.remove(CallbackReference(callback))
    }

    private fun discardCollectedCallbacks() {
        while (true) {
            val collected = collectedCallbacks.poll() as? CallbackReference ?: return
            providers.remove(collected)
        }
    }

    private class CallbackReference(
        callback: Any,
        queue: ReferenceQueue<Any>? = null,
    ) : WeakReference<Any>(callback, queue) {
        private val identityHash = System.identityHashCode(callback)

        override fun hashCode(): Int = identityHash

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is CallbackReference) return false
            val callback = get() ?: return false
            return callback === other.get()
        }
    }
}
