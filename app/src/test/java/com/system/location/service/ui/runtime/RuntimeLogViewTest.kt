package com.system.location.service.ui.runtime

import com.system.location.service.core.backend.BackendType
import com.system.location.service.core.runtime.DiagnosticEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeLogViewTest {
    private val mock = BackendType.MOCK_PROVIDER
    private val xposed = BackendType.XPOSED
    private fun event(time: Long, backend: BackendType = mock, result: String = "OK") =
        DiagnosticEvent(time, backend, "PUBLISH", result, "event-$time")

    @Test fun allLogsRetainHistoryAndShowNewestFirstBeyondEightRows() {
        val history = (1L..25L).map { event(it) }
        val original = history.toList()
        assertEquals(history.asReversed(), visibleRuntimeEvents(history, 0, 0, mock))
        assertEquals(original, history)
    }

    @Test fun exceptionFilterIncludesFailuresAndActionableStatesAcrossBackends() {
        val results = listOf("OK", "FAILED", "ERROR", "REQUIRES_ACTION", "UNAVAILABLE", "AVAILABLE", "EXPERIMENTAL")
        val history = results.mapIndexed { index, result -> event(index + 1L, xposed, result) }
        assertEquals(
            listOf("UNAVAILABLE", "REQUIRES_ACTION", "ERROR", "FAILED"),
            visibleRuntimeEvents(history, 0, 1, mock).map { it.result },
        )
    }

    @Test fun currentBackendFilterTracksActualBackend() {
        val history = listOf(event(1), event(2, xposed), event(3))
        assertEquals(listOf(history[2], history[0]), visibleRuntimeEvents(history, 0, 2, mock))
        assertEquals(listOf(history[1]), visibleRuntimeEvents(history, 0, 2, xposed))
    }

    @Test fun clearHidesExistingRowsWithoutDeletingHistoryAndLaterEventsRemainVisible() {
        val history = listOf(event(10), event(20, xposed, "FAILED"))
        val original = history.toList()
        assertTrue(visibleRuntimeEvents(history, 20, 0, mock).isEmpty())
        assertEquals(original, history)
        val next = event(21)
        assertEquals(listOf(next), visibleRuntimeEvents(history + next, 20, 0, mock))
        assertTrue(visibleRuntimeEvents(history + next, 20, 1, mock).isEmpty())
    }
}
