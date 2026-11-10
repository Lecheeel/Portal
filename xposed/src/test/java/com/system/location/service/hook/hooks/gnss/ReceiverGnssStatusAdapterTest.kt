package com.system.location.service.hook.hooks.gnss

import com.system.location.service.core.geo.Wgs84
import com.system.location.service.core.gnss.*
import org.junit.Assert.*
import org.junit.Test

class ReceiverGnssStatusAdapterTest {
    private val publication = PublishedGnssFrame(1, ReceiverGnssFrame(1790000000000, Wgs84(31.0, 121.0), 10.0,
        listOf(GnssSatellite(Constellation.BEIDOU, 9, 25f, 90f, 33f, true, true, true, 1561e6f)),
        GnssGeometryResult(null), ReceiverGnssSource.STATISTICAL_REPLAY), 0.0, 0.0)

    @Test fun arraySignaturesAreSizedExactlyAndUnobservedFieldsAreNotAdvertised() {
        for (method in Callbacks::class.java.declaredMethods.filter { it.name.startsWith("status") }) {
            assertTrue(ReceiverGnssStatusAdapter.supports(method))
            val args = ReceiverGnssStatusAdapter.arguments(method, publication)
            assertEquals(method.parameterCount, args.size)
            assertEquals(1, args[0])
            val packed = (args[1] as IntArray).single()
            assertEquals(9, packed ushr 12)
            assertEquals(5, (packed ushr 8) and 0xf)
            assertEquals(0, packed and 16)
            assertEquals(15, packed and 15)
            assertArrayEquals(floatArrayOf(33f), args[2] as FloatArray, 0f)
        }
    }

    @Test fun unsupportedSignaturesAreExplicit() {
        val method = Callbacks::class.java.getDeclaredMethod("unsupported", String::class.java)
        assertFalse(ReceiverGnssStatusAdapter.supports(method))
        assertThrows(IllegalArgumentException::class.java) { ReceiverGnssStatusAdapter.arguments(method, publication) }
    }

    @Suppress("UNUSED_PARAMETER")
    class Callbacks {
        fun status5(n: Int, s: IntArray, c: FloatArray, e: FloatArray, a: FloatArray) = Unit
        fun status6(n: Int, s: IntArray, c: FloatArray, e: FloatArray, a: FloatArray, f: FloatArray) = Unit
        fun status7(n: Int, s: IntArray, c: FloatArray, e: FloatArray, a: FloatArray, f: FloatArray, b: FloatArray) = Unit
        fun unsupported(value: String) = Unit
    }
}
