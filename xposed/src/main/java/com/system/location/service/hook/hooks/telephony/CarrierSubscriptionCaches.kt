package com.system.location.service.hook.hooks.telephony

import android.os.Binder
import android.os.Process
import android.telephony.SubscriptionManager
import com.system.location.service.core.runtime.RuntimeSnapshot
import com.system.location.service.hook.utils.Logger

/** Invalidate framework client mapping caches after the owning process accepts a new topology. */
internal object CarrierSubscriptionCaches {
    fun onChanged(previous: RuntimeSnapshot, current: RuntimeSnapshot) {
        val before = previous.configuration
        val after = current.configuration
        if (before.enabled == after.enabled && before.carrierIdentityEnabled == after.carrierIdentityEnabled &&
            before.carrierIdentity == after.carrierIdentity) return
        // Only OS processes can publish these property-invalidated cache generations. In particular,
        // run in the phone peer after its asynchronous transfer, not just before dispatching it.
        val uid = runCatching { Process.myUid() }.getOrDefault(-1)
        if (uid != 1000 && uid != 1001) return
        if (uid == 1001) CarrierGlobalProperties.refresh()
        if (com.system.location.service.hook.utils.FakeLoc.isSystemServerProcess) {
            CarrierRegistryHook.onConfigurationChanged()
        }
        runCatching {
            val token = Binder.clearCallingIdentity()
            try {
                SubscriptionManager::class.java.declaredMethods.filter {
                    it.name.startsWith("invalidate") && it.name.contains("Cache") &&
                        it.parameterCount == 0 && java.lang.reflect.Modifier.isStatic(it.modifiers)
                }.forEach { method -> method.isAccessible = true; method.invoke(null) }
            } finally { Binder.restoreCallingIdentity(token) }
        }.onFailure { Logger.warn("Subscription cache invalidation unavailable: ${it.javaClass.simpleName}") }
        if (uid == 1001) CarrierSubscriptionNotifications.schedule()
    }
}
