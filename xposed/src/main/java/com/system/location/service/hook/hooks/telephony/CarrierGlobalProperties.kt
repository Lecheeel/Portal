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
    private val fields: Map<String, (SimCarrierProfile) -> String?> = linkedMapOf(
        "icc_operator_numeric" to { it.operatorNumeric },
        "icc_operator_alpha" to { it.carrierName },
        "icc_operator_iso_country" to { it.countryIso.lowercase(Locale.ROOT) },
        "operator_numeric" to { it.operatorNumeric },
        "operator_alpha" to { it.operatorName },
        "operator_iso_country" to { it.countryIso.lowercase(Locale.ROOT) },
        "sim_state" to { if (it.virtual) "READY" else null },
        "data_network_type" to { if (!it.virtual) null else
            if (it.radio == com.system.location.service.core.carrier.CarrierRadio.NR) "NR" else "LTE" },
    )
    private val ownership = fields.keys.associateWith { CarrierListPropertyOwnership() }
    @Volatile private var type: Class<*>? = null
    private var journal: CarrierPropertyJournalStore? = null
    private var recovered = false

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

    private fun store(): CarrierPropertyJournalStore = journal ?: run {
        val system = com.system.location.service.hook.utils.BinderUtils.getSystemContext()
            ?: error("System context unavailable")
        val phone = system.createPackageContext("com.android.phone", 0).createDeviceProtectedStorageContext()
        CarrierPropertyJournalStore(phone, fields.keys).also { journal = it }
    }

    @Suppress("UNCHECKED_CAST")
    private fun read(properties: Class<*>, field: String): List<String> =
        properties.getMethod(field).invoke(null) as List<String>

    private fun recover(properties: Class<*>, store: CarrierPropertyJournalStore) {
        store.entries().forEach { (field, entry) ->
            val current = read(properties, field)
            val original = entry.recover(current)
            if (original != current) properties.getMethod(field, List::class.java).invoke(null, original)
        }
        store.clear()
        ownership.values.forEach { it.restore(com.system.location.service.core.carrier.CarrierListPropertyState()) }
        recovered = true
    }

    fun refresh() {
        if (writing.get() == true || runCatching { Process.myUid() }.getOrDefault(-1) != 1001) return
        val properties = type ?: return
        synchronized(lock) {
            if (writing.get() == true) return
            writing.set(true)
            val token = Binder.clearCallingIdentity()
            try {
                val store = store()
                if (!recovered) recover(properties, store)
                val policy = FakeLoc.runtimeSnapshots.current().configuration
                val global = policy.enabled && policy.carrierIdentityEnabled &&
                    policy.carrierIdentity?.mode == CarrierIdentityMode.GLOBAL
                val profiles = if (global) (0..1).mapNotNull { phone ->
                    CarrierIdentityRuntime.profileForCall("getSimOperatorNumericForPhone", listOf(phone), 1001)
                }.distinctBy { it.slot } else emptyList()
                fields.forEach { (field, value) ->
                    val owner = ownership.getValue(field)
                    if (profiles.isEmpty() && !owner.ownsValues()) return@forEach
                    val current = read(properties, field)
                    val next = profiles.mapNotNull { profile ->
                        value(profile)?.let { profile.slot - 1 to it }
                    }.toMap()
                    if (next.isEmpty() && !owner.ownsValues()) return@forEach
                    val before = owner.snapshot()
                    val proposal = owner.plan(current, next)
                    if (proposal.values == current && proposal.state == before) return@forEach
                    store.put(field, com.system.location.service.core.carrier.CarrierPropertyJournalEntry(before, proposal.state))
                    if (proposal.values != current)
                        properties.getMethod(field, List::class.java).invoke(null, proposal.values)
                    owner.restore(proposal.state)
                    store.put(field, if (owner.ownsValues())
                        com.system.location.service.core.carrier.CarrierPropertyJournalEntry(owner.snapshot()) else null)
                }
            } catch (failure: Exception) {
                journal = null
                recovered = false
                Logger.warn("Global carrier property recovery/apply unavailable: ${failure.javaClass.simpleName}")
            } finally {
                Binder.restoreCallingIdentity(token)
                writing.set(false)
            }
        }
    }
}
