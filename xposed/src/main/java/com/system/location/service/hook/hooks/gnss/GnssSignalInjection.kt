package com.system.location.service.hook.hooks.gnss

import android.os.IInterface
import com.system.location.service.core.gnss.GnssFrameRoute
import com.system.location.service.core.gnss.PublishedGnssFrame
import com.system.location.service.hook.hooks.nmea.NmeaBridge
import com.system.location.service.hook.hooks.nmea.NmeaListenerRouting
import com.system.location.service.hook.runtime.HookRuntime
import com.system.location.service.hook.utils.FakeLoc
import com.system.location.service.hook.utils.LogRateLimiter

/** The only producer of simulated GNSS callbacks. Missing capture leaves the real stream intact. */
object GnssSignalInjection {
    private val failures = LogRateLimiter()
    private val worker = ReceiverDeliveryWorker(::tick) { error -> report("worker", error) }

    fun ensureStarted() = worker.start()

    fun stop() {
        worker.stop()
        GnssListenerRouting.clear()
        NmeaListenerRouting.clear()
        RawGnssCallbacks.clear()
    }

    private fun tick() {
        if (!FakeLoc.enable || !FakeLoc.enableMockGnss) return
        val frames = ReceiverTickFrames(GnssReceiverRuntime::frameForRoute)
        GnssListenerRouting.listeners().forEach { listener ->
            deliver(listener, { GnssListenerRouting.unregister(listener) }) {
                val route = GnssListenerRouting.routeFor(listener) ?: return@deliver
                val publication = frames.forRoute(route) ?: return@deliver
                val method = listener.javaClass.methods.firstOrNull {
                    it.name == "onSvStatusChanged" && ReceiverGnssStatusAdapter.supports(it)
                } ?: error("unsupported GNSS status callback signature")
                HookRuntime.invokeOriginalMethod(method, listener, ReceiverGnssStatusAdapter.arguments(method, publication))
            }
        }
        if (!FakeLoc.enableNMEA) return
        NmeaListenerRouting.listeners().forEach { listener ->
            deliver(listener, { NmeaListenerRouting.unregister(listener) }) {
                val route = NmeaListenerRouting.routeFor(listener) ?: return@deliver
                val publication = frames.forRoute(route) ?: return@deliver
                val method = listener.javaClass.methods.firstOrNull {
                    it.name == "onNmeaReceived" && it.parameterTypes.toList() ==
                        listOf(Long::class.javaPrimitiveType, String::class.java)
                } ?: error("unsupported NMEA callback signature")
                NmeaBridge.generatedSentences(publication).forEach { sentence ->
                    if (Thread.currentThread().isInterrupted) return@deliver
                    // Android OnNmeaMessageListener timestamps are UTC milliseconds, not elapsed nanos.
                    HookRuntime.invokeOriginalMethod(method, listener,
                        arrayOf(publication.frame.generatedAtMillis, sentence))
                }
            }
        }
    }

    private inline fun deliver(listener: IInterface, remove: () -> Unit, action: () -> Unit) {
        if (Thread.currentThread().isInterrupted) return
        try { action() } catch (error: Exception) {
            if (error is InterruptedException) { Thread.currentThread().interrupt(); return }
            if (error is android.os.DeadObjectException || !listener.asBinder().isBinderAlive) remove()
            report(listener.javaClass.name, error)
        }
    }

    private fun report(key: String, error: Exception) {
        if (failures.allow(key, System.nanoTime()))
            HookRuntime.log("GNSS delivery failed [$key]: ${error.javaClass.simpleName}: ${error.message}")
    }
}

/** A referenced worker cannot overlap its replacement, including while a callback is returning. */
internal class ReceiverDeliveryWorker(private val tick: () -> Unit, private val failure: (Exception) -> Unit) {
    private var worker: Thread? = null

    @Synchronized fun start() {
        if (worker != null) return
        worker = Thread({
            try {
                while (!Thread.currentThread().isInterrupted) {
                    try { tick() } catch (interrupted: InterruptedException) { throw interrupted }
                    catch (error: Exception) { failure(error) }
                    Thread.sleep(1000)
                }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            } finally {
                synchronized(this) { if (worker === Thread.currentThread()) worker = null }
            }
        }, "GnssSignalInjection").apply { isDaemon = true; start() }
    }

    fun stop() {
        val current = synchronized(this) { worker?.also { it.interrupt() } } ?: return
        if (current !== Thread.currentThread()) current.join(2000)
    }
}

/** Freeze each route once per delivery tick, even if provider samples advance during callbacks. */
internal class ReceiverTickFrames(private val source: (GnssFrameRoute) -> PublishedGnssFrame?) {
    private val frames = mutableMapOf<GnssFrameRoute, PublishedGnssFrame?>()
    fun forRoute(route: GnssFrameRoute): PublishedGnssFrame? {
        if (!frames.containsKey(route)) frames[route] = source(route)
        return frames[route]
    }
}
