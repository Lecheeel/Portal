package com.system.location.service.core.carrier

/** Platform IDs are not interchangeable: phone IDs are zero-based slots, sub IDs are opaque. */
object CarrierSubscriptionSelection {
    const val DEFAULT_SUBSCRIPTION_ID = Int.MAX_VALUE
    fun slot(method: String, requestedId: Int?, subscriptions: Map<Int, Int>, defaultSubId: Int): Int? {
        val phoneIndex = method.endsWith("ForPhone") || method.endsWith("ForSlot")
        val result = if (phoneIndex) {
            requestedId?.takeIf { it in 0..1 }?.plus(1)
        } else {
            val subId = if (requestedId == null || requestedId == DEFAULT_SUBSCRIPTION_ID) defaultSubId
                else requestedId
            subscriptions[subId]
        }
        return result?.takeIf { it in 1..2 && it in subscriptions.values }
    }
}
