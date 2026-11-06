package com.system.location.service.core.privacy

/** Pure location-availability synthesis rule (P1-05). */
object LocationAvailabilityPolicy {
    const val FIRST_APP_UID = 10_000

    fun shouldSynthesize(enabled: Boolean, callerUid: Int): Boolean = enabled && callerUid >= FIRST_APP_UID
}
