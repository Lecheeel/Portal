package com.system.location.service.hook.hooks

import android.os.DeadObjectException
import android.os.IInterface
import com.system.location.service.core.runtime.RuntimeSnapshotObservation
import com.system.location.service.hook.BaseLocationHook
import com.system.location.service.hook.runtime.HookRuntime
import com.system.location.service.hook.runtime.Reflection
import com.system.location.service.hook.utils.FakeLoc
import com.system.location.service.hook.utils.Logger
import com.system.location.service.hook.utils.ProviderView
import java.lang.reflect.InvocationTargetException

/** Sends the current provider view through the listener's original implementation. */
internal object LocationListenerDelivery : BaseLocationHook() {
    fun deliver(locationListeners: Collection<Pair<String, IInterface>>, publication: RuntimeSnapshotObservation?) {
        val observation = FakeLoc.runtimeSnapshots.observation()
        if (!observation.state.configuration.enabled) return
        val generation = observation.simulationGeneration
        if (publication != null && publication.simulationGeneration != generation) return
        val context = ProviderView.capture(force = true)
        if (!context.state.configuration.enabled || !isCurrent(generation)) return
        if (publication != null && context.state.sample?.elapsedNanos != publication.state.sample?.elapsedNanos) return
        if (context.state.configuration.debugLog) {
            Logger.debug("==> callOnLocationChanged: ${locationListeners.size}")
        }
        locationListeners.forEach { listenerWithProvider ->
            val listener = listenerWithProvider.second
            if (!isCurrent(generation)) return
            val route = ListenerRouting.route(listener, context.state.routing)
            if (route is ListenerRouting.Route.Denied) return@forEach
            val scenarioId = (route as? ListenerRouting.Route.Scenario)?.scenarioId
            val point = (route as? ListenerRouting.Route.Point)?.point
            val coarse = LocationServiceHook.isCoarse(listener)
            val location = runCatching {
                locationForProvider(
                        if (coarse) "network"
                        else if (listenerWithProvider.first == "GnssBatch") "gps"
                        else listenerWithProvider.first,
                        scenarioId,
                        point,
                        context,
                    ).also {
                        if (coarse) {
                            it.accuracy = maxOf(it.accuracy, 2_000f)
                            it.verticalAccuracyMeters = maxOf(it.verticalAccuracyMeters, it.accuracy * 2f)
                        }
                    }
            }.getOrNull() ?: return@forEach
            if (!isCurrent(generation)) return
            var called = false
            var error: Throwable? = null
            kotlin
                .runCatching {
                    val locations = listOf(location)
                    val mOnLocationChanged =
                        Reflection.findMethodBestMatch(
                            listener.javaClass,
                            "onLocationChanged",
                            locations,
                            null,
                        )
                    HookRuntime.invokeOriginalMethod(
                        mOnLocationChanged,
                        listener,
                        arrayOf(locations, null),
                    )
                    called = true
                }
                .onFailure {
                    if (
                        it is DeadObjectException ||
                            (it is InvocationTargetException &&
                                it.targetException is DeadObjectException)
                    ) {
                        return@forEach
                    }
                    error = it
                }

            if (!called && isCurrent(generation))
                runCatching {
                        val mOnLocationChanged =
                            Reflection.findMethodBestMatch(
                                listener.javaClass,
                                "onLocationChanged",
                                location,
                            )
                        HookRuntime.invokeOriginalMethod(
                            mOnLocationChanged,
                            listener,
                            arrayOf(location),
                        )
                        called = true
                    }
                    .onFailure {
                        if (
                            it is DeadObjectException ||
                                (it is InvocationTargetException &&
                                    it.targetException is DeadObjectException)
                        ) {
                            return@forEach
                        }
                        error = it
                    }

            if (!called) {
                Logger.error(
                    "Location listener delivery failed",
                    error ?: IllegalStateException("No supported listener method"),
                )
            }
        }

        if (context.state.configuration.debugLog) {
            Logger.debug("==> callOnLocationChanged: end")
        }
    }

    private fun isCurrent(generation: Long): Boolean = FakeLoc.runtimeSnapshots.observation().let {
        it.simulationGeneration == generation && it.state.configuration.enabled
    }

}
