package com.system.location.service.core

import com.system.location.service.core.geo.Wgs84
import com.system.location.service.core.location.LocationSample
import com.system.location.service.core.runtime.SampleStore
import com.system.location.service.core.runtime.SharedSampleBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SharedSampleBufferTest {
    private fun sample(sequence: Long): LocationSample {
        val coordinate = (sequence % 180L) - 90.0
        return LocationSample(Wgs84(coordinate, coordinate), 80.0, 5f, 1f, 0f, sequence, sequence)
    }

    @Test fun publishesDecodedSamples() {
        val buffer = SharedSampleBuffer()
        assertNull(buffer.latest(0))
        assertEquals(1L, buffer.publish(sample(1), 100))
        val entry = buffer.latest(100)!!
        assertEquals(1L, entry.sequence)
        assertEquals(entry.sample.latitude, entry.sample.longitude, 0.0)
        assertEquals(2L, buffer.publish(sample(2), 200))
        assertEquals(2L, buffer.latest(200)!!.sequence)
    }

    @Test fun leaseAndClear() {
        val buffer = SharedSampleBuffer()
        buffer.publish(sample(1), 100)
        assertNull(buffer.latest(99))
        assertEquals(1L, buffer.latest(100 + SampleStore.DEFAULT_MAX_AGE_NANOS)!!.sequence)
        assertNull(buffer.latest(101 + SampleStore.DEFAULT_MAX_AGE_NANOS))
        buffer.clear()
        assertNull(buffer.latest(100))
    }

    @Test fun concurrentReadersNeverSeeTornSample() {
        val buffer = SharedSampleBuffer()
        val failure = AtomicReference<String?>(null)
        val start = CountDownLatch(1)
        val reader = Thread {
            start.await()
            repeat(20_000) {
                val entry = buffer.latest(Long.MAX_VALUE) ?: return@repeat
                if (entry.sample.latitude != entry.sample.longitude) {
                    failure.set("torn sample at ${entry.sequence}")
                }
            }
        }
        reader.start()
        start.countDown()
        repeat(5_000) { buffer.publish(sample(it.toLong()), it.toLong()) }
        reader.join()
        assertNull(failure.get())
    }
}
