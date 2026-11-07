package com.system.location.service.hook.hooks.telephony

import android.os.Build
import android.os.Bundle
import android.os.Parcel
import android.telephony.CellIdentity
import android.telephony.CellIdentityCdma
import android.telephony.CellInfo
import android.telephony.CellInfoCdma
import android.telephony.CellSignalStrengthCdma
import android.telephony.NeighboringCellInfo
import android.telephony.SignalStrength
import com.system.location.service.hook.runtime.Reflection
import com.system.location.service.hook.utils.BinderUtils
import com.system.location.service.hook.utils.FakeLoc
import com.system.location.service.hook.utils.Logger
import io.github.libxposed.api.XposedInterface.Hooker
import com.system.location.service.hook.utils.hookAllMethods
import com.system.location.service.hook.utils.hook
import com.system.location.service.hook.utils.hookMethod
import com.system.location.service.hook.utils.onceHookMethod
import java.lang.reflect.Modifier

object TelephonyHook: BaseTelephonyHook() {
    operator fun invoke(classLoader: ClassLoader) {
        if(!initDivineService("TelephonyHook")) {
            Logger.error("Failed to init mock service in TelephonyHook")
            return
        }

        Reflection.findClassIfExists("com.android.phone.PhoneInterfaceManager", classLoader)?.let { CellInfoSynthesis.hook(it) }
        PhoneTypeHooks.hook(classLoader); SimOperatorHooks.hook(classLoader)
        if (!FakeLoc.needDowngradeToCdma) return
        val cPhoneInterfaceManager = Reflection.findClassIfExists("com.android.phone.PhoneInterfaceManager", classLoader)
            ?: return

        val hookGetPhoneTyp = Hooker { chain ->
            if (FakeLoc.enable && !BinderUtils.isSystemAppsCall()) {
                if (FakeLoc.enableDebugLog) {
                    Logger.debug("getActivePhoneType: injected!")
                }

                return@Hooker 2
            }
            chain.proceed()
        }

        if(cPhoneInterfaceManager.hookAllMethods("getActivePhoneType", hookGetPhoneTyp).isEmpty()) {
            Logger.error("Hook PhoneInterfaceManager.getActivePhoneType failed")
        }
        if(cPhoneInterfaceManager.hookAllMethods("getActivePhoneTypeForSlot", hookGetPhoneTyp).isEmpty()) {
            Logger.warn("Hook PhoneInterfaceManager.getActivePhoneTypeForSlot failed")
        }

        cPhoneInterfaceManager.declaredMethods.find { it.name == "getAllCellInfo" }?.let {
            val hookGetAllCellInfo = Hooker { chain ->
                val original = chain.proceed()
                if (FakeLoc.enableDebugLog) {
                    Logger.debug("getAllCellInfo: injected! caller = ${BinderUtils.getCallerUid()}")
                }

                if (FakeLoc.enable && !BinderUtils.isSystemAppsCall()) {
                    val cResult = arrayListOf<CellInfo>()
                    val cellInfo = kotlin.runCatching {
                        CellInfoCdma::class.java.getConstructor().newInstance().also {
                            Reflection.callMethod(it, "setRegistered", true)
                            Reflection.callMethod(it, "setTimeStamp", System.nanoTime())
                            Reflection.callMethod(it, "setCellConnectionStatus", 0)
                        }
                    }.getOrElse {
                        CellInfoCdma::class.java.getConstructor(
                            Int::class.java,
                            Boolean::class.java,
                            Long::class.java,
                            CellIdentityCdma::class.java,
                            CellSignalStrengthCdma::class.java
                        ).newInstance(0, true, System.nanoTime(), CellIdentityCdma::class.java.newInstance(), CellSignalStrengthCdma::class.java.newInstance())
                    }
                    cResult.add(cellInfo)
                    cResult
                } else {
                    original
                }
            }
            com.system.location.service.hook.scope.HookInstaller.hookMethod(it, hookGetAllCellInfo)
        }

        if(com.system.location.service.hook.scope.HookInstaller.hookAllMethods(cPhoneInterfaceManager, "getCellLocation", Hooker { chain ->
                val original = chain.proceed()
                if (!FakeLoc.enable || BinderUtils.isSystemAppsCall()) {
                    return@Hooker original
                }
                if (FakeLoc.enableDebugLog) {
                    Logger.debug("${chain.executable.name}: injected!")
                }

                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || original?.javaClass?.name == "android.os.Bundle") {
                    Bundle().apply {
                        putInt("cid", Int.MAX_VALUE)
                        putInt("lac", Int.MAX_VALUE)
                        putInt("psc", Int.MAX_VALUE)
                        putInt("baseStationLatitude", (FakeLoc.latitude * 14400.0).toInt())
                        putInt("baseStationLongitude", (FakeLoc.longitude * 14400.0).toInt())
                        putBoolean("empty", false)
                        putBoolean("emptyParcel", false)
                        putInt("mFlags", 1536)
                        putBoolean("parcelled", false)
                        putInt("baseStationId", Int.MAX_VALUE)
                        putInt("systemId", Int.MAX_VALUE)
                        putInt("networkId", Int.MAX_VALUE)
                        putInt("size", 0)
                    }
                } else {
                    CellIdentityCdma::class.java.getConstructor(
                        Int::class.java,
                        Int::class.java,
                        Int::class.java,
                        Int::class.java,
                        Int::class.java,
                        String::class.java,
                        String::class.java
                    ).newInstance(
                        Int.MAX_VALUE,
                        Int.MAX_VALUE,
                        Int.MAX_VALUE,
                        (FakeLoc.latitude * 14400.0).toInt(),
                        (FakeLoc.longitude * 14400.0).toInt(),
                        null, null
                    )
                }
            }).isEmpty()) {
            Logger.error("Hook PhoneInterfaceManager.getCellLocation failed")
        }

