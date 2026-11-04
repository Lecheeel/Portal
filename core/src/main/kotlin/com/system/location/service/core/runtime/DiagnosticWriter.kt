package com.system.location.service.core.runtime

import java.util.concurrent.RejectedExecutionException
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Process-owned single writer. enqueue never performs IO or waits for IO. Capacity counts
 * queued AND in-flight events; a full queue rejects the entire batch without dropping history.
 * Call flush outside runtime locks, and close before disposing the owner. Failed writes retain
 * their batch; the next enqueue/flush retries. A failed close may be retried after storage recovers.
 */
class DiagnosticWriter(
    private val journal: DiagnosticJournal,
    private val capacity: Int = 600,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val guard = Any()
    private val pending = ArrayDeque<DiagnosticEvent>()
    private val writes = Mutex()
    private val signal = Channel<Unit>(Channel.CONFLATED)
    private val owner = SupervisorJob()
    private val scope = CoroutineScope(owner + dispatcher)
    private val mutableFailure = MutableStateFlow<Throwable?>(null)
    val failure = mutableFailure.asStateFlow()
    private var accepting = true
    private var submitted = 0L
    private var persisted = 0L
    val pendingCount: Int get() = synchronized(guard) { pending.size }
    val isClosed: Boolean get() = worker.isCompleted

    init { require(capacity > 0) }

    fun enqueue(events: List<DiagnosticEvent>) {
        synchronized(guard) {
            check(accepting) { "Diagnostic writer is closed" }
            if (events.size > capacity - pending.size) {
                // A prior write may have failed while keeping the queue full. Retry that
                // accepted history even though this entire incoming batch is rejected.
                if (pending.isNotEmpty()) signal.trySend(Unit)
                throw RejectedExecutionException("Diagnostic queue is full ($capacity events)")
            }
            events.forEach { event -> pending.addLast(event.copy(stage = event.stage.take(128),
                result = event.result.take(64), reason = event.reason.take(2048), suggestion = event.suggestion.take(512))) }
            submitted += events.size
            if (events.isNotEmpty()) signal.trySend(Unit)
        }
    }

    private val worker = scope.launch {
        for (ignored in signal) {
            val target = synchronized(guard) { submitted }
            persistThrough(target)
        }
    }

    /** Barrier for all events accepted before this call. Failure is explicit and data is retained. */
    suspend fun flush(): Result<Unit> {
        val target = synchronized(guard) { submitted }
        return withContext(NonCancellable + dispatcher) { persistThrough(target) }
    }

    /** Stop accepting, persist the final batch, then release the owned worker. Idempotent. */
    suspend fun close(): Result<Unit> = withContext(NonCancellable) {
        synchronized(guard) { accepting = false }
        val result = flush()
        signal.cancel()
        worker.cancelAndJoin()
        owner.cancel()
        result
    }

    private suspend fun persistThrough(target: Long): Result<Unit> = writes.withLock {
        try {
            while (true) {
                val batch = synchronized(guard) {
                    if (persisted >= target) emptyList() else pending.take((target - persisted).toInt())
                }
                if (batch.isEmpty()) break
                journal.appendAll(batch)
                synchronized(guard) {
                    repeat(batch.size) { pending.removeFirst() }
                    persisted += batch.size
                }
                mutableFailure.value = null
            }
            Result.success(Unit)
        } catch (error: Exception) {
            mutableFailure.value = error
            Result.failure(error)
        }
    }
}
