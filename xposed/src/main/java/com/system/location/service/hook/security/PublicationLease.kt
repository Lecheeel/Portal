package com.system.location.service.hook.security

/** Pure monotonic freshness policy. All peer processes apply the same deadline. */
class PublicationLease(private val durationNanos: Long = 5_000_000_000L) {
    init { require(durationNanos > 0L) }
    private var latestSample = -1L
    private var deadline = -1L
    private fun fresh(sampleNanos: Long, nowNanos: Long) = sampleNanos >= 0 &&
        sampleNanos <= Long.MAX_VALUE - durationNanos && sampleNanos <= nowNanos &&
        nowNanos - sampleNanos < durationNanos
    @Synchronized fun accepts(sampleNanos: Long, nowNanos: Long): Boolean =
        sampleNanos > latestSample && fresh(sampleNanos, nowNanos)
    @Synchronized fun renew(sampleNanos: Long, nowNanos: Long): Boolean {
        if (!accepts(sampleNanos, nowNanos)) return false
        latestSample = sampleNanos
        deadline = sampleNanos + durationNanos
        return true
    }
    /** Full-state ordering is enforced by the caller; repeated samples keep their original deadline. */
    @Synchronized fun restore(sampleNanos: Long, nowNanos: Long): Boolean {
        if (!fresh(sampleNanos, nowNanos)) { clear(); return false }
        latestSample = sampleNanos
        deadline = sampleNanos + durationNanos
        return true
    }
    @Synchronized fun expired(nowNanos: Long) = deadline >= 0 && nowNanos >= deadline
    @Synchronized fun clear() { latestSample = -1; deadline = -1 }
}
