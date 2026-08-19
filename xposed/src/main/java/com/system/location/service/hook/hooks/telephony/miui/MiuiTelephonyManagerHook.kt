package com.system.location.service.hook.hooks.telephony.miui

import com.system.location.service.hook.hooks.telephony.BaseTelephonyHook

object MiuiTelephonyManagerHook: BaseTelephonyHook() {
    operator fun invoke(classLoader: ClassLoader) {
//        val cMiuiTelephonyManager = XposedHelpers.findClassIfExists("com.miui.internal.telephony.BaseTelephonyManagerAndroidImpl", classLoader)
//        val cTelephonyManagerEx = XposedHelpers.findClassIfExists("miui.telephony.TelephonyManagerEx", classLoader)
//
//        if (FakeLocationConfig.DEBUG) {
//            println("[LocationService] MiuiTelephonyManager: $cMiuiTelephonyManager")
//            println("[LocationService] MiuiTelephonyManagerEx: $cTelephonyManagerEx")
//        }
//
//        cMiuiTelephonyManager?.let { clazz ->
//            println("[LocationService] found " + clazz.declaredMethods.mapNotNull {
//                if (it.returnType == CellLocation::class.java) {
//                    XposedBridge.hookMethod(it, hookGetCellLocation)
//                } else null
//            }.size + " methods to hook in MiuiTelephonyManager")
//        }
//
//        cTelephonyManagerEx?.let { clazz ->
//            println("[LocationService] found " + clazz.declaredMethods.mapNotNull {
//                if (it.returnType == CellLocation::class.java) {
//                    XposedBridge.hookMethod(it, hookGetCellLocation)
//                } else null
//            }.size + " methods to hook in MiuiTelephonyManagerEx")
//
//            var sizeGetNeighboringCellInfoMethod = XposedBridge.hookAllMethods(clazz, "getNeighboringCellInfo", hookGetNeighboringCellInfoList).size
//            sizeGetNeighboringCellInfoMethod += XposedBridge.hookAllMethods(clazz, "getNeighboringCellInfoForSlot", hookGetNeighboringCellInfoList).size
//            sizeGetNeighboringCellInfoMethod += XposedBridge.hookAllMethods(clazz, "getNeighboringCellInfoForSubscription", hookGetNeighboringCellInfoList).size
//            println("[LocationService] found $sizeGetNeighboringCellInfoMethod methods to hook in MiuiTelephonyManagerEx")
//        }
    }
}
