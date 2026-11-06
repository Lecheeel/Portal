package com.system.location.service.hook.transport

import android.os.SharedMemory
import com.system.location.service.core.location.LocationSample
import com.system.location.service.core.runtime.SharedSampleHeader
import com.system.location.service.core.runtime.SharedSampleLayout
import java.io.Closeable
import java.nio.ByteBuffer

/**
 * P5-06: Android SharedMemory adapter for the contiguous header/two-slot layout.
 *
 * This class only owns the mapping. Binder fd transfer and the system_server-side mapping are not
 * wired yet; creation/mmap must be validated on a device before enabling the experimental path.
 */
class SharedMemorySampleBuffer private constructor(
    private val memory: SharedMemory,
    private val mapped: ByteBuffer,
) : Closeable {
    val size: Int get() = mapped.capacity()

    fun write(sample: LocationSample, sequence: Long, elapsedNanos: Long): SharedSampleHeader {
        val bytes = ByteArray(SharedSampleLayout.SIZE)
        val header = SharedSampleLayout.write(bytes, sample, sequence, elapsedNanos)
        mapped.position(0)
        mapped.put(bytes)
        return header
    }

    fun read(): Pair<SharedSampleHeader, LocationSample>? {
        val bytes = ByteArray(SharedSampleLayout.SIZE)
        mapped.position(0)
        mapped.get(bytes)
        return SharedSampleLayout.read(bytes)
    }

    override fun close() {
        runCatching { SharedMemory.unmap(mapped) }
        runCatching { memory.close() }
    }

    companion object {
        fun create(name: String = "portal-sample"): SharedMemorySampleBuffer {
            val memory = SharedMemory.create(name, SharedSampleLayout.SIZE)
            val mapped = memory.mapReadWrite()
            mapped.order(java.nio.ByteOrder.LITTLE_ENDIAN)
            return SharedMemorySampleBuffer(memory, mapped)
        }
    }
}
