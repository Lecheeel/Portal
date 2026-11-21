package com.system.location.service.hook.hooks.provider

import android.location.Location
import com.system.location.service.core.routing.RoutingDecision
import com.system.location.service.hook.BaseLocationHook
import com.system.location.service.hook.hooks.ListenerRouting
import com.system.location.service.hook.runtime.CurrentLocationDelivery
import com.system.location.service.hook.runtime.Reflection
import com.system.location.service.hook.utils.FakeLoc
import com.system.location.service.hook.utils.Logger
import com.system.location.service.hook.utils.ProviderView
import com.system.location.service.hook.utils.hook
import com.system.location.service.hook.utils.onceHook
import io.github.libxposed.api.XposedInterface

/** Supplies a one-shot cache candidate only while Android activates an eligible registration. */
internal object CurrentLocationProviderHook : BaseLocationHook() {
    fun install(loader: ClassLoader) {
        val manager = Reflection.findClassIfExists(
            "com.android.server.location.provider.LocationProviderManager", loader,
        ) ?: return
        val registration = Reflection.findClassIfExists(
            manager.name + "\$GetCurrentLocationListenerRegistration", loader,
        ) ?: return
        val activate = Reflection.findMethodExactIfExists(registration, "onActive") ?: return
        val cache = Reflection.findMethodExactIfExists(manager, "getLastLocationUnsafe",
            Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!,
            Boolean::class.javaPrimitiveType!!, Long::class.javaPrimitiveType!!) ?: return
        // The broad provider adapter already observes this method. This guard is additional:
        // its replacement is confined to the currently activating one-shot registration.
        cache.hook(XposedInterface.Hooker { chain ->
            val original = chain.proceed()
            CurrentLocationDelivery.sampleFor(chain.thisObject) ?: original
        })
        activate.onceHook(XposedInterface.Hooker { chain ->
            if (!FakeLoc.enable || FakeLoc.disableGetCurrentLocation) return@Hooker chain.proceed()
            val target = chain.thisObject ?: return@Hooker chain.proceed()
            // onActive is reached after the platform checks the provider switch, permissions,
            // background policy and AppOps eligibility. Never manufacture a null callback result.
            val request = runCatching {
                val owner = requireNotNull(Reflection.callMethod(target, "getOwner"))
                val provider = Reflection.callMethod(owner, "getName") as String
                val identity = requireNotNull(Reflection.callMethod(target, "getIdentity"))
                val uid = Reflection.callMethod(identity, "getUid") as Int
                val pkg = Reflection.callMethod(identity, "getPackageName") as String
                Request(owner, provider, uid, pkg)
            }.onFailure { Logger.error("Cannot resolve current-location registration", it) }
                .getOrNull() ?: return@Hooker chain.proceed()
            CurrentLocationDelivery.during(request.owner, { sample(request) }) { chain.proceed() }
        })
        Logger.info("current-location provider cache candidate installed")
    }

    private fun sample(request: Request): Location? {
        if (!FakeLoc.enable || FakeLoc.disableGetCurrentLocation) return null
        val context = ProviderView.capture()
        if (!context.state.configuration.enabled) return null
        val route = ListenerRouting.decisionFor(request.pkg, request.uid, context.state.routing)
        if (route is RoutingDecision.Deny) return null
        return locationForProvider(request.provider,
            scenarioId = (route as? RoutingDecision.Scenario)?.scenarioId,
            point = (route as? RoutingDecision.Point)?.point, context = context)
        // The original activation continues through acceptLocationChange: Android notes AppOps,
        // applies coarse filtering, delivers at most once and unregisters the request. The cache
        // candidate is never written into the manager's real last-location storage.
    }

    private data class Request(val owner: Any, val provider: String, val uid: Int, val pkg: String)
}
