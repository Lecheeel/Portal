package com.system.location.service.core.gnss

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/** Bounded Binder payload. Raw evidence stays local; validated compact GPS evidence can cross IPC. */
object GnssCaptureWire {
    const val MAX_ENCODED_LENGTH = 262_144

    fun encode(capture: GnssCapture): String {
        // Fresh raw input always supersedes compact input, including when it invalidates it.
        // Peers may re-encode an already compact snapshot without losing its evidence.
        val evidence = if (capture.measurements.isNotEmpty() || capture.navigation.isNotEmpty())
            GpsBroadcastEvidence.fromCapture(capture) else capture.gpsBroadcastEvidence
        // Legacy projected-only captures already reproduce their fit from the retained frames.
        // Preserve explicit empty fits from raw signals too: empty is not permission to refit L5.
        val calibration = if (capture.gpsSignalCalibration != null || capture.frames.any { it.signalObservations.isNotEmpty() })
            GpsEmpiricalSignalModel(capture).calibration else null
        val compact = capture.copy(measurements = emptyList(), navigation = emptyList(),
            gpsBroadcastEvidence = evidence,
            gpsSignalCalibration = calibration,
            frames = capture.frames.map { it.copy(signalObservations = emptyList()) })
        val data = GnssCaptureCodec.encode(compact).toByteArray(Charsets.UTF_8)
        require(data.size <= GnssCaptureCodec.MAX_BYTES) { "GNSS compact capture too large" }
        val compressed = ByteArrayOutputStream()
        GZIPOutputStream(compressed).use { it.write(data) }
        val encoded = Base64.getEncoder().encodeToString(compressed.toByteArray())
        require(encoded.length <= MAX_ENCODED_LENGTH) { "GNSS capture exceeds Binder budget" }
        return encoded
    }

    fun decode(encoded: String): GnssCapture {
        require(encoded.length <= MAX_ENCODED_LENGTH) { "GNSS capture exceeds Binder budget" }
        val compressed = Base64.getDecoder().decode(encoded)
        val output = ByteArrayOutputStream()
        GZIPInputStream(ByteArrayInputStream(compressed)).use { input ->
            val chunk = ByteArray(8192)
            while (true) {
                val count = input.read(chunk)
                if (count < 0) break
                require(output.size() + count <= GnssCaptureCodec.MAX_BYTES) { "GNSS decompression limit exceeded" }
                output.write(chunk, 0, count)
            }
        }
        return GnssCaptureCodec.decode(output.toString(Charsets.UTF_8.name()))
    }
}