        Hooker { chain ->
            if (FakeLoc.enable && !BinderUtils.isSystemAppsCall()) {
                if (FakeLoc.enableDebugLog) {
                    Logger.debug("getDataNetworkType: injected!")
                }
                return@Hooker 4
            }
            chain.proceed()
        }.let {
            cPhoneInterfaceManager.hookAllMethods("getDataNetworkType", it)
            cPhoneInterfaceManager.hookAllMethods("getNetworkType", it)
            cPhoneInterfaceManager.hookAllMethods("getDataNetworkTypeForSubscriber", it)
            cPhoneInterfaceManager.hookAllMethods("getNetworkTypeForSubscriber", it)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            if (cPhoneInterfaceManager.hookAllMethods("getNeighboringCellInfo", Hooker { chain ->
                if (!FakeLoc.enable || BinderUtils.isSystemAppsCall()) {
                    return@Hooker chain.proceed()
                }

                kotlin.runCatching {
                    val nCellInfo = NeighboringCellInfo::class.java.getConstructor().newInstance()
                    Reflection.setIntField(nCellInfo, "mRssi", -46)
                    Reflection.setIntField(nCellInfo, "mCid", -1)
                    Reflection.setIntField(nCellInfo, "mLac", -1)
                    Reflection.setIntField(nCellInfo, "mPsc", -1)
                    Reflection.setIntField(nCellInfo, "mNetworkType", 3)
                    listOf(nCellInfo)
                }.getOrElse {
                    arrayListOf()
                }
            }).isEmpty()) {
                Logger.error("Hook PhoneInterfaceManager.getNeighboringCellInfo failed")
            }
        }

