package com.system.location.service.hook.hooks

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CurrentLocationRequestsTest {
    @Test
    fun callbacksOfTheSameClassKeepTheirOwnProviders() {
        val requests = CurrentLocationRequests()
        val gps = Callback()
        val network = Callback()
        requests.register(gps, "gps")
        requests.register(network, "network")

        assertEquals("network", requests.take(network))
        assertEquals("gps", requests.take(gps))
    }

    @Test
    fun callbackEqualityDoesNotMergeSeparateRequests() {
        val requests = CurrentLocationRequests()
        val first = EqualCallback()
        val second = EqualCallback()
        assertEquals(first, second)
        requests.register(first, "gps")
        requests.register(second, "fused")

        assertEquals("gps", requests.take(first))
        assertEquals("fused", requests.take(second))
    }

    @Test
    fun providerIsConsumedOnceAndUnknownCallbackHasNoProvider() {
        val requests = CurrentLocationRequests()
        val callback = Callback()
        requests.register(callback, "gps")

        assertNull(requests.take(Callback()))
        assertEquals("gps", requests.take(callback))
        assertNull(requests.take(callback))
    }

    @Test
    fun registeringTheSameObjectReplacesItsPreviousRequest() {
        val requests = CurrentLocationRequests()
        val callback = Callback()
        requests.register(callback, "gps")
        requests.register(callback, "network")

        assertEquals("network", requests.take(callback))
        assertNull(requests.take(callback))
    }

    @Test
    fun failedRegistrationCanBeRemovedWithoutAffectingOtherCallbacks() {
        val requests = CurrentLocationRequests()
        val failed = EqualCallback()
        val retained = EqualCallback()
        requests.register(failed, "gps")
        requests.register(retained, "fused")

        requests.remove(failed)
        requests.remove(failed)

        assertNull(requests.take(failed))
        assertEquals("fused", requests.take(retained))
    }

    @Test
    fun racingDeliveriesConsumeTheRequestOnlyOnce() {
        val requests = CurrentLocationRequests()
        val callback = Callback()
        requests.register(callback, "gps")
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(8)
        try {
            val deliveries = (1..16).map {
                pool.submit<String?> {
                    assertTrue(start.await(5, TimeUnit.SECONDS))
                    requests.take(callback)
                }
            }
            start.countDown()

            val results = deliveries.map { it.get(5, TimeUnit.SECONDS) }
            assertEquals(listOf("gps"), results.filterNotNull())
            assertNull(requests.take(callback))
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun concurrentDistinctCallbacksDoNotLoseAssociations() {
        val requests = CurrentLocationRequests()
        val callbacks = List(32) { EqualCallback() }
        val registered = CountDownLatch(callbacks.size)
        val pool = Executors.newFixedThreadPool(8)
        try {
            val registrations = callbacks.mapIndexed { index, callback ->
                pool.submit {
                    requests.register(callback, "provider-$index")
                    registered.countDown()
                }
            }
            assertTrue(registered.await(5, TimeUnit.SECONDS))
            registrations.forEach { it.get(5, TimeUnit.SECONDS) }

            val deliveries = callbacks.mapIndexed { index, callback ->
                pool.submit {
                    assertEquals("provider-$index", requests.take(callback))
                }
            }
            deliveries.forEach { it.get(5, TimeUnit.SECONDS) }
            callbacks.forEach { assertNull(requests.take(it)) }
        } finally {
            pool.shutdownNow()
        }
    }

    private class Callback

    private class EqualCallback {
        override fun equals(other: Any?): Boolean = other is EqualCallback
        override fun hashCode(): Int = 1
    }
}
