package com.system.location.service.core.runtime

import com.system.location.service.core.repository.DocumentStore
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** Bounded, atomic local history. Failed writes leave persisted and cached history intact. */
class DiagnosticJournal(private val store: DocumentStore, private val maxEvents: Int = 300, private val maxChars: Int = 300_000) {
    private val codec = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(DiagnosticEvent.serializer())
    private var cached: List<DiagnosticEvent>? = null
    init { require(maxEvents > 0 && maxChars >= 1024) }
    @Synchronized fun read(): List<DiagnosticEvent> {
        cached?.let { return it.toList() }
        val text = store.read()
        require(text == null || text.length <= maxChars) { "诊断历史文件过大" }
        return (text?.let { codec.decodeFromString(serializer, it) } ?: emptyList()).takeLast(maxEvents).also { cached = it }.toList()
    }
    @Synchronized fun append(event: DiagnosticEvent) {
        val bounded = event.copy(stage = event.stage.take(128), result = event.result.take(64), reason = event.reason.take(2048), suggestion = event.suggestion.take(512))
        var next = (read() + bounded).takeLast(maxEvents)
        var text = codec.encodeToString(serializer, next)
        while (text.length > maxChars && next.size > 1) { next = next.drop(1); text = codec.encodeToString(serializer, next) }
        require(text.length <= maxChars) { "诊断记录过大" }
        store.writeAtomically(text)
        cached = next
    }
}
