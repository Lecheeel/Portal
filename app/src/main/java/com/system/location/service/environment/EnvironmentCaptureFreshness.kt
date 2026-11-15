package com.system.location.service.environment

/** Keeps cached radio observations temporally close to the location used to anchor a capture. */
internal object EnvironmentCaptureFreshness {
    const val MAX_FIX_DELTA_NANOS = 15_000_000_000L

    fun matchesFix(sampleElapsedNanos: Long, fixElapsedNanos: Long): Boolean {
        if (sampleElapsedNanos <= 0L || fixElapsedNanos <= 0L) return false
        val delta = if (sampleElapsedNanos >= fixElapsedNanos) {
            sampleElapsedNanos - fixElapsedNanos
        } else {
            fixElapsedNanos - sampleElapsedNanos
        }
        return delta <= MAX_FIX_DELTA_NANOS
    }

    fun wifiTimestampNanos(timestampMicros: Long): Long? =
        timestampMicros.takeIf { it > 0L && it <= Long.MAX_VALUE / 1_000L }?.times(1_000L)

    fun cellTimestampNanos(timestampMillis: Long): Long? =
        timestampMillis.takeIf { it > 0L && it <= Long.MAX_VALUE / 1_000_000L }?.times(1_000_000L)
}
