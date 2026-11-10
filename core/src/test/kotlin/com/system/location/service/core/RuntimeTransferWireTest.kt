package com.system.location.service.core

import com.system.location.service.core.environment.*
import com.system.location.service.core.geo.Wgs84
import com.system.location.service.core.gnss.*
import com.system.location.service.core.location.LocationSample
import com.system.location.service.core.routing.*
import com.system.location.service.core.runtime.*
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream
import org.junit.Assert.*
import org.junit.Test

class RuntimeTransferWireTest {
    private val point = Wgs84(31.0, 121.0)
    private val sample = LocationSample(point, 10.0, 5f, 2f, 90f, 62000, 1_000_000_000)
    private fun source(): CapturedGnssSource = CapturedGnssSource.prepare(GnssCaptureWire.encode(GnssCapture(
        deviceModel = "receiver", osVersion = "os", startedAtMillis = 1000, endedAtMillis = 61000,
        frames = (0..60).map { GnssObservationFrame(1000 + it * 1000L, point,
            satellites = listOf(GnssSatellite(Constellation.GPS, 1, 30f, 120f, 35f, true, true, true))) })))!!

    @Test fun fullStateRoundTripRetainsConfigurationRoutingEnvironmentAndStatusEvidence() {
        val original = RuntimeSnapshot(configRevision = 12, environmentVersion = 5, sample = sample,
            latitude = 31.0, longitude = 121.0, altitudeMeters = 10.0, speedMps = 2.0, accuracyMeters = 5f,
            routing = RoutingConfig(mode = RoutingMode.PER_APP, routes = listOf(AppRoute("app", point = point))),
            environment = EnvironmentProfile(EnvironmentSourceType.REPLAY, generatedAtMillis = 1000, seed = 42),
            environmentSeed = 99, environmentApCount = 7, cellMode = CellMode.AUTO,
            environmentSourceMode = EnvironmentSourceMode.REPLAY, physicalNoise = true, physicalNoiseSeed = 123,
            simReady = false, bearingDeg = 90.0, hasBearings = true,
            configuration = HookConfiguration(enabled = true, nmeaEnabled = false, minimumSatellites = 24,
                allowPhysicalRawGnss = true, reportIntervalMs = 200, sensorCadenceScale = 1.5, gnssSource = source()))
        val restored = RuntimeTransferWire.decode(RuntimeTransferWire.encode(RuntimeTransfer(original, true, mapOf("route" to sample))))
        assertEquals(original.copy(configuration = original.configuration.copy(gnssSource = null)),
            restored.state.copy(configuration = restored.state.configuration.copy(gnssSource = null)))
        assertTrue(restored.externallyDriven)
        assertEquals(mapOf("route" to sample), restored.scenarios)
        val before = original.configuration.gnssSource!!.frame(sample, 24, GnssFrameRoute.Global, 62000)!!
        val after = restored.state.configuration.gnssSource!!.frame(sample, 24, GnssFrameRoute.Global, 62000)!!
        assertEquals(before.frame, after.frame)
        assertTrue(after.sequence > before.sequence)
        assertNotSame(original.configuration.gnssSource, restored.state.configuration.gnssSource)
    }

    @Test fun restorationCanReuseEvidenceWithoutMutatingPreviouslyPublishedSource() {
        val source = source()
        val state = RuntimeSnapshot(configuration = HookConfiguration(gnssSource = source))
        val restored = RuntimeTransferWire.decode(RuntimeTransferWire.encode(RuntimeTransfer(state, false)), source)
        assertSame(source, restored.state.configuration.gnssSource)
    }

    @Test fun delayNeverRenewsAStaleExternalSampleOrScenario() {
        val state = RuntimeSnapshot(sample = sample, hasBearings = true, configuration = HookConfiguration(enabled = true))
        val transfer = RuntimeTransfer(state, true, mapOf("route" to sample))
        assertTrue(transfer.atReception(sample.elapsedNanos).externallyDriven)
        for (now in listOf(sample.elapsedNanos - 1, sample.elapsedNanos + 5_000_000_000)) {
            val result = transfer.atReception(now)
            assertFalse(result.externallyDriven)
            assertFalse(result.state.configuration.enabled)
            assertFalse(result.state.hasBearings)
            assertNull(result.state.sample)
            assertTrue(result.scenarios.isEmpty())
        }
    }

    @Test fun malformedUnknownVersionAndOversizedPayloadsCannotBeDecoded() {
        assertThrows(Exception::class.java) { RuntimeTransferWire.decode(byteArrayOf(1, 2, 3)) }
        assertThrows(IllegalArgumentException::class.java) { RuntimeTransferWire.decode(ByteArray(RuntimeTransferWire.MAX_COMPRESSED_BYTES + 1)) }
        fun compressed(raw: String): ByteArray = ByteArrayOutputStream().also { output ->
            GZIPOutputStream(output).use { it.write(raw.toByteArray()) }
        }.toByteArray()
        assertThrows(IllegalArgumentException::class.java) {
            RuntimeTransferWire.decode(compressed(" ".repeat(RuntimeTransferWire.MAX_DECODED_BYTES + 1)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            RuntimeTransferWire.decode(compressed("""{"version":2,"state":{},"externallyDriven":false,"scenarios":{},"gnssCapture":null}"""))
        }
        assertThrows(IllegalArgumentException::class.java) { RuntimeTransfer(RuntimeSnapshot(sample = sample), false) }
        assertThrows(IllegalArgumentException::class.java) { RuntimeTransfer(RuntimeSnapshot(), true) }
    }
}
