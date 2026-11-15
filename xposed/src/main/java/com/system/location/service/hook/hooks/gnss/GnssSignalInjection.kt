package com.system.location.service.hook.hooks.gnss

import android.location.GnssMeasurementsEvent
import android.os.IInterface
import com.system.location.service.core.gnss.GnssFrameRoute
import com.system.location.service.core.gnss.PublishedGnssFrame
import com.system.location.service.core.runtime.RuntimeSnapshot
import com.system.location.service.core.runtime.RuntimeSnapshotObservation
import com.system.location.service.hook.hooks.nmea.NmeaBridge
import com.system.location.service.hook.hooks.nmea.NmeaListenerRouting
import com.system.location.service.hook.runtime.HookRuntime
import com.system.location.service.hook.runtime.DeliveryWorker
import com.system.location.service.hook.utils.FakeLoc
import com.system.location.service.hook.utils.LogRateLimiter
import com.system.location.service.hook.utils.Logger
import com.system.location.service.hook.utils.ProviderView

/** The only producer of simulated GNSS callbacks, using the selected autonomous or replay source. */
object GnssSignalInjection {
    private val failures = LogRateLimiter()
    private val rawReadyListeners = java.util.Collections.newSetFromMap(
        java.util.concurrent.ConcurrentHashMap<android.os.IBinder, Boolean>())
    private val worker = DeliveryWorker("GnssSignalInjection", { 1000 },
        tick = ::tick, failure = { error -> report("worker", error) })

    fun ensureStarted() = worker.start()

    /** New provider samples wake the producer; exact frame keys still limit each epoch to once. */
    fun onSamplePublished() = worker.requestTick()

    /** Pause delivery without losing subscriptions; a failed reload can resume this generation. */
    fun pause(): Boolean = worker.stop()

    fun stop(): Boolean {
        if (!pause()) return false
        GnssListenerRouting.clear()
        NmeaListenerRouting.clear()
        RawGnssCallbacks.clear()
        RawGnssSyntheticDelivery.clear()
        rawReadyListeners.clear()
        return true
    }

    private fun tick() {
        // Avoid generating samples while inactive; the captured policy below is authoritative.
        val initial = FakeLoc.runtimeSnapshots.observation()
        val initialPolicy = initial.state.configuration
        if (!initialPolicy.enabled || !initialPolicy.gnssEnabled || initialPolicy.gnssFrameSource() == null) return
        val context = ProviderView.capture()
        val policy = context.state.configuration
        if (!policy.enabled || !policy.gnssEnabled || policy.gnssFrameSource() == null) return
        val frames = ReceiverTickFrames { GnssReceiverRuntime.frameForRoute(it, context) }
        GnssListenerRouting.listeners().forEach { listener ->
            deliver(listener) {
                val route = GnssListenerRouting.routeFor(listener, context.state.routing) ?: return@deliver
                val publication = frames.forRoute(route) ?: return@deliver
                val method = listener.javaClass.methods.firstOrNull {
                    it.name == "onSvStatusChanged" && ReceiverGnssStatusAdapter.supports(it)
                } ?: error("unsupported GNSS status callback signature")
                GnssListenerRouting.deliverOnce(listener, publication.frame.toString()) {
                    if (!receiverDeliveryAllowed(context.state, initial.simulationGeneration,
                            FakeLoc.runtimeSnapshots.observation())) return@deliverOnce false
                    HookRuntime.invokeOriginalMethod(method, listener, ReceiverGnssStatusAdapter.arguments(method, publication))
                    true
                }
            }
        }
        deliverRawMeasurements(context, frames, initial)
        if (!policy.nmeaEnabled) return
        NmeaListenerRouting.listeners().forEach { listener ->
            deliver(listener) {
                val route = NmeaListenerRouting.routeFor(listener, context.state.routing) ?: return@deliver
                val publication = frames.forRoute(route) ?: return@deliver
                val method = listener.javaClass.methods.firstOrNull {
                    it.name == "onNmeaReceived" && it.parameterTypes.toList() ==
                        listOf(Long::class.javaPrimitiveType, String::class.java)
                } ?: error("unsupported NMEA callback signature")
                val sentences = NmeaBridge.generatedSentences(publication)
                // Compare the exact emitted epoch/text, without rounded-time windows or hashes.
                val key = "${publication.frame.generatedAtMillis}:${sentences.joinToString("")}"
                NmeaListenerRouting.deliverBatch(listener, key, sentences.size,
                    allowed = { receiverDeliveryAllowed(context.state, initial.simulationGeneration,
                        FakeLoc.runtimeSnapshots.observation(), nmea = true) }) { index ->
                    // Android OnNmeaMessageListener timestamps are UTC milliseconds, not elapsed nanos.
                    HookRuntime.invokeOriginalMethod(method, listener,
                        arrayOf(publication.frame.generatedAtMillis, sentences[index]))
                }
            }
        }
    }

