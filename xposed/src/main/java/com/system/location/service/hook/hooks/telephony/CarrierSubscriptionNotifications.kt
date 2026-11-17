package com.system.location.service.hook.hooks.telephony

import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import com.system.location.service.hook.utils.Logger
import java.util.concurrent.atomic.AtomicLong

/** Notify the framework's real subscription listeners after the phone peer accepts a new view. */
internal object CarrierSubscriptionNotifications {
    private val revision = AtomicLong()

    fun schedule() {
        val request = revision.incrementAndGet()
        val generation = com.system.location.service.hook.runtime.GenerationDelivery(
            com.system.location.service.hook.runtime.HookRuntime.executionGate)
        Handler(Looper.getMainLooper()).postDelayed({
            if (revision.get() != request) return@postDelayed
            generation.run {
                val token = Binder.clearCallingIdentity()
                try {
                    val manager = Class.forName("android.os.ServiceManager")
                    val binder = manager.getMethod("getService", String::class.java)
                        .invoke(null, "telephony.registry") as? IBinder ?: return@run
                    val stub = Class.forName("com.android.internal.telephony.ITelephonyRegistry\$Stub")
                    val registry = stub.getMethod("asInterface", IBinder::class.java).invoke(null, binder)
                    registry.javaClass.getMethod("notifySubscriptionInfoChanged").invoke(registry)
                } catch (failure: Exception) {
                    Logger.warn("Subscription listener notification unavailable: ${failure.javaClass.simpleName}")
                } finally { Binder.restoreCallingIdentity(token) }
            }
        }, 250)
    }
}
