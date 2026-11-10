package com.system.location.service.hook.hooks.gnss

import android.os.IInterface
import com.system.location.service.core.gnss.GnssFrameRoute
import com.system.location.service.core.gnss.PublishedGnssFrame
import com.system.location.service.hook.hooks.nmea.NmeaBridge
import com.system.location.service.hook.hooks.nmea.NmeaListenerRouting
import com.system.location.service.hook.runtime.HookRuntime
import com.system.location.service.hook.runtime.DeliveryWorker
import com.system.location.service.hook.utils.FakeLoc
import com.system.location.service.hook.utils.LogRateLimiter
import com.system.location.service.hook.utils.ProviderView

/** The only producer of simulated GNSS callbacks. Missing capture leaves the real stream intact. */
object GnssSignalInjection {
    private val failures = LogRateLimiter()
    private val worker = DeliveryWorker("GnssSignalInjection", { 1000 },
        tick = ::tick, failure = { error -> report("worker", error) })

    fun ensureStarted() = worker.start()

    /** Pause delivery without losing subscriptions; a failed reload can resume this generation. */
    fun pause(): Boolean = worker.stop()

    fun stop(): Boolean {
        if (!pause()) return false
        GnssListenerRouting.clear()
        NmeaListenerRouting.clear()
        RawGnssCallbacks.clear()
        return true
    }

    private fun tick() {
        // Avoid generating samples while inactive; the captured policy below is authoritative.
        val initial = FakeLoc.runtimeSnapshots.current().configuration
        if (!initial.enabled || !initial.gnssEnabled || initial.gnssSource == null) return
        val context = ProviderView.capture()
        val policy = context.state.configuration
        if (!policy.enabled || !policy.gnssEnabled || policy.gnssSource == null) return
        val frames = ReceiverTickFrames { GnssReceiverRuntime.frameForRoute(it, context) }
        GnssListenerRouting.listeners().forEach { listener ->
            deliver(listener, { GnssListenerRouting.unregister(listener) }) {
                val route = GnssListenerRouting.routeFor(listener, context.state.routing) ?: return@deliver
                val publication = frames.forRoute(route) ?: return@deliver
                val method = listener.javaClass.methods.firstOrNull {
                    it.name == "onSvStatusChanged" && ReceiverGnssStatusAdapter.supports(it)
                } ?: error("unsupported GNSS status callback signature")
                HookRuntime.invokeOriginalMethod(method, listener, ReceiverGnssStatusAdapter.arguments(method, publication))
            }
        }
        if (!policy.nmeaEnabled) return
        NmeaListenerRouting.listeners().forEach { listener ->
            deliver(listener, { NmeaListenerRouting.unregister(listener) }) {
                val route = NmeaListenerRouting.routeFor(listener, context.state.routing) ?: return@deliver
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

/** Freeze each route once per delivery tick, even if provider samples advance during callbacks. */
internal class ReceiverTickFrames(private val source: (GnssFrameRoute) -> PublishedGnssFrame?) {
    private val frames = mutableMapOf<GnssFrameRoute, PublishedGnssFrame?>()
    fun forRoute(route: GnssFrameRoute): PublishedGnssFrame? {
        if (!frames.containsKey(route)) frames[route] = source(route)
        return frames[route]
    }
}
