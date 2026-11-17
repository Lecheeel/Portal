package com.system.location.service.hook.hooks.telephony

import android.os.Binder
import android.os.Parcel
import android.telephony.SubscriptionInfo
import android.telephony.SubscriptionManager
import com.system.location.service.core.carrier.CarrierVirtualIdentity
import com.system.location.service.core.carrier.SimCarrierProfile
import com.system.location.service.hook.runtime.Reflection
import com.system.location.service.hook.utils.Logger
import com.system.location.service.hook.utils.hookAllMethods
import io.github.libxposed.api.XposedInterface.Hooker

/** Preserves real IDs while adding a bounded virtual subscription only when the caller may see it. */
internal object CarrierSubscriptionHook {
    private val countingPhysical = ThreadLocal.withInitial { false }
    private val countMethods = setOf("getActiveSubscriptionInfoCount", "getActiveSubInfoCount")
    private val recentVirtual = LinkedHashMap<Pair<Int, Int>, Long>()
    private fun remember(virtuals: List<SimCarrierProfile>, uid: Int) = synchronized(recentVirtual) {
        val now = android.os.SystemClock.elapsedRealtime()
        recentVirtual.entries.removeAll { it.value < now }
        virtuals.forEach { recentVirtual[CarrierVirtualIdentity.subscriptionId(it) to (uid / 100000)] = now + 30_000 }
        while (recentVirtual.size > 128) recentVirtual.remove(recentVirtual.keys.first())
    }
    private fun knownVirtual(subId: Int): Boolean {
        val uid = Binder.getCallingUid()
        val active = CarrierIdentityRuntime.profileForCall("getActiveSubscriptionInfo", listOf(subId))
        if (active?.virtual == true) return true
        return synchronized(recentVirtual) {
            (recentVirtual[subId to (uid / 100000)] ?: 0L) > android.os.SystemClock.elapsedRealtime()
        }
    }
    fun install(loader: ClassLoader) {
        listOf("com.android.internal.telephony.SubscriptionController",
            "com.android.internal.telephony.subscription.SubscriptionManagerService").forEach { name ->
            val type = Reflection.findClassIfExists(name, loader) ?: return@forEach
            type.hookAllMethods("getSubscriptionUserHandle", Hooker { chain ->
                try { chain.proceed() } catch (failure: IllegalArgumentException) {
                    val subId = chain.args.firstOrNull() as? Int ?: throw failure
                    if (failure.message != "getSubscriptionUserHandle: Invalid subId: $subId" ||
                        !CarrierIdentifierAccess.permitted()) throw failure
                    if (!knownVirtual(subId)) throw failure
                    android.os.UserHandle.getUserHandleForUid(Binder.getCallingUid())
                }
            })
            type.hookAllMethods("setDisplayNumber", Hooker { chain ->
                try { chain.proceed() } catch (failure: IllegalArgumentException) {
                    val subId = chain.args.filterIsInstance<Int>().firstOrNull() ?: throw failure
                    if (failure.message != "Subscription doesn't exist. subId=$subId, columnName=number" ||
                        !CarrierIdentifierAccess.permitted() || !knownVirtual(subId)) throw failure
                    0 // No physical subscription record was updated.
                }
            })
            val hook = Hooker { chain ->
                if (CarrierHardwareSubscriptionHook.hardwareQueryActive()) return@Hooker chain.proceed()
                if (CarrierIdentityRuntime.isResolving() || countingPhysical.get() == true) return@Hooker chain.proceed()
                if (Binder.getCallingUid() in setOf(1000, 1001) &&
                    chain.args.any { it == CarrierIdentityRuntime.PHYSICAL_QUERY_TAG }) return@Hooker chain.proceed()
                val original = if (chain.executable.name in countMethods) {
                    countingPhysical.set(true)
                    try { chain.proceed() } finally { countingPhysical.set(false) }
                } else chain.proceed()
                if (chain.executable.name == "getAccessibleSubscriptionInfoList") return@Hooker original
                val virtuals = CarrierIdentityRuntime.virtualProfilesForCall(Binder.getCallingUid())
                remember(virtuals, Binder.getCallingUid())
                val requested = chain.args.firstOrNull() as? Int
                val topology = com.system.location.service.core.carrier.CarrierVirtualTopology
                when (chain.executable.name) {
                    "getPhoneId", "getSlotIndex" -> requested?.let {
                        topology.slotForSubscription(virtuals, it)?.minus(1)
                    } ?: original
                    "getSubId", "getSubIds", "getSubscriptionId" -> {
                        val id = requested?.let { topology.subscriptionForPhone(virtuals, it) }
                        if (id == null) original else when ((chain.executable as java.lang.reflect.Method).returnType) {
                            IntArray::class.java -> intArrayOf(id)
                            Int::class.javaPrimitiveType -> id
                            else -> original
                        }
                    }
                    "isActiveSubId", "isActiveSubscriptionId" ->
                        if (requested != null && topology.slotForSubscription(virtuals, requested) != null)
                            true else original
                    "getActiveSubIdList" -> if (original is IntArray)
                        topology.activeIds(original, virtuals) else original
                    "getActiveSubscriptionInfoList",
                    "getAvailableSubscriptionInfoList", "getSelectableSubscriptionInfoList" ->
                        if (original is List<*>) mergeList(original, virtuals) else original
                    "getActiveSubscriptionInfo" -> {
                        val id = chain.args.firstOrNull() as? Int
                        virtualForSubId(virtuals, id) ?: overlay(original)
                    }
                    "getActiveSubscriptionInfoForIccId" -> {
                        val iccId = chain.args.firstOrNull() as? String
                        virtuals.firstOrNull { CarrierVirtualIdentity.iccid(it) == iccId }
                            ?.let(CarrierVirtualSubscription::info) ?: overlay(original)
                    }
                    "getActiveSubscriptionInfoForSimSlotIndex" -> {
                        val slot = (chain.args.firstOrNull() as? Int)?.plus(1)
                        virtuals.firstOrNull { it.slot == slot }
                            ?.let(CarrierVirtualSubscription::info) ?: overlay(original)
                    }
                    "getActiveSubscriptionInfoCount", "getActiveSubInfoCount" -> {
                        val physical = (original as? Int) ?: 0
                        physical + virtuals.size
                    }
                    "getActiveSubscriptionInfoCountMax" ->
                        maxOf((original as? Int) ?: 0, virtuals.maxOfOrNull { it.slot } ?: 0)
                    "getDefaultSubId", "getDefaultDataSubId", "getDefaultVoiceSubId",
                    "getDefaultSmsSubId" -> {
                        val current = (original as? Int) ?: SubscriptionManager.INVALID_SUBSCRIPTION_ID
                        if (SubscriptionManager.isValidSubscriptionId(current)) current
                        else virtuals.firstOrNull()?.let { CarrierVirtualIdentity.subscriptionId(it) }
                            ?: current
                    }
                    else -> overlay(original)
                }
            }
            listOf("getPhoneId", "getSlotIndex", "getSubId", "getSubIds", "getSubscriptionId",
                "isActiveSubId", "isActiveSubscriptionId", "getActiveSubIdList",
                "getActiveSubscriptionInfo", "getActiveSubscriptionInfoForIccId",
                "getActiveSubscriptionInfoForSimSlotIndex", "getActiveSubscriptionInfoList",
                "getAccessibleSubscriptionInfoList", "getAvailableSubscriptionInfoList",
                "getSelectableSubscriptionInfoList", "getActiveSubscriptionInfoCount", "getActiveSubInfoCount",
                "getActiveSubscriptionInfoCountMax", "getDefaultSubId", "getDefaultDataSubId",
                "getDefaultVoiceSubId", "getDefaultSmsSubId")
                .forEach { method -> type.hookAllMethods(method, hook) }
        }
    }

