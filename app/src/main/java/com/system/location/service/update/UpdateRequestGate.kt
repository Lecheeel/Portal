package com.system.location.service.update

/** Rejects stale asynchronous results after a channel or request change. */
internal class UpdateRequestGate<C>(initialChannel: C?) {
    private var channel: C? = initialChannel
    private var generation = 0L

    @Synchronized fun select(value: C?) {
        if (channel != value) {
            channel = value
            generation++
        }
    }

    @Synchronized fun begin(): Long? {
        if (channel == null) return null
        return ++generation
    }

    @Synchronized fun accepts(request: Long, requestedChannel: C) =
        channel == requestedChannel && generation == request
}
