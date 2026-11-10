package com.system.location.service.detector.model

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream

/** User-selected local evidence; the user's source assertion is not an authenticity proof. */
data class GnssReference(
    val deviceFingerprint: String,
    val environment: String,
    val savedAtMillis: Long,
    val evidence: GnssEvidenceSnapshot,
) {
    fun profile(): GnssWindowProfile = GnssWindowProfile.from(evidence)
}

/** Bounded versioned format. Recompute statistics from observations rather than trusting totals. */
object GnssReferenceCodec {
    const val MAX_BYTES = 40 * 1024 * 1024
    private const val MAGIC = 0x474E5352

    fun write(reference: GnssReference, stream: OutputStream) {
        validate(reference)
        val output = DataOutputStream(stream)
        output.writeInt(MAGIC)
        output.writeInt(1)
        output.writeUTF(reference.deviceFingerprint)
        output.writeUTF(reference.environment)
        output.writeLong(reference.savedAtMillis)
        output.writeLong(reference.evidence.startedAtMillis)
        output.writeLong(reference.evidence.endedAtMillis)
        output.writeInt(reference.evidence.frames.size)
        reference.evidence.frames.forEach { frame ->
            output.writeLong(frame.capturedAtMillis)
            output.writeInt(frame.satellites.size)
            frame.satellites.forEach { s ->
                output.writeInt(s.constellation)
                output.writeInt(s.svid)
                output.writeFloat(s.cn0DbHz)
                output.writeFloat(s.elevationDegrees)
                output.writeFloat(s.azimuthDegrees)
                output.writeBoolean(s.usedInFix)
                output.writeBoolean(s.hasEphemeris)
                output.writeBoolean(s.hasAlmanac)
                output.writeBoolean(s.hasCarrierFrequency)
            }
        }
        output.flush()
    }

    fun read(stream: InputStream): GnssReference {
        val input = DataInputStream(stream)
        require(input.readInt() == MAGIC && input.readInt() == 1) { "参考窗口格式不支持" }
        val device = input.readUTF()
        val environment = input.readUTF()
        val savedAt = input.readLong()
        val start = input.readLong()
        val end = input.readLong()
        val count = input.readInt()
        require(count in 1..3000)
        val frames = List(count) {
            val time = input.readLong()
            val satellites = input.readInt()
            require(satellites in 0..512)
            GnssFrameSnapshot(time, List(satellites) {
                GnssSatelliteSnapshot(input.readInt(), input.readInt(), input.readFloat(), input.readFloat(),
                    input.readFloat(), input.readBoolean(), input.readBoolean(), input.readBoolean(), input.readBoolean())
            })
        }
        require(input.read() == -1) { "参考窗口含多余数据" }
        return GnssReference(device, environment, savedAt, GnssEvidenceSnapshot(start, end, frames)).also(::validate)
    }

    private fun validate(reference: GnssReference) {
        require(reference.deviceFingerprint.isNotBlank() && reference.deviceFingerprint.length <= 1024)
        require(reference.environment.isNotBlank() && reference.environment.length <= 120)
        require(reference.savedAtMillis >= reference.evidence.endedAtMillis)
        require(reference.profile().adequate) { "参考窗口需至少 20 个接收秒、跨度 30 秒、间隔不超过 2 秒且存在可见卫星" }
    }
}
