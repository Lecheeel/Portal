package com.system.location.service.hook.hooks.telephony

/** AOSP notifyNow follows events; never mistake the leading location-renunciation flags for it. */
internal object CarrierRegistrationPolicy {
    fun notifyNow(args: List<Any?>): Boolean {
        val eventsIndex = args.indexOfLast { it is IntArray }
        return eventsIndex >= 0 && args.getOrNull(eventsIndex + 1) == true
    }
}