    private fun mergeList(original: Any?, virtuals: List<SimCarrierProfile>): List<SubscriptionInfo> {
        val physical = (original as? List<*>).orEmpty().mapNotNull { it as? SubscriptionInfo }
            .map(::overlay)
        val physicalSlots = physical.map { it.simSlotIndex + 1 }.toSet()
        val extra = virtuals.filter { it.slot !in physicalSlots }.mapNotNull(CarrierVirtualSubscription::info)
        return physical + extra
    }

    private fun virtualForSubId(
        virtuals: List<SimCarrierProfile>,
        subId: Int?,
    ): SubscriptionInfo? {
        val slot = subId?.let { CarrierVirtualIdentity.slotForSubscriptionId(it, virtuals) } ?: return null
        return virtuals.firstOrNull { it.slot == slot }?.let(CarrierVirtualSubscription::info)
    }

    private fun overlay(original: Any?): Any? = when (original) {
        is SubscriptionInfo -> overlay(original)
        is List<*> -> original.map { item -> if (item is SubscriptionInfo) overlay(item) else item }
        else -> original
    }

    private fun overlay(info: SubscriptionInfo): SubscriptionInfo {
        val profile = CarrierIdentityRuntime.profileForCall("getActiveSubscriptionInfo",
            listOf(info.subscriptionId)) ?: return info
        return runCatching { copyWithIdentity(info, profile) }.getOrElse {
            Logger.warn("Subscription identity overlay unavailable: ${it.javaClass.simpleName}")
            info
        }
    }

    internal fun copyWithIdentity(info: SubscriptionInfo, profile: SimCarrierProfile): SubscriptionInfo {
        val parcel = Parcel.obtain()
        val copy = try {
            info.writeToParcel(parcel, 0)
            parcel.setDataPosition(0)
            SubscriptionInfo.CREATOR.createFromParcel(parcel)
        } finally { parcel.recycle() }
        Reflection.setObjectField(copy, "mCarrierName", profile.carrierName)
        Reflection.setObjectField(copy, "mDisplayName", profile.operatorName)
        Reflection.setObjectField(copy, "mCountryIso", profile.countryIso.lowercase(java.util.Locale.ROOT))
        Reflection.setObjectField(copy, "mMcc", profile.mcc.toString().padStart(3, '0'))
        Reflection.setObjectField(copy, "mMnc", profile.mnc.toString().padStart(profile.mncDigits, '0'))
        if (profile.phoneNumber.isNotEmpty()) Reflection.setObjectField(copy, "mNumber",
            if (CarrierPhoneNumberAccess.permitted()) profile.phoneNumber else "")
        return copy
    }
}
