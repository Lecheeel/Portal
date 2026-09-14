package com.system.location.service.hook.utils

/** Bounded per-key deduplication plus a global burst ceiling, using monotonic time. */
class LogRateLimiter(private val intervalNanos: Long = 5_000_000_000L, private val capacity: Int = 256,
    private val maxPerSecond: Int = 10) {
    private val last = LinkedHashMap<String, Long>()
    private var window = -1L
    private var count = 0
    @Synchronized fun allow(key: String, now: Long): Boolean {
        if (last[key]?.let { now - it < intervalNanos } == true) return false
        if (window < 0 || now - window >= 1_000_000_000L) { window = now; count = 0 }
        if (count >= maxPerSecond) return false
        if (key !in last && last.size >= capacity) last.remove(last.keys.first())
        last[key] = now
        count++
        return true
    }
}
