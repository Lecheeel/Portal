package com.system.location.service.core

import com.system.location.service.core.geo.Wgs84
import com.system.location.service.core.gnss.*
import com.system.location.service.core.location.LocationSample
import com.system.location.service.core.runtime.*
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class HookConfigurationTest {
    private val point = Wgs84(31.0, 121.0)
    private val sample = LocationSample(point, 10.0, 5f, 0f, 0f, 62000, 1000)
    private fun payload(svid: Int): String = GnssCaptureWire.encode(GnssCapture(deviceModel = "receiver", osVersion = "os",
        startedAtMillis = 1000, endedAtMillis = 61000,
        frames = (0..60).map { GnssObservationFrame(1000 + it * 1000L, point,
            satellites = listOf(GnssSatellite(Constellation.GPS, svid, 30f, 120f, 35f, true, true, true))) }))

    @Test fun replacingConfigurationKeepsOldSourceAndDistinctMonotonicPublications() {
        val firstPayload = payload(1)
        val firstSource = CapturedGnssSource.prepare(firstPayload)!!
        val original = RuntimeSnapshot(configuration = HookConfiguration(enabled = true, gnssSource = firstSource))
        val store = RuntimeSnapshotStore(original)
        val before = firstSource.frame(sample, 0, GnssFrameRoute.Global, 62000)!!
        val secondSource = CapturedGnssSource.prepare(payload(2), firstSource)!!
        store.update { it.copy(configuration = it.configuration.copy(gnssSource = secondSource, minimumSatellites = 8)) }
        val after = secondSource.frame(sample, 8, GnssFrameRoute.Global, 62000)!!
        assertEquals(1, before.frame.satellites.single().svid)
        assertEquals(2, after.frame.satellites.single().svid)
        assertTrue(after.sequence > before.sequence)
        assertSame(before, original.configuration.gnssSource!!.frame(sample, 0, GnssFrameRoute.Global, 62000))
        assertSame(firstSource, CapturedGnssSource.prepare(firstPayload, firstSource))
        assertThrows(IllegalArgumentException::class.java) { CapturedGnssSource.prepare("!!", secondSource) }
        assertSame(secondSource, store.current().configuration.gnssSource)
    }

    @Test fun sourceEvidenceAndPublicationCacheCannotEnterSerializedRuntimeState() {
        val source = CapturedGnssSource.prepare(payload(1))!!
        val state = RuntimeSnapshot(configuration = HookConfiguration(enabled = true, minimumSatellites = 7, gnssSource = source))
        val json = Json { encodeDefaults = true }
        val encoded = json.encodeToString(RuntimeSnapshot.serializer(), state)
        assertFalse(encoded.contains("gnssSource"))
        assertFalse(encoded.contains("receiver"))
        val restored = json.decodeFromString(RuntimeSnapshot.serializer(), encoded)
        assertTrue(restored.configuration.enabled)
        assertEquals(7, restored.configuration.minimumSatellites)
        assertNull(restored.configuration.gnssSource)
    }

    @Test fun boundsCannotBeBypassedByAnAtomicConfigurationCopy() {
        assertThrows(IllegalArgumentException::class.java) { HookConfiguration(minimumSatellites = -1) }
        assertThrows(IllegalArgumentException::class.java) { HookConfiguration(minimumSatellites = 129) }
        assertThrows(IllegalArgumentException::class.java) { HookConfiguration(reportIntervalMs = 49) }
        assertThrows(IllegalArgumentException::class.java) { HookConfiguration(sensorCadenceScale = Double.NaN) }
        assertThrows(IllegalArgumentException::class.java) { HookConfiguration(sensorVariation = 0.6) }
        for (amplitude in listOf(Double.NaN, Double.POSITIVE_INFINITY, -1.0, 10.1)) {
            assertThrows(IllegalArgumentException::class.java) { HookConfiguration(speedAmplitude = amplitude) }
        }
    }
}
