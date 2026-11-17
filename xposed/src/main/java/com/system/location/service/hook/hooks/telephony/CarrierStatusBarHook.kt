package com.system.location.service.hook.hooks.telephony

import android.os.Handler
import android.os.Looper
import com.system.location.service.core.carrier.SimCarrierProfile
import com.system.location.service.hook.runtime.Reflection
import com.system.location.service.hook.scope.HookInstaller
import com.system.location.service.hook.utils.Logger
import com.system.location.service.hook.utils.onceHookMethod
import io.github.libxposed.api.XposedInterface.Hooker
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** MIUI carrier text adapter. Uses the ROM's renderer and listener notifications on its UI thread. */
internal object CarrierStatusBarHook {
    private const val TYPE = "com.android.systemui.statusbar.policy.MiuiCarrierTextControllerImpl"
    private val fields = listOf("mCarrier", "mCustomCarrier", "mCardDisable", "mSimError")
    private val controllers = Collections.synchronizedMap(WeakHashMap<Any, Handler>())
    private val installed = Collections.synchronizedSet(mutableSetOf<Class<*>>())
    private val workerStarted = AtomicBoolean()
    @Volatile private var profiles: List<SimCarrierProfile> = emptyList()

    fun install(loader: ClassLoader) {
        val type = Reflection.findClassIfExists(TYPE, loader) ?: return
        if (fields.any { Reflection.findFieldIfExists(type, it) == null }) return
        synchronized(installed) {
            if (type in installed) return
            type.onceHookMethod("updateCarrierText", callback = Hooker { chain ->
                val target = chain.thisObject ?: return@Hooker chain.proceed()
                withVirtualCarriers(target, profiles) { chain.proceed() }
            }) ?: return
            HookInstaller.hookAllConstructors(type, Hooker { chain ->
                val result = chain.proceed()
                chain.thisObject?.let { target ->
                    val handler = runCatching { Reflection.getObjectField(target, "mMainHandler") as? Handler }
                        .getOrNull() ?: Handler(Looper.getMainLooper())
                    controllers[target] = handler
                }
                result
            })
            installed.add(type)
        }
        startWorker()
    }

    private fun startWorker() {
        if (!workerStarted.compareAndSet(false, true)) return
        Thread({
            while (!Thread.currentThread().isInterrupted) {
                try {
                    if (controllers.isNotEmpty()) {
                        // A read-only caller-scoped snapshot returns no profiles for SystemUI in
                        // PER_APP or while stopped. Binder work stays off the UI thread.
                        val next = CarrierClientHook.statusBarProfiles()
                        if (next != profiles) {
                            profiles = next
                            val targets = synchronized(controllers) { controllers.entries.map { it.key to it.value } }
                            targets.forEach { (target, handler) ->
                                val reference = java.lang.ref.WeakReference(target)
                                handler.post {
                                    reference.get()?.let { instance ->
                                        runCatching { Reflection.callMethod(instance, "updateCarrierText") }
                                            .onFailure { Logger.warn("Carrier text refresh failed: ${it.javaClass.simpleName}") }
                                    }
                                }
                            }
                        }
                    }
                    Thread.sleep(1500)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                } catch (failure: Exception) {
                    Logger.warn("Carrier text snapshot failed: ${failure.javaClass.simpleName}")
                }
            }
        }, "PortalCarrierText").apply { isDaemon = true }.start()
    }

    /** Replace array references only for this render; the ROM retains every original cache value. */
    internal fun withVirtualCarriers(target: Any, visible: List<SimCarrierProfile>, render: () -> Any?): Any? {
        val virtuals = visible.filter { it.virtual && it.enabled }
        if (virtuals.isEmpty()) return render()
        val carrierField = Reflection.findFieldIfExists(target.javaClass, "mCarrier") ?: return render()
        val customField = Reflection.findFieldIfExists(target.javaClass, "mCustomCarrier") ?: return render()
        val disabledField = Reflection.findFieldIfExists(target.javaClass, "mCardDisable") ?: return render()
        val errorsField = Reflection.findFieldIfExists(target.javaClass, "mSimError") ?: return render()
        val originalCarrier = carrierField.get(target) as? Array<*> ?: return render()
        val originalCustom = customField.get(target)
        val originalDisabled = disabledField.get(target) as? BooleanArray ?: return render()
        val originalErrors = errorsField.get(target) as? BooleanArray ?: return render()
        val emergencyField = Reflection.findFieldIfExists(target.javaClass, "mEmergencyOnly")
        val originalEmergency = emergencyField?.get(target) as? Boolean
        val carrier = originalCarrier.copyOf()
        @Suppress("UNCHECKED_CAST")
        val custom = (originalCustom as? Array<*>)?.copyOf() ?: arrayOfNulls<String>(carrier.size)
        val disabled = originalDisabled.copyOf()
        val errors = originalErrors.copyOf()
        @Suppress("UNCHECKED_CAST")
        virtuals.forEach { profile ->
            val index = profile.slot - 1
            if (index in carrier.indices && index in custom.indices && index in disabled.indices && index in errors.indices) {
                (carrier as Array<Any?>)[index] = profile.carrierName
                (custom as Array<Any?>)[index] = null
                disabled[index] = false
                errors[index] = false
            }
        }
        try {
            carrierField.set(target, carrier)
            customField.set(target, custom)
            disabledField.set(target, disabled)
            errorsField.set(target, errors)
            if (originalEmergency != null) emergencyField?.setBoolean(target, false)
            return render()
        } finally {
            carrierField.set(target, originalCarrier)
            customField.set(target, originalCustom)
            disabledField.set(target, originalDisabled)
            errorsField.set(target, originalErrors)
            if (originalEmergency != null) emergencyField?.setBoolean(target, originalEmergency)
        }
    }
}
