package com.system.location.service.core.runtime

import com.system.location.service.core.geo.Wgs84
import com.system.location.service.core.location.LocationSample
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32

/**
 * P5-06: fixed-size sample slot codec for the proposed shared-memory transport.
 *
 * Layout (little endian, 64 bytes):
 * magic i32 | version i16 | flags i16 | lat f64 | lon f64 | altitude f64 |
 * accuracy f32 | speed f32 | bearing f32 | timeMillis i64 | elapsedNanos i64 | crc32 i32
 *
 * The CRC covers the first 60 bytes. Decoders reject wrong magic/version/size/CRC and invalid
 * coordinate ranges; the shared-memory writer must still publish via the seqlock sequence.
 */
object SharedSampleCodec {
    const val MAGIC = 0x504F5254 // "PORT"
    const val VERSION = 1
    const val SLOT_BYTES = 64

    private const val CRC_OFFSET = 60

    fun encode(sample: LocationSample): ByteArray {
        val buffer = ByteBuffer.allocate(SLOT_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(MAGIC)
        buffer.putShort(VERSION.toShort())
        buffer.putShort(0)
        buffer.putDouble(sample.latitude)
        buffer.putDouble(sample.longitude)
        buffer.putDouble(sample.altitude)
        buffer.putFloat(sample.accuracy)
        buffer.putFloat(sample.speed)
        buffer.putFloat(sample.bearing)
        buffer.putLong(sample.timeMillis)
        buffer.putLong(sample.elapsedNanos)
        val bytes = buffer.array()
        val crc = CRC32().apply { update(bytes, 0, CRC_OFFSET) }.value.toInt()
        ByteBuffer.wrap(bytes, CRC_OFFSET, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(crc)
        return bytes
    }

    fun decode(bytes: ByteArray): LocationSample {
        require(bytes.size == SLOT_BYTES) { "invalid slot size" }
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        require(buffer.int == MAGIC) { "invalid slot magic" }
        require(buffer.short.toInt() == VERSION) { "unsupported slot version" }
        buffer.short
        val lat = buffer.double
        val lon = buffer.double
        val altitude = buffer.double
        val accuracy = buffer.float
        val speed = buffer.float
        val bearing = buffer.float
        val timeMillis = buffer.long
        val elapsedNanos = buffer.long
        val expectedCrc = buffer.int
        val actualCrc = CRC32().apply { update(bytes, 0, CRC_OFFSET) }.value.toInt()
        require(expectedCrc == actualCrc) { "slot crc mismatch" }
        require(lat.isFinite() && lat in -90.0..90.0 && lon.isFinite() && lon in -180.0..180.0) {
            "invalid slot coordinate"
        }
        require(altitude.isFinite()) { "invalid slot altitude" }
        require(accuracy.isFinite() && accuracy > 0f) { "invalid slot accuracy" }
        require(speed.isFinite() && speed >= 0f) { "invalid slot speed" }
        require(bearing.isFinite()) { "invalid slot bearing" }
        require(elapsedNanos >= 0L) { "invalid slot elapsedNanos" }
        return LocationSample(Wgs84(lat, lon), altitude, accuracy, speed, bearing, timeMillis, elapsedNanos)
    }
}
