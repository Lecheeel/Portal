package com.system.location.service.hook.runtime

/** Keeps a framework one-shot delivery intact after it applies permission-level filtering. */
internal object CurrentLocationDelivery {
    private data class Delivery(val owner: Any, val sample: () -> Any?)
    private val current = ThreadLocal<Delivery>()

    val active: Boolean get() = current.get() != null

    fun sampleFor(owner: Any?): Any? = current.get()?.takeIf { it.owner === owner }?.sample?.invoke()

    fun <T> during(owner: Any, sample: () -> Any?, deliver: () -> T): T {
        val previous = current.get()
        current.set(Delivery(owner, sample))
        return try { deliver() } finally {
            if (previous == null) current.remove() else current.set(previous)
        }
    }
}
