package com.system.location.service.core.runtime

/** Only complete replacement states may be coalesced. Delivery never holds the mailbox lock. */
class LatestStateWorker<T : Any>(
    private val deliver: (T) -> Unit,
    private val onFailure: (Throwable) -> Unit,
) : AutoCloseable {
    private val monitor = Object()
    private var pending: T? = null
    private var running = false
    private var closed = false

    /** At most one in-flight state and one pending latest state, even when the receiver stalls. */
    fun offer(state: T): Boolean = synchronized(monitor) {
        if (closed) return false
        pending = state
        monitor.notifyAll()
        if (!running) {
            running = true
            Thread(::drain, "RuntimePeerDelivery").apply { isDaemon = true }.start()
        }
        true
    }

    private fun drain() {
        while (true) {
            val next = synchronized(monitor) {
                while (!closed && pending == null) monitor.wait()
                if (closed) return
                val state = checkNotNull(pending)
                pending = null
                state
            }
            try {
                deliver(next)
            } catch (failure: Throwable) {
                close()
                onFailure(failure)
                return
            }
        }
    }

    override fun close() = synchronized(monitor) {
        closed = true
        pending = null
        monitor.notifyAll()
    }
}
