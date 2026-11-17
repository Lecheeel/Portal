package com.system.location.service.hook.hooks.telephony

import android.annotation.SuppressLint
import android.telephony.SubscriptionManager
import com.system.location.service.hook.runtime.Reflection
import com.system.location.service.hook.utils.BinderUtils
import com.system.location.service.hook.utils.Logger
import com.system.location.service.hook.utils.hookAllMethods
import io.github.libxposed.api.XposedInterface.Hooker

/** Software subscriptions do not create modem IMS/RCS features or provisioning credentials. */
internal object CarrierHardwareSubscriptionHook {
    private val hardwareQuery = ThreadLocal.withInitial { false }
    internal fun hardwareQueryActive(): Boolean = hardwareQuery.get() == true
    internal fun <T> withHardwareQuery(action: () -> T): T {
        val previous = hardwareQueryActive()
        hardwareQuery.set(true)
        try { return action() } finally {
            if (previous) hardwareQuery.set(true) else hardwareQuery.remove()
        }
    }

    @SuppressLint("MissingPermission")
    fun install(loader: ClassLoader) {
        val monitor = Reflection.findClassIfExists("com.android.phone.RcsProvisioningMonitor", loader) ?: return
        monitor.hookAllMethods("onSubChanged", Hooker { chain -> withHardwareQuery { chain.proceed() } })
        SubscriptionManager::class.java.hookAllMethods("getActiveSubscriptionIdList", Hooker { chain ->
            val original = chain.proceed() // retain the framework's original checks
            if (!hardwareQueryActive()) return@Hooker original
            runCatching {
                val context = BinderUtils.getSystemContext() ?: error("System context unavailable")
                val physical = context.createAttributionContext(CarrierIdentityRuntime.PHYSICAL_QUERY_TAG)
                    .getSystemService(SubscriptionManager::class.java) ?: error("Subscription service unavailable")
                physical.activeSubscriptionInfoList.orEmpty().map { it.subscriptionId }
                    .filter(SubscriptionManager::isValidSubscriptionId).distinct().toIntArray()
            }.getOrElse {
                Logger.warn("Hardware subscription query unavailable: ${it.javaClass.simpleName}")
                // Unavailable hardware cannot be safely provisioned from a modeled subscription.
                intArrayOf()
            }
        })
    }
}
