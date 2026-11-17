package com.system.location.service.hook.hooks.telephony

import android.os.Binder
import android.os.Process
import com.system.location.service.core.carrier.CarrierIdentityMode
import com.system.location.service.core.carrier.CarrierListPropertyOwnership
import com.system.location.service.core.carrier.SimCarrierProfile
import com.system.location.service.hook.runtime.Reflection
import com.system.location.service.hook.utils.FakeLoc
import com.system.location.service.hook.utils.Logger
import com.system.location.service.hook.utils.onceHookAllMethod
import io.github.libxposed.api.XposedInterface.Hooker
import java.util.Locale

/** GLOBAL property-backed getters also work in applications outside module injection scope. */
internal object CarrierGlobalProperties {
    private val lock = Any()
    private val writing = ThreadLocal.withInitial { false }
    private val fields: Map<String, (SimCarrierProfile) -> String> = linkedMapOf(
        "icc_operator_numeric" to { it.operatorNumeric },
        "icc_operator_alpha" to { it.carrierName },
        "icc_operator_iso_country" to { it.countryIso.lowercase(Locale.ROOT) },
        "operator_numeric" to { it.operatorNumeric },
        "operator_alpha" to { it.operatorName },
        "operator_iso_country" to { it.countryIso.lowercase(Locale.ROOT) },
    )
    private val ownership = fields.keys.associateWith { CarrierListPropertyOwnership() }
    @Volatile private var type: Class<*>? = null

    fun install(loader: ClassLoader) {
        val properties = Reflection.findClassIfExists("android.sysprop.TelephonyProperties", loader) ?: return
        type = properties
        fields.keys.forEach { field ->
            properties.onceHookAllMethod(field, Hooker { chain ->
                val result = chain.proceed()
                if (chain.args.size == 1 && writing.get() != true) refresh()
                result
            })
        }
        refresh()
    }

    fun refresh() {
        if (writing.get() == true || runCatching { Process.myUid() }.getOrDefault(-1) != 1001) return
        val properties = type ?: return
        synchronized(lock) {
            if (writing.get() == true) return
            writing.set(true)
            val token = Binder.clearCallingIdentity()
            try {
                val policy = FakeLoc.runtimeSnapshots.current().configuration
                val global = policy.enabled && policy.carrierIdentityEnabled &&
                    policy.carrierIdentity?.mode == CarrierIdentityMode.GLOBAL
                val profiles = if (global) (0..1).mapNotNull { phone ->
                    CarrierIdentityRuntime.profileForCall("getSimOperatorNumericForPhone", listOf(phone), 1001)
                }.distinctBy { it.slot } else emptyList()
                fields.forEach { (field, value) ->
                    val owner = ownership.getValue(field)
                    if (profiles.isEmpty() && !owner.ownsValues()) return@forEach
                    runCatching {
                        @Suppress("UNCHECKED_CAST")
                        val current = properties.getMethod(field).invoke(null) as List<String>
                        val next = profiles.associate { it.slot - 1 to value(it) }
                        owner.update(current, next) { list ->
                            properties.getMethod(field, List::class.java).invoke(null, list)
                        }
                    }.onFailure { Logger.warn("Global carrier property $field unavailable: ${it.javaClass.simpleName}") }
                }
            } finally {
                Binder.restoreCallingIdentity(token)
                writing.set(false)
            }
        }
    }
}
