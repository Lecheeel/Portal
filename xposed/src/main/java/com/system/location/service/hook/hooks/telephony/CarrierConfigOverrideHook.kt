package com.system.location.service.hook.hooks.telephony

import android.os.Binder
import android.os.PersistableBundle
import com.system.location.service.core.carrier.CarrierOverrideOwnership
import com.system.location.service.hook.runtime.HookRuntime
import com.system.location.service.hook.runtime.Reflection
import com.system.location.service.hook.scope.HookInstaller
import io.github.libxposed.api.XposedInterface.Hooker
import java.util.IdentityHashMap

/**
 * Owns only Portal's temporary carrier fields, inside CarrierConfigLoader's update path.
 * The public API enforces platform permissions; the private merge helper runs on the loader's
 * handler on recent Android releases. Requests are correlated by the exact clean bundle object,
 * rather than ThreadLocal state which would be lost across that handler hop.
 */
internal object CarrierConfigOverrideHook {
    @Volatile var supported = false
        private set
    private data class Request(val subId: Int, val action: String, val patch: PersistableBundle)
    private data class Ownership(val baseline: PersistableBundle, val written: PersistableBundle,
        val phoneId: Int, val layer: Array<PersistableBundle?>)
    private val pending = IdentityHashMap<PersistableBundle, Request>()
    private val owned = HashMap<Int, Ownership>()
    private val lock = Any()

    fun status(subId: Int): Pair<Boolean, Boolean> = synchronized(lock) {
        owned.containsKey(subId) to pending.values.any { it.subId == subId }
    }

    fun install(loader: ClassLoader) {
        val type = Reflection.findClassIfExists("com.android.phone.CarrierConfigLoader", loader) ?: return
        val helper = type.declaredMethods.firstOrNull {
            it.name == "overrideConfig" && it.parameterTypes.toList() == listOf(
                arrayOfNulls<PersistableBundle>(0).javaClass,
                Int::class.javaPrimitiveType,
                PersistableBundle::class.java,
            )
        }
        val publicMethod = type.declaredMethods.firstOrNull {
            it.name == "overrideConfig" && it.parameterTypes.toList() == listOf(
                Int::class.javaPrimitiveType, PersistableBundle::class.java, Boolean::class.javaPrimitiveType,
            )
        } ?: return
        // Always strip/reject our protocol, including on unsupported OEMs. Never let its request
        // keys become real carrier keys merely because a private platform helper changed shape.
        HookInstaller.hookMethod(publicMethod, Hooker { chain ->
            val request = chain.args.getOrNull(1) as? PersistableBundle
            val action = request?.getString(CarrierOverrideProtocol.ACTION)
            if (action == null) return@Hooker chain.proceed()
            require(Binder.getCallingUid() == 1000) { "Carrier override requires system UID" }
            check(supported) { "Owned carrier override is unsupported on this platform" }
            require(action == CarrierOverrideProtocol.APPLY || action == CarrierOverrideProtocol.RESTORE)
            require(chain.args.getOrNull(2) == false) { "Portal carrier override must be temporary" }
            val subId = chain.args[0] as Int
            val patch = PersistableBundle(request).apply { remove(CarrierOverrideProtocol.ACTION) }
            require(patch.keySet().all { it in CarrierOverrideProtocol.FIELDS }) { "Unexpected carrier field" }
            val clean = PersistableBundle()
            synchronized(lock) {
                check(pending.size < 64) { "Carrier update queue is full" }
                pending[clean] = Request(subId, action, patch)
            }
            val args = chain.args.toTypedArray().apply { this[1] = clean }
            try { chain.proceed(args) } catch (failure: Throwable) {
                synchronized(lock) { pending.remove(clean) }
                throw failure
            }
        }, exceptionMode = io.github.libxposed.api.XposedInterface.ExceptionMode.PASSTHROUGH)
        if (helper == null) return
        HookInstaller.hookMethod(helper, Hooker { chain ->
            val bundle = chain.args.getOrNull(2) as? PersistableBundle ?: return@Hooker chain.proceed()
            synchronized(lock) {
                val request = pending.remove(bundle) ?: return@synchronized chain.proceed()
                @Suppress("UNCHECKED_CAST")
                val values = chain.args[0] as Array<PersistableBundle?>
                val phoneId = chain.args[1] as Int
                check(phoneId in values.indices) { "Invalid carrier phone index" }
                val current = values[phoneId]?.let(::PersistableBundle) ?: PersistableBundle()
                val previous = owned[request.subId]
                check(previous == null || previous.phoneId == phoneId && previous.layer === values) {
                    "Subscription mapping changed; refusing to reuse carrier restore baseline"
                }
                check(owned.entries.none { (subId, ownership) ->
                    subId != request.subId && ownership.phoneId == phoneId && ownership.layer === values
                }) { "Carrier slot ownership belongs to another subscription" }
                val args = chain.args.toTypedArray()
                if (request.action == CarrierOverrideProtocol.APPLY) {
                    val baseline = previous?.let {
                        val rebased = CarrierOverrideOwnership.rebase(
                            it.baseline.keySet().associateWith { key -> it.baseline[key] },
                            current.keySet().associateWith { key -> current[key] },
                            it.written.keySet().associateWith { key -> it.written[key] },
                            request.patch.keySet(),
                        )
                        PersistableBundle(it.baseline).apply {
                            request.patch.keySet().forEach { key ->
                                remove(key)
                                if (key in rebased) {
                                    val source = if (it.written.containsKey(key) && current.containsKey(key) &&
                                        current[key] == it.written[key]) it.baseline else current
                                    putAll(PersistableBundle(source).apply {
                                        keySet().toList().filterNot { candidate -> candidate == key }.forEach(::remove)
                                    })
                                }
                            }
                        }
                    } ?: current
                    args[2] = request.patch
                    val result = chain.proceed(args)
                    owned[request.subId] = Ownership(baseline, PersistableBundle(request.patch), phoneId, values)
                    result
                } else {
                    if (previous == null) return@synchronized chain.proceed()
                    val restored = PersistableBundle(current)
                    val baselineFields = PersistableBundle(previous.baseline)
                    val plan = CarrierOverrideOwnership.restorePlan(
                        previous.baseline.keySet().associateWith { previous.baseline[it] },
                        current.keySet().associateWith { current[it] },
                        previous.written.keySet().associateWith { previous.written[it] },
                    )
                    // Preserve changes another tool made after us, including changes to an owned
                    // key. All unowned keys remain in the current bundle untouched.
                    plan.remove.forEach(restored::remove)
                    baselineFields.keySet().toList().filterNot { it in plan.restore }
                        .forEach(baselineFields::remove)
                    restored.putAll(baselineFields)
                    // The AOSP helper merges non-null bundles. Clear only this temporary layer,
                    // then replace it before the outer method sends any config-change notification.
                    HookRuntime.invokeOriginalMethod(helper, chain.thisObject,
                        arrayOf(values, phoneId, null))
                    args[2] = restored
                    val result = try { chain.proceed(args) } catch (failure: Throwable) {
                        values[phoneId] = current
                        throw failure
                    }
                    owned.remove(request.subId)
                    result
                }
            }
        })
        supported = true
    }
}
