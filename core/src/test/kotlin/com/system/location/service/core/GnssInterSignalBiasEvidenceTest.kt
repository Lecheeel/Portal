package com.system.location.service.core

import com.system.location.service.core.gnss.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class GnssInterSignalBiasEvidenceTest {
    private val measurement = GnssMeasurementObservation(Constellation.GALILEO, 24, 9,
        0.25, 1000000, 10, 35.0, 0.0, 1.0, 0, 0.0, 1.0, 1_575_420_000.0, "C",
        fullInterSignalBiasNanos = -12.5, fullInterSignalBiasUncertaintyNanos = 1.5,
        satelliteInterSignalBiasNanos = 3.25, satelliteInterSignalBiasUncertaintyNanos = 0.5)
    private val batch = GnssMeasurementBatch(1000, 1000000, null, null, 0, listOf(measurement),
        referenceConstellationTypeForIsb = 1, referenceCarrierHzForIsb = 1_575_420_000.0,
        referenceCodeTypeForIsb = "C")
    private fun capture(batch: GnssMeasurementBatch) = GnssCapture(deviceModel = "test", osVersion = "test",
        startedAtMillis = 1000, endedAtMillis = 1000,
        frames = listOf(GnssObservationFrame(1000, satellites = emptyList())), measurements = listOf(batch))

    @Test fun localStorageRetainsSignedBiasesAndExplicitReferenceWhileIpcOmitsRawEvidence() {
        val c = capture(batch)
        assertEquals(c, GnssCaptureCodec.decode(GnssCaptureCodec.encode(c)))
        assertEquals(-12.5, c.measurements.single().measurements.single().fullInterSignalBiasNanos!!, 0.0)
        assertEquals(3.25, c.measurements.single().measurements.single().satelliteInterSignalBiasNanos!!, 0.0)
        assertTrue(GnssCaptureWire.decode(GnssCaptureWire.encode(c)).measurements.isEmpty())
        // Android availability flags are independent: do not silently create a missing bias.
        val partial = capture(batch.copy(measurements = listOf(measurement.copy(fullInterSignalBiasNanos = null))))
        assertEquals(partial, GnssCaptureCodec.decode(GnssCaptureCodec.encode(partial)))
    }

    @Test fun legacyMissingReferenceAndBiasStayUnknownRatherThanBecomingZeroOrGps() {
        val json = Json.parseToJsonElement(GnssCaptureCodec.encode(capture(batch))).jsonObject
        val oldMeasurement = JsonObject(json.getValue("measurements").jsonArray[0].jsonObject
            .getValue("measurements").jsonArray[0].jsonObject.filterKeys { !it.contains("InterSignalBias") })
        val oldBatch = JsonObject(json.getValue("measurements").jsonArray[0].jsonObject
            .filterKeys { !it.startsWith("reference") } + ("measurements" to JsonArray(listOf(oldMeasurement))))
        val legacy = GnssCaptureCodec.decode(JsonObject(json + ("measurements" to JsonArray(listOf(oldBatch)))).toString())
        val b = legacy.measurements.single()
        assertNull(b.referenceConstellationTypeForIsb)
        assertNull(b.referenceCarrierHzForIsb)
        assertNull(b.referenceCodeTypeForIsb)
        assertNull(b.measurements.single().fullInterSignalBiasNanos)
        assertNull(b.measurements.single().satelliteInterSignalBiasNanos)
    }

    @Test fun invalidReportedValuesCannotMasqueradeAsValidBiasEvidence() {
        assertThrows(IllegalArgumentException::class.java) { measurement.copy(fullInterSignalBiasNanos = Double.NaN) }
        assertThrows(IllegalArgumentException::class.java) { measurement.copy(satelliteInterSignalBiasNanos = Double.POSITIVE_INFINITY) }
        assertThrows(IllegalArgumentException::class.java) { measurement.copy(fullInterSignalBiasUncertaintyNanos = -1.0) }
        assertThrows(IllegalArgumentException::class.java) { measurement.copy(satelliteInterSignalBiasUncertaintyNanos = Double.NaN) }
        assertThrows(IllegalArgumentException::class.java) { batch.copy(referenceCarrierHzForIsb = 0.0) }
        assertThrows(IllegalArgumentException::class.java) { batch.copy(referenceConstellationTypeForIsb = -1) }
    }
}