    private fun deliverRawMeasurements(
        context: com.system.location.service.hook.utils.ProviderReadContext,
        frames: ReceiverTickFrames,
        initial: RuntimeSnapshotObservation,
    ) {
        RawGnssCallbacks.listeners().forEach { listener ->
            val method = listener.javaClass.methods.firstOrNull {
                it.name == "onGnssMeasurementsReceived" &&
                    it.parameterTypes.toList() == listOf(GnssMeasurementsEvent::class.java)
            } ?: return@forEach
            deliver(listener) {
                val route = RawGnssCallbacks.routeFor(listener, context.state.routing) ?: return@deliver
                val publication = frames.forRoute(route) ?: return@deliver
                val event = RawGnssSyntheticDelivery.event(route, publication, context) ?: return@deliver
                if (rawReadyListeners.add(listener.asBinder())) reportRawGnssReady(listener)
                val key = "${publication.sequence}:${publication.frame.generatedAtMillis}:" +
                    event.clock.timeNanos
                RawGnssCallbacks.deliverMeasurements(listener, key) {
                    if (!receiverDeliveryAllowed(context.state, initial.simulationGeneration,
                            FakeLoc.runtimeSnapshots.observation())) return@deliverMeasurements false
                    HookRuntime.invokeOriginalMethod(method, listener, arrayOf(event))
                    true
                }
            }
        }
    }

    private inline fun deliver(listener: IInterface, action: () -> Unit) {
        if (Thread.currentThread().isInterrupted) return
        try { action() } catch (error: Exception) {
            if (error is InterruptedException) { Thread.currentThread().interrupt(); return }
            report(listener.javaClass.name, error)
        }
    }

    private fun report(key: String, error: Exception) {
        if (failures.allow(key, System.nanoTime()))
            HookRuntime.log("GNSS delivery failed [$key]: ${error.javaClass.simpleName}: ${error.message}")
    }
}

/** Stop/configuration/routing changes invalidate a captured batch; sample renewal does not. */
internal fun receiverDeliveryAllowed(captured: RuntimeSnapshot, simulationGeneration: Long,
    current: RuntimeSnapshotObservation, nmea: Boolean = false): Boolean =
    current.simulationGeneration == simulationGeneration && current.state.configuration.enabled &&
    current.state.configuration.gnssEnabled && (!nmea || current.state.configuration.nmeaEnabled) &&
    current.state.configuration.gnssFrameSource() != null &&
    current.state.configuration == captured.configuration && current.state.routing == captured.routing

/** Freeze each route once per delivery tick, even if provider samples advance during callbacks. */
internal class ReceiverTickFrames(private val source: (GnssFrameRoute) -> PublishedGnssFrame?) {
    private val frames = mutableMapOf<GnssFrameRoute, PublishedGnssFrame?>()
    fun forRoute(route: GnssFrameRoute): PublishedGnssFrame? {
        if (!frames.containsKey(route)) frames[route] = source(route)
        return frames[route]
    }
}
