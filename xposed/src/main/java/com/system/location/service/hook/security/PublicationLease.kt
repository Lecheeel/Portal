package com.system.location.service.hook.security

/** Pure monotonic freshness policy. All peer processes apply the same deadline. */
class PublicationLease(private val durationNanos: Long = 5_000_000_000L) {
    private var latestSample = -1L
    private var deadline = -1L
    @Synchronized fun accepts(sampleNanos: Long, nowNanos: Long): Boolean = sampleNanos >= 0 &&
        sampleNanos > latestSample && sampleNanos <= nowNanos && nowNanos - sampleNanos <= durationNanos
    @Synchronized fun renew(sampleNanos: Long, nowNanos: Long): Boolean {
        if (!accepts(sampleNanos, nowNanos)) return false
        latestSample = sampleNanos
        deadline = sampleNanos + durationNanos
        return true
    }
    @Synchronized fun expired(nowNanos: Long) = deadline >= 0 && nowNanos >= deadline
    @Synchronized fun clear() { latestSample = -1; deadline = -1 }
}
