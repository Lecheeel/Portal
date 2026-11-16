package com.system.location.service.hook.hooks.telephony

import android.os.Binder
import android.telephony.SubscriptionManager
import com.system.location.service.core.carrier.CarrierIdentityResolver
import com.system.location.service.core.carrier.SimCarrierProfile
import com.system.location.service.hook.utils.BinderUtils
import com.system.location.service.hook.utils.FakeLoc

/** Resolves physical subscriptions under system identity; caller identity is captured beforehand. */
internal object CarrierIdentityRuntime {
    private val resolving = ThreadLocal.withInitial { false }

    fun profileForCall(methodName: String, args: List<Any?>,
        callerUid: Int = Binder.getCallingUid()): SimCarrierProfile? {
        val policy = FakeLoc.runtimeSnapshots.current().configuration
        if (!policy.enabled || !policy.carrierIdentityEnabled || resolving.get() == true) return null
        val config = policy.carrierIdentity ?: return null
        val uid = callerUid
        val context = BinderUtils.getSystemContext() ?: return null
        val packages = runCatching { context.packageManager.getPackagesForUid(uid)?.toList().orEmpty() }
            .getOrDefault(emptyList())
        val systemCaller = uid % 100000 < 10000 || packages.isEmpty() || packages.any { name ->
            runCatching {
                val flags = context.packageManager.getApplicationInfo(name, 0).flags
                flags and (android.content.pm.ApplicationInfo.FLAG_SYSTEM or
                    android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
            }.getOrDefault(true)
        }
        resolving.set(true)
        val token = Binder.clearCallingIdentity()
        return try {
            val manager = context.getSystemService(SubscriptionManager::class.java) ?: return null
            val subscriptions = manager.activeSubscriptionInfoList.orEmpty()
                .filter { it.simSlotIndex in 0..1 && SubscriptionManager.isValidSubscriptionId(it.subscriptionId) }
            val slot = com.system.location.service.core.carrier.CarrierSubscriptionSelection.slot(
                methodName, args.firstOrNull() as? Int,
                subscriptions.associate { it.subscriptionId to (it.simSlotIndex + 1) },
                SubscriptionManager.getDefaultSubscriptionId(),
            ) ?: return null
            CarrierIdentityResolver.profile(config, slot,
                subscriptions.map { it.simSlotIndex + 1 }.toSet(), packages, systemCaller)
        } catch (_: Exception) {
            // An unavailable mapping cannot be guessed: preserve the platform result.
            null
        } finally {
            Binder.restoreCallingIdentity(token)
            resolving.set(false)
        }
    }
}
