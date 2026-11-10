package com.system.location.service.core.runtime

import com.system.location.service.core.environment.WifiFingerprintImporter
import com.system.location.service.core.gnss.CapturedGnssSource
import com.system.location.service.core.location.LocationSample
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Prepared full state, not a partial patch. Only authenticated initialization may restore it. */
data class RuntimeTransfer(
    val state: RuntimeSnapshot,
    val externallyDriven: Boolean,
    val scenarios: Map<String, LocationSample> = emptyMap(),
) {
    init {
        require(scenarios.size <= ScenarioSampleStore.MAX_SCENARIOS)
        require(scenarios.keys.all { it.isNotBlank() && it.length <= ScenarioSampleStore.MAX_ID_LENGTH })
        require(if (externallyDriven) state.configuration.enabled && state.sample != null else state.sample == null)
    }

    /** IPC latency does not renew samples. All peers use the original monotonic sample deadline. */
    fun atReception(nowNanos: Long, maxAgeNanos: Long = SampleStore.DEFAULT_MAX_AGE_NANOS): RuntimeTransfer {
        require(nowNanos >= 0L && maxAgeNanos > 0L)
        fun fresh(sample: LocationSample) = sample.elapsedNanos <= nowNanos && nowNanos - sample.elapsedNanos < maxAgeNanos
        val active = externallyDriven && fresh(state.sample!!)
        val received = if (externallyDriven && !active) state.copy(sample = null, provider = null,
            configuration = state.configuration.copy(enabled = false), hasBearings = false) else state
        return RuntimeTransfer(received, active, scenarios.filterValues(::fresh))
    }
}

/** Versioned bounded binary IPC; neither publication caches nor raw GNSS observations are sent. */
object RuntimeTransferWire {
    const val MAX_COMPRESSED_BYTES = 262_144
    const val MAX_DECODED_BYTES = 2_097_152
    private const val VERSION = 1
    private val json = Json { encodeDefaults = true }

    @Serializable
    private data class Envelope(
        val version: Int,
        val state: RuntimeSnapshot,
        val externallyDriven: Boolean,
        val scenarios: Map<String, LocationSample>,
        val gnssCapture: String?,
    )

    fun encode(transfer: RuntimeTransfer): ByteArray {
        val envelope = Envelope(VERSION, transfer.state, transfer.externallyDriven, transfer.scenarios,
            transfer.state.configuration.gnssSource?.wirePayload())
        val raw = json.encodeToString(Envelope.serializer(), envelope).toByteArray(Charsets.UTF_8)
        require(raw.size <= MAX_DECODED_BYTES) { "Runtime transfer exceeds decoded budget" }
        val output = ByteArrayOutputStream()
        GZIPOutputStream(output).use { it.write(raw) }
        return output.toByteArray().also {
            require(it.size <= MAX_COMPRESSED_BYTES) { "Runtime transfer exceeds IPC budget" }
        }
    }

    fun decode(encoded: ByteArray, previousSource: CapturedGnssSource? = null): RuntimeTransfer {
        require(encoded.isNotEmpty() && encoded.size <= MAX_COMPRESSED_BYTES) { "Invalid runtime transfer size" }
        val output = ByteArrayOutputStream()
        GZIPInputStream(ByteArrayInputStream(encoded)).use { input ->
            val chunk = ByteArray(8192)
            while (true) {
                val count = input.read(chunk)
                if (count < 0) break
                require(output.size() + count <= MAX_DECODED_BYTES) { "Runtime transfer decompression limit" }
                output.write(chunk, 0, count)
            }
        }
        val envelope = json.decodeFromString(Envelope.serializer(), output.toString(Charsets.UTF_8.name()))
        require(envelope.version == VERSION) { "Unsupported runtime transfer version" }
        envelope.state.configuration.wifiFingerprintCatalogJson?.let { WifiFingerprintImporter.importJson(it) }
        val source = CapturedGnssSource.prepare(envelope.gnssCapture, previousSource)
        val state = envelope.state.copy(configuration = envelope.state.configuration.copy(gnssSource = source))
        return RuntimeTransfer(state, envelope.externallyDriven, envelope.scenarios.toMap())
    }
}
