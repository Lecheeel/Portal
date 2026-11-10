package com.system.location.service.hook.hooks.gnss

import android.os.IBinder
import android.os.IInterface
import com.system.location.service.hook.runtime.DeliveryWorker
import java.lang.reflect.Proxy
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test

class GnssBatchDeliveryTest {
    @Test fun policyStopAndReloadResumeOnlyTheUnsentSuffix() {
        val listener = listener()
        val old = GnssCallbackRegistry()
        old.register(listener, "app", 10001)
        val sent = mutableListOf<Int>()
        var allowed = true
        old.deliverBatch(listener, "exact batch", 4, { allowed }) {
            sent += it
            allowed = false
        }
        assertEquals(listOf(0), sent)
        val saved = old.subscriptions().single()
        assertEquals(1, saved.nextDeliveryIndex)
        assertNull(saved.lastDeliveryKey)
        old.clear()
        val successor = GnssCallbackRegistry()
        assertTrue(successor.restore(saved))
        successor.deliverBatch(listener, "exact batch", 4, { true }) { sent += it }
        repeat(3) { successor.deliverBatch(listener, "exact batch", 4, { true }) { fail("Repeated completed batch") } }
        assertEquals(listOf(0, 1, 2, 3), sent)
        assertNull(successor.subscriptions().single().pendingDeliveryKey)
        assertEquals(0, successor.subscriptions().single().nextDeliveryIndex)
        successor.clear()
    }

    @Test fun failedSubmissionRetriesItsItemWithoutRepeatingTheSuccessfulPrefix() {
        val listener = listener()
        val registry = GnssCallbackRegistry()
        registry.register(listener, "app", 10001)
        val sent = mutableListOf<Int>()
        assertThrows(IllegalStateException::class.java) {
            registry.deliverBatch(listener, "batch", 3, { true }) {
                if (it == 1) error("Submission failed before acceptance")
                sent += it
            }
        }
        registry.deliverBatch(listener, "batch", 3, { true }) { sent += it }
        assertEquals(listOf(0, 1, 2), sent)
        registry.clear()
    }

    @Test fun aDifferentEpochDiscardsTheOldUnsentSuffixAndStartsAtItsFirstSentence() {
        val listener = listener()
        val registry = GnssCallbackRegistry()
        registry.register(listener, "app", 10001)
        val sent = mutableListOf<String>()
        var allowed = true
        registry.deliverBatch(listener, "old epoch", 3, { allowed }) {
            sent += "old:$it"; allowed = false
        }
        registry.deliverBatch(listener, "new epoch", 2, { true }) { sent += "new:$it" }
        assertEquals(listOf("old:0", "new:0", "new:1"), sent)
        registry.clear()
    }

    @Test fun unregisterCanFinishDuringBlockedBinderAndReregistrationInvalidatesOldSuffix() {
        val listener = listener()
        val registry = GnssCallbackRegistry()
        registry.register(listener, "app", 10001)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val removed = CountDownLatch(1)
        val sent = CopyOnWriteArrayList<Int>()
        val failure = AtomicReference<Throwable?>()
        val sender = Thread {
            runCatching {
                registry.deliverBatch(listener, "batch", 4, { true }) {
                    sent += it; entered.countDown()
                    check(release.await(3, TimeUnit.SECONDS))
                }
            }.onFailure(failure::set)
        }.apply { start() }
        val remover = Thread {
            runCatching {
                check(entered.await(2, TimeUnit.SECONDS))
                registry.unregister(listener)
                registry.register(listener, "app", 10001)
                removed.countDown()
            }.onFailure(failure::set)
        }.apply { start() }
        try {
            assertTrue("Registry lock must not span Binder delivery", removed.await(2, TimeUnit.SECONDS))
        } finally {
            release.countDown(); sender.join(3000); remover.join(3000)
        }
        assertFalse(sender.isAlive); assertFalse(remover.isAlive)
        failure.get()?.let { throw AssertionError(it) }
        assertEquals(listOf(0), sent)
        assertNull(registry.subscriptions().single().pendingDeliveryKey)
        registry.deliverBatch(listener, "batch", 4, { true }) { sent += it }
        assertEquals(listOf(0, 0, 1, 2, 3), sent) // The second 0 belongs to the new registration.
        registry.clear()
    }

    @Test fun deliveryWorkerPauseCommitsTheAcceptedItemBeforeInterruptingTheSuffix() {
        val listener = listener()
        val registry = GnssCallbackRegistry()
        registry.register(listener, "app", 10001)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val sent = CopyOnWriteArrayList<Int>()
        val failure = AtomicReference<Throwable?>()
        val worker = DeliveryWorker("batch-pause-test", { 1000 }, tick = {
            registry.deliverBatch(listener, "batch", 3, { true }) {
                sent += it; entered.countDown()
                var interrupted = false
                while (release.count > 0) try { release.await() }
                catch (_: InterruptedException) { interrupted = true }
                if (interrupted) Thread.currentThread().interrupt()
            }
        }, failure = { failure.set(it) })
        try {
            worker.start()
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            assertFalse(worker.stop(0))
        } finally { release.countDown(); assertTrue(worker.stop(2000)) }
        failure.get()?.let { throw AssertionError(it) }
        assertEquals(listOf(0), sent)
        assertEquals(1, registry.subscriptions().single().nextDeliveryIndex)
        registry.deliverBatch(listener, "batch", 3, { true }) { sent += it }
        assertEquals(listOf(0, 1, 2), sent)
        registry.clear()
    }

    private fun listener(): IInterface {
        val binder = Proxy.newProxyInstance(IBinder::class.java.classLoader, arrayOf(IBinder::class.java)) { proxy, method, args ->
            when (method.name) {
                "isBinderAlive", "unlinkToDeath" -> true
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.get(0)
                else -> null
            }
        } as IBinder
        return object : IInterface { override fun asBinder(): IBinder = binder }
    }
}
