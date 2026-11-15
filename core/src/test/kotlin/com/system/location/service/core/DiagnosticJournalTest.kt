package com.system.location.service.core

import com.system.location.service.core.backend.*
import com.system.location.service.core.repository.DocumentStore
import com.system.location.service.core.runtime.*
import com.system.location.service.core.geo.Wgs84
import com.system.location.service.core.scenario.Scenario
import com.system.location.service.core.location.LocationSample
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class DiagnosticJournalTest {
    private class Store : DocumentStore {
        var text: String? = null; var fail = false
        override fun read() = text
        override fun writeAtomically(text: String) { if (fail) error("disk full"); this.text = text }
    }
    private fun event(i: Long) = DiagnosticEvent(i, BackendType.MOCK_PROVIDER, "TEST", "INFO", "message $i")
    @Test fun batchUsesOneAtomicWriteAndKeepsCacheOnFailure() {
        var writes = 0
        val store = object : DocumentStore {
            var text: String? = null
            var fail = false
            override fun read() = text
            override fun writeAtomically(text: String) {
                writes++
                if (fail) error("disk full")
                this.text = text
            }
        }
        val journal = DiagnosticJournal(store, maxEvents = 5)
        journal.appendAll((1L..60L).map(::event))
        assertEquals(1, writes)
        assertEquals((56L..60L).toList(), DiagnosticJournal(store).read().map { it.timestamp })
        store.fail = true
        assertThrows(Exception::class.java) { journal.appendAll(listOf(event(61), event(62))) }
        assertEquals((56L..60L).toList(), journal.read().map { it.timestamp })
        assertEquals(journal.read(), DiagnosticJournal(store).read())
        journal.appendAll(emptyList())
        assertEquals(2, writes)
    }
    @Test fun boundedHistorySurvivesRecreationAndFailedWrites() {
        val store = Store(); val journal = DiagnosticJournal(store, maxEvents = 5)
        for (i in 1L..10L) journal.append(event(i))
        assertEquals((6L..10L).toList(), DiagnosticJournal(store).read().map { it.timestamp })
        store.fail = true
        assertThrows(Exception::class.java) { journal.append(event(11)) }
        assertEquals(10L, journal.read().last().timestamp)
        assertEquals(10L, DiagnosticJournal(store).read().last().timestamp)
    }
    @Test fun historyIsAlsoBoundedBySizeAndCorruptionIsReported() {
        val store = Store(); val journal = DiagnosticJournal(store, maxChars = 3000)
        for (i in 1L..10L) journal.append(event(i).copy(reason = "x".repeat(1800)))
        assertTrue(store.text!!.length <= 3000)
        assertEquals(10L, journal.read().last().timestamp)
        store.text = "broken"
        assertThrows(Exception::class.java) { DiagnosticJournal(store).read() }
    }
    @Test fun legacyScenarioNamesAreRemovedFromRestoredAndPersistedHistory() {
        val store = Store()
        val oldEvent = event(1).copy(stage = "CONFIG_STORE", reason = "最近配置：Home address rev=42")
        store.text = Json.encodeToString(ListSerializer(DiagnosticEvent.serializer()), listOf(oldEvent))

        val restored = DiagnosticJournal(store).read().single()

        assertEquals("最近配置已恢复", restored.reason)
        assertFalse(store.text!!.contains("Home address"))
        assertTrue(store.text!!.contains("最近配置已恢复"))
    }
    @Test fun environmentIdentifiersAreRedactedBeforeDiagnosticPersistence() {
        val store = Store()
        val journal = DiagnosticJournal(store)

        journal.append(event(1).copy(reason =
            "ssid=Office Guest bssid=AA:BB:CC:DD:EE:FF cellId=12345 api_key=SecretKey lat=31.2 lon=121.5"))

        for (privateValue in listOf("Office Guest", "AA:BB:CC:DD:EE:FF", "cellId=12345", "SecretKey", "31.2", "121.5")) {
            assertFalse("Persisted diagnostic leaked $privateValue", store.text!!.contains(privateValue))
        }
        assertTrue(DiagnosticJournal(store).read().single().reason.contains("ssid=<redacted>"))
    }
    @Test fun healthTracksRealGapsAndLatencyAndStorageFailureDoesNotStopPlayback() = runBlocking {
        var nanos = 0L
        val backend = object : LocationBackend {
            override val type = BackendType.MOCK_PROVIDER
            override val capabilities = emptyMap<Capability, CapabilityStatus>()
            override suspend fun prepare() = BackendResult.Success
            override suspend fun start() = BackendResult.Success
            override suspend fun publish(sample: LocationSample, scenarioId: String?): BackendResult { nanos += 20_000_000; return BackendResult.Success }
            override suspend fun stop() = BackendResult.Success
            override suspend fun release() = BackendResult.Success
        }
        val controller = ScenarioController({ backend }, object : RuntimeClock {
            override fun nanos() = nanos
            override fun millis() = nanos / 1_000_000
        }, { error("disk full") })
        assertTrue(controller.start(Scenario("s", "s", point = Wgs84(1.0, 2.0))))
        nanos = 5_000_000_000; assertTrue(controller.tick())
        assertEquals(5000.0, controller.state.value.submissionHealth.maxGapMs, 0.0)
        assertEquals(20.0, controller.state.value.submissionHealth.lastLatencyMs, 0.0)
        assertEquals(1L, controller.state.value.submissionHealth.delayedCount)
        assertEquals(1, controller.diagnostics.value.count { it.stage == "DIAGNOSTIC_STORAGE" })
        assertEquals(RuntimePhase.RUNNING, controller.state.value.phase)
    }
}
