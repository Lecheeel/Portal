package com.system.location.service.ui.diagnostics

import java.util.ArrayDeque
import java.util.Locale
import java.util.UUID

data class ProbeFix(
    val provider: String?, val latitude: Double, val longitude: Double, val mock: Boolean,
    val elapsedNanos: Long, val timeMillis: Long, val accuracy: Float
)

/** Bounded, in-memory receiver evidence. A missing fix is never classified as non-mock. */
class LocationProbeJournal(private val capacity: Int = 64) {
    init { require(capacity > 0) }
    val runId: String = UUID.randomUUID().toString()
    private val events = ArrayDeque<String>()
    private var sequence = 0L
    var sampleCount = 0L; private set
    var mockCount = 0L; private set
    var nonMockCount = 0L; private set

    fun message(source: String, detail: String) = append("$source · $detail")
    fun receive(source: String, fix: ProbeFix?, nowNanos: Long) {
        if (fix == null) { message(source, "无样本（不等于非模拟）"); return }
        sampleCount++
        if (fix.mock) mockCount++ else nonMockCount++
        append(String.format(Locale.ROOT,
            "%s · provider=%s · isMock=%s\nWGS84=%.7f, %.7f · accuracy=%.1fm\nageMs=%.1f · elapsedNanos=%d · timeMillis=%d",
            source, fix.provider, fix.mock, fix.latitude, fix.longitude, fix.accuracy,
            (nowNanos - fix.elapsedNanos) / 1_000_000.0, fix.elapsedNanos, fix.timeMillis))
    }
    private fun append(detail: String) {
        events.addFirst("#${++sequence} $detail")
        while (events.size > capacity) events.removeLast()
    }
    fun report(): String = "runId=$runId\n收到=$sampleCount · mock=$mockCount · nonMock=$nonMockCount\n" +
        "仅表示本接收进程的样本，不代表其他 App 的检测结果；保留最近 $capacity 条事件。\n\n" + events.joinToString("\n\n")
}
