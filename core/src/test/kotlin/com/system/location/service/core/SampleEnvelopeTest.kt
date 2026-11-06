package com.system.location.service.core

import com.system.location.service.core.config.ConfigCodec
import com.system.location.service.core.config.ConfigException
import com.system.location.service.core.config.SampleCodec
import com.system.location.service.core.config.SampleEnvelope
import com.system.location.service.core.geo.Wgs84
import com.system.location.service.core.location.LocationSample
import com.system.location.service.core.providers.ProviderKind
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class SampleEnvelopeTest {
    private val sample = LocationSample(
        coordinate = Wgs84(31.2304, 121.4737),
        altitude = 80.0,
        accuracy = 6f,
        speed = 5f,
        bearing = 90f,
        timeMillis = 1_790_000_000_000,
        elapsedNanos = 1_000_000_000,
    )

    @Test
    fun v2RoundTripsWithMetadata() {
        val envelope = SampleEnvelope(
            sample = sample,
            provider = ProviderKind.GPS,
            fieldsMask = 0b1010,
            environmentVersion = 9,
            configRevision = 4,
        )
        assertEquals(envelope, SampleCodec.decode(SampleCodec.encode(envelope)))
    }

    @Test
    fun rawV1SampleIsWrappedWithDefaults() {
        val raw = Json.encodeToString(LocationSample.serializer(), sample)
        val decoded = SampleCodec.decode(raw)
        assertEquals(SampleEnvelope.CURRENT_SCHEMA_VERSION, decoded.schemaVersion)
        assertEquals(sample, decoded.sample)
        assertNull(decoded.provider)
        assertEquals(0, decoded.fieldsMask)
    }

    @Test
    fun explicitV1SampleIsAccepted() {
        val raw = """{"schemaVersion":1,"coordinate":{"latitude":31.0,"longitude":121.0},"altitude":1.0,"accuracy":5.0,"speed":0.0,"bearing":0.0,"timeMillis":1,"elapsedNanos":1}"""
        val decoded = SampleCodec.decode(raw)
        assertEquals(31.0, decoded.sample.latitude, 0.0)
        assertEquals(5f, decoded.sample.accuracy, 0f)
    }

    @Test
    fun futureVersionAndInvalidJsonAreRejected() {
        assertThrows(ConfigException::class.java) { SampleCodec.decode("""{"schemaVersion":99}""") }
        assertThrows(ConfigException::class.java) { SampleCodec.decode("not-json") }
    }

    @Test
    fun invalidEnvelopeIsRejected() {
        assertThrows(IllegalArgumentException::class.java) { SampleEnvelope(sample = sample, fieldsMask = -1) }
        assertThrows(IllegalArgumentException::class.java) { SampleEnvelope(sample = sample, environmentVersion = -1) }
        assertThrows(IllegalArgumentException::class.java) { SampleEnvelope(sample = sample, configRevision = -1) }
    }

    @Test
    fun envelopeUsesV2VersionField() {
        val encoded = SampleCodec.encode(SampleEnvelope(sample = sample))
        assertEquals(2, Json.parseToJsonElement(encoded).jsonObject[ConfigCodec.VERSION_FIELD]?.toString()?.toInt())
    }
}