        val cTelephonyRegistry = Reflection.findClassIfExists("com.android.server.TelephonyRegistry", classLoader)
        if (cTelephonyRegistry == null) {
            Logger.error("TelephonyRegistry not found")
        } else {
            hookTelephonyRegistry(cTelephonyRegistry)
        }

    }

    fun hookTelephonyRegistry(cTelephonyRegistry: Class<*>) {
        cTelephonyRegistry.declaredMethods.filter { (it.name == "listen" || it.name == "listenWithEventList") && !Modifier.isAbstract(it.modifiers) }
            .map {
                it to it.parameterTypes.indexOfFirst { typ -> typ.simpleName == "IPhoneStateListener" }
            }.forEach {
                val (m, idx) = it
                if (idx == -1) {
                    Logger.error("IPhoneStateListener not found")
                    return@forEach
                }
                m.hook(Hooker { chain ->
                    if (!FakeLoc.enable || BinderUtils.isSystemAppsCall()) {
                        return@Hooker chain.proceed()
                    }

                    val listener = chain.args[idx] as Any
                    val hasHookOnCellLocationChanged = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        listener.javaClass.onceHookMethod("onCellLocationChanged", CellIdentity::class.java, callback = Hooker callback@ { callbackChain ->
                            if (FakeLoc.enable) {
                                return@callback CellIdentityCdma::class.java.getConstructor(
                                    Int::class.java,
                                    Int::class.java,
                                    Int::class.java,
                                    Int::class.java,
                                    Int::class.java,
                                    String::class.java,
                                    String::class.java
                                ).newInstance(
                                    Int.MAX_VALUE,
                                    Int.MAX_VALUE,
                                    Int.MAX_VALUE,
                                    (FakeLoc.latitude * 14400.0).toInt(),
                                    (FakeLoc.longitude * 14400.0).toInt(),
                                    null, null
                                )
                            }
                            callbackChain.proceed()
                        })
                    } else {
                        listener.javaClass.onceHookMethod("onCellLocationChanged", Bundle::class.java, callback = Hooker callback@ { callbackChain ->
                            if (FakeLoc.enable) {
                                return@callback Bundle().apply {
                                    putInt("cid", Int.MAX_VALUE)
                                    putInt("lac", Int.MAX_VALUE)
                                    putInt("psc", Int.MAX_VALUE)
                                    putInt("baseStationLatitude", (FakeLoc.latitude * 14400.0).toInt())
                                    putInt("baseStationLongitude", (FakeLoc.longitude * 14400.0).toInt())
                                    putBoolean("empty", false)
                                    putBoolean("emptyParcel", false)
                                    putInt("mFlags", 1536)
                                    putBoolean("parcelled", false)
                                    putInt("baseStationId", Int.MAX_VALUE)
                                    putInt("systemId", Int.MAX_VALUE)
                                    putInt("networkId", Int.MAX_VALUE)
                                    putInt("size", 0)
                                }
                            }
                            callbackChain.proceed()
                        })
                    } != null
                    if (!hasHookOnCellLocationChanged) {
                        Logger.error("Hook onCellLocationChanged failed")
                    }
                    val suppressSignal = Hooker { callbackChain ->
                        if (FakeLoc.enable) null else callbackChain.proceed()
                    }
                    listener.javaClass.onceHookMethod("onSignalStrengthChanged", Int::class.java, callback = suppressSignal)
                    listener.javaClass.onceHookMethod("onSignalStrengthsChanged", SignalStrength::class.java, callback = suppressSignal)
                    chain.proceed()
                })
            }

        cTelephonyRegistry.hookMethod("notifyCellInfo", List::class.java, callback = Hooker { chain ->
            val args = chain.args.toTypedArray()
            if (!FakeLoc.enable || BinderUtils.isSystemAppsCall()) {
                return@Hooker chain.proceed()
            }

            if (FakeLoc.enableDebugLog) {
                Logger.debug("notifyCellInfo: injected!")
            }

            val cellInfos = arrayListOf<CellInfo>()
            val cellInfo = kotlin.runCatching {
                CellInfoCdma::class.java.getConstructor().newInstance().also {
                    Reflection.callMethod(it, "setRegistered", true)
                    Reflection.callMethod(it, "setTimeStamp", System.nanoTime())
                    Reflection.callMethod(it, "setCellConnectionStatus", 0)
                }
            }.getOrElse {
                CellInfoCdma::class.java.getConstructor(
                    Int::class.java,
                    Boolean::class.java,
                    Long::class.java,
                    CellIdentityCdma::class.java,
                    CellSignalStrengthCdma::class.java
                ).newInstance(0, true, System.nanoTime(), CellIdentityCdma::class.java.newInstance(), CellSignalStrengthCdma::class.java.newInstance())
            }
            cellInfos.add(cellInfo)

            args[0] = cellInfos
            chain.proceed(args)
        })

        cTelephonyRegistry.hookMethod("notifyCellInfoForSubscriber", Int::class.java, List::class.java, callback = Hooker { chain ->
            val args = chain.args.toTypedArray()
            if (!FakeLoc.enable || BinderUtils.isSystemAppsCall()) {
                return@Hooker chain.proceed()
            }

            if (FakeLoc.enableDebugLog) {
                Logger.debug("notifyCellInfoForSubscriber: injected!")
            }

            val cellInfos = arrayListOf<CellInfo>()
            val cellInfo = kotlin.runCatching {
                CellInfoCdma::class.java.getConstructor().newInstance().also {
                    Reflection.callMethod(it, "setRegistered", true)
                    Reflection.callMethod(it, "setTimeStamp", System.nanoTime())
                    Reflection.callMethod(it, "setCellConnectionStatus", 0)
                }
            }.getOrElse {
                CellInfoCdma::class.java.getConstructor(
                    Int::class.java,
                    Boolean::class.java,
                    Long::class.java,
                    CellIdentityCdma::class.java,
                    CellSignalStrengthCdma::class.java
                ).newInstance(0, true, System.nanoTime(), CellIdentityCdma::class.java.newInstance(), CellSignalStrengthCdma::class.java.newInstance())
            }
            cellInfos.add(cellInfo)

            args[1] = cellInfos
            chain.proceed(args)
        })

        cTelephonyRegistry.hookMethod("notifyCellLocation", Bundle::class.java, callback = Hooker { chain ->
            val args = chain.args.toTypedArray()
            if (!FakeLoc.enable || BinderUtils.isSystemAppsCall()) {
                return@Hooker chain.proceed()
            }

            if (FakeLoc.enableDebugLog) {
                Logger.debug("notifyCellLocation: injected!")
            }

            args[0] = Bundle().apply {
                putInt("cid", Int.MAX_VALUE)
                putInt("lac", Int.MAX_VALUE)
                putInt("psc", Int.MAX_VALUE)
                putInt("baseStationLatitude", (FakeLoc.latitude * 14400.0).toInt())
                putInt("baseStationLongitude", (FakeLoc.longitude * 14400.0).toInt())
                putBoolean("empty", false)
                putBoolean("emptyParcel", false)
                putInt("mFlags", 1536)
                putBoolean("parcelled", false)
                putInt("baseStationId", Int.MAX_VALUE)
                putInt("systemId", Int.MAX_VALUE)
                putInt("networkId", Int.MAX_VALUE)
                putInt("size", 0)
            }
            chain.proceed(args)
        })
        cTelephonyRegistry.hookMethod("notifyCellLocationForSubscriber", Int::class.java, Bundle::class.java, callback = Hooker { chain ->
            val args = chain.args.toTypedArray()
            if (!FakeLoc.enable || BinderUtils.isSystemAppsCall()) {
                return@Hooker chain.proceed()
            }

            if (FakeLoc.enableDebugLog) {
                Logger.debug("notifyCellLocationForSubscriber: injected!")
            }

            args[1] = Bundle().apply {
                putInt("cid", Int.MAX_VALUE)
                putInt("lac", Int.MAX_VALUE)
                putInt("psc", Int.MAX_VALUE)
                putInt("baseStationLatitude", (FakeLoc.latitude * 14400.0).toInt())
                putInt("baseStationLongitude", (FakeLoc.longitude * 14400.0).toInt())
                putBoolean("empty", false)
                putBoolean("emptyParcel", false)
                putInt("mFlags", 1536)
                putBoolean("parcelled", false)
                putInt("baseStationId", Int.MAX_VALUE)
                putInt("systemId", Int.MAX_VALUE)
                putInt("networkId", Int.MAX_VALUE)
                putInt("size", 0)
            }
            chain.proceed(args)
        })
    }

    @Suppress("LocalVariableName")
    fun hookSubOnTransact(classLoader: ClassLoader) {
        val cISub = Reflection.findClassIfExists("com.android.internal.telephony.ISub\$Stub", classLoader)
        if (cISub == null) {
            Logger.error("ISub.Stub not found")
            return
        }

        val subClassName = "com.android.internal.telephony.ISub"
        val TRANSACTION_getPhoneId = kotlin.runCatching { Reflection.getStaticIntField(cISub, "TRANSACTION_getPhoneId") }.getOrDefault(-1)
        val TRANSACTION_getSimStateForSlotIndex = kotlin.runCatching { Reflection.getStaticIntField(cISub, "TRANSACTION_getSimStateForSlotIndex") }.getOrDefault(-1)
        val TRANSACTION_isActiveSubId = kotlin.runCatching { Reflection.getStaticIntField(cISub, "TRANSACTION_isActiveSubId") }.getOrDefault(-1)
        val TRANSACTION_getNetworkCountryIsoForPhone = kotlin.runCatching { Reflection.getStaticIntField(cISub, "TRANSACTION_getNetworkCountryIsoForPhone") }.getOrDefault(-1)
        val TRANSACTION_getSimStateForSubscriber = kotlin.runCatching { Reflection.getStaticIntField(cISub, "TRANSACTION_getSimStateForSubscriber") }.getOrDefault(-1)
        val TRANSACTION_getSimStateForSlotIdx = kotlin.runCatching { Reflection.getStaticIntField(cISub, "TRANSACTION_getSimStateForSlotIdx") }.getOrDefault(-1)

        val hookOnTransact = Hooker { chain ->
            val args = chain.args
            if (!FakeLoc.enable || BinderUtils.isSystemAppsCall()) {
                return@Hooker chain.proceed()
            }

            val code = args[0] as Int
            val data = args[1] as Parcel
            val reply = args[2] as Parcel

            if (code == -1) return@Hooker chain.proceed()

            when (code) {
                TRANSACTION_isActiveSubId -> {
                    data.enforceInterface(subClassName)
                    data.readInt()
                    reply.writeNoException()
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        reply.writeBoolean(true)
                    } else {
                        reply.writeInt(1)
                    }
                    return@Hooker true
                }
                TRANSACTION_getSimStateForSlotIndex -> {
                    data.enforceInterface(subClassName)
                    data.readInt()
                    reply.writeNoException()
                    reply.writeInt(5)
                    return@Hooker true
                }
                TRANSACTION_getSimStateForSubscriber -> {
                    data.enforceInterface(subClassName)
                    data.readInt()
                    reply.writeNoException()
                    reply.writeInt(5)
                    return@Hooker true
                }
                TRANSACTION_getSimStateForSlotIdx -> {
                    data.enforceInterface(subClassName)
                    data.readInt()
                    reply.writeNoException()
                    reply.writeInt(5)
                    return@Hooker true
                }
                TRANSACTION_getNetworkCountryIsoForPhone -> {
                    data.enforceInterface(subClassName)
                    data.readInt()
                    reply.writeNoException()
                    reply.writeString("CHN")
                    return@Hooker true
                }
                TRANSACTION_getPhoneId -> {
                    data.enforceInterface(subClassName)
                    data.readInt()
                    reply.writeNoException()
                    reply.writeInt(0)
                    return@Hooker true
                }
            }
            chain.proceed()
        }
        if(cISub.hookAllMethods("onTransact", hookOnTransact).isEmpty()) {
            Logger.error("Hook ISub.Stub.onTransact failed")
        }
    }

}
