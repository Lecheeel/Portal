package com.system.location.service.hook.hooks.gnss

import android.os.IInterface
import android.os.SystemClock
import com.system.location.service.hook.hooks.nmea.NmeaBridge
import com.system.location.service.hook.hooks.nmea.NmeaListenerRouting
import com.system.location.service.hook.utils.FakeLoc
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Proactively feeds registered GNSS/NMEA callbacks from the simulated sky model.
 *
 * Rewriting only incoming callbacks is insufficient on devices without a real GNSS fix: an app
 * registering while indoors would otherwise receive no GnssStatus/NMEA at all.
 */
object GnssSignalInjection {
    private val started = AtomicBoolean(false)

    fun ensureStarted() {
        if (!started.compareAndSet(false, true)) return
        Thread({
            while (!Thread.currentThread().isInterrupted) {
                runCatching {
                    if (FakeLoc.enable && FakeLoc.enableMockGnss) {
                        injectStatus()
                        if (FakeLoc.enableNMEA) injectNmea()
                    }
                }
                try {
                    Thread.sleep(1_000L)
                } catch (interrupted: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }
        }, "GnssSignalInjection").apply { isDaemon = true }.start()
    }

    fun stop() {
        started.set(false)
        // Threads are daemon and exit when interrupted; keep this callable from hot reload.
        Thread.getAllStackTraces().keys
            .firstOrNull { it.name == "GnssSignalInjection" }
            ?.interrupt()
    }

    private fun injectStatus() {
        val listeners = GnssListenerRouting.listeners()
        if (listeners.isEmpty()) return
        val data = GnssStatusBridge.toMockData(GnssStatusBridge.currentFrame())
        listeners.forEach { listener ->
            runCatching {
                val method = listener.javaClass.methods.firstOrNull {
                    it.name == "onSvStatusChanged" && it.parameterTypes.isNotEmpty()
                } ?: return@forEach
                val args = when {
                    method.parameterTypes.first() == Int::class.javaPrimitiveType ||
                        method.parameterTypes.first() == Int::class.java -> arrayOf(
                        data.svCount,
                        data.svidWithFlags,
                        data.cn0s,
                        data.elevations,
                        data.azimuths,
                        data.carrierFreqs,
                        GnssStatusBridge.basebandCn0(data),
                    )
                    method.parameterTypes.first().name == "android.location.GnssStatus" ->
                        arrayOf(gnssStatus(data) ?: return@forEach)
                    else -> return@forEach
                }
                method.invoke(listener, *args)
            }
        }
    }

    private fun injectNmea() {
        val listeners = NmeaListenerRouting.listeners()
        if (listeners.isEmpty()) return
        val sentences = NmeaBridge.generatedSentences()
        if (sentences.isEmpty()) return
        val timestamp = SystemClock.elapsedRealtimeNanos()
        listeners.forEach { listener ->
            runCatching {
                val method = listener.javaClass.methods.firstOrNull {
                    it.name == "onNmeaReceived" && it.parameterTypes.size == 2
                } ?: return@forEach
                // The callback method is itself hooked to drop real NMEA; invoke ORIGIN so the
                // injected frame is not swallowed by that hook.
                sentences.forEach { sentence ->
                    com.system.location.service.hook.runtime.HookRuntime.invokeOriginalMethod(
                        method,
                        listener,
                        arrayOf<Any?>(timestamp, sentence),
                    )
                }
            }.onFailure {
                com.system.location.service.hook.runtime.HookRuntime.log(
                    "NMEA injection failed for ${listener.javaClass.name}: ${it.javaClass.simpleName}: ${it.message}"
                )
            }
        }
    }

    private fun gnssStatus(data: MockGnssData): Any? = runCatching {
        val type = Class.forName("android.location.GnssStatus")
        val constructor = type.declaredConstructors.firstOrNull { it.parameterTypes.size == 7 }
            ?: return null
        constructor.isAccessible = true
        constructor.newInstance(
            data.svCount,
            data.svidWithFlags,
            data.cn0s,
            data.elevations,
            data.azimuths,
            data.carrierFreqs,
            GnssStatusBridge.basebandCn0(data),
        )
    }.getOrNull()
}
