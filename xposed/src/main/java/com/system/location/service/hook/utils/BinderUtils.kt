@file:Suppress("LocalVariableName", "PrivateApi")
package com.system.location.service.hook.utils

import android.content.Context
import android.os.Binder
import android.os.Build
import android.content.pm.ApplicationInfo
import com.system.location.service.hook.security.CallerPolicy
import com.system.location.service.hook.security.CallerRole
import de.robv.android.xposed.XposedBridge


object BinderUtils {
    private fun getActivityContext(): Context? {
        // public static ActivityManagerService self()
        // frameworks/base/services/java/com/android/server/am/ActivityManagerService.java
        try {
            val cam = Class.forName("com.android.server.am.ActivityManagerService")
            val am = cam.getMethod("self").invoke(null) ?: return null
            val mContext = cam.getDeclaredField("mContext")
            mContext.isAccessible = true
            return mContext.get(am) as? Context
        } catch (e: Throwable) {
            return null
        }
    }

    fun getSystemContext(): Context? {
        try {
            val cActivityThread = Class.forName("android.app.ActivityThread")
            val activityThread = cActivityThread.getMethod("currentActivityThread")
                .invoke(null) ?: return null
            return (cActivityThread.getMethod("getSystemContext").invoke(activityThread) as? Context) ?: getActivityContext()
        } catch (e: Throwable) {
            e.printStackTrace()
        }
        return null
    }

    fun getUidPackageNames(context: Context = getSystemContext()!!, uid: Int = getCallerUid()): Array<String>? {
        val packageManager = context.packageManager
        return packageManager.getPackagesForUid(uid)
    }

    fun getCallerUid(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            kotlin.runCatching { Binder.getCallingUidOrThrow() }.getOrNull() ?: -1
        } else {
            Binder.getCallingUid()
        }
    }

    /**
     * Check whether the `LocationService` is started properly
     */
    fun isLocationProviderEnabled(uid: Int): Boolean {
        return callerRole(uid) != CallerRole.DENIED
    }

    fun callerRole(uid: Int = getCallerUid()): CallerRole = runCatching {
        val pm = getSystemContext()?.packageManager ?: return CallerRole.DENIED
        val packages = pm.getPackagesForUid(uid)?.toSet() ?: return CallerRole.DENIED
        val userId = uid / 100000
        // getApplicationInfo resolves in the system user's context; appId is stable across users.
        val controllerUid = runCatching {
            userId * 100000 + pm.getApplicationInfo(CallerPolicy.CONTROLLER_PACKAGE, 0).uid % 100000
        }.getOrNull()
        val systemPackages = packages.filterTo(mutableSetOf()) { name ->
            val info = pm.getApplicationInfo(name, 0)
            info.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
        }
        CallerPolicy.resolve(uid, packages, controllerUid, systemPackages)
    }.getOrDefault(CallerRole.DENIED)

    fun isSystemPackages(packageNames: String): Boolean {
        if (packageNames.contains("com.xiaomi.location.fused") ||
            packageNames.contains("com.xiaomi.metoknlp") ||
            //packageNames.contains("com.android.phone") ||
            packageNames.contains("com.android.location.fused")
            ) {
            return false
        }
        return packageNames.contains("com.android") ||
                packageNames.contains("com.miui") ||
                packageNames.contains("com.xiaomi") ||
                packageNames.contains("com.oplus") ||
                packageNames.contains("com.coloros") ||
                packageNames.contains("com.heytap") ||
                packageNames.contains("android.framework") ||
                packageNames.contains("com.qualcomm") ||
                packageNames.contains("com.google.android.permissioncontroller")
    }

    fun isSystemAppsCall(uid: Int = getCallerUid()): Boolean {
        if (uid > 10000) {
            val packageNames = kotlin.runCatching { getUidPackageNames(uid = uid)?.joinToString() }
                .getOrNull() ?: return true
            return isSystemPackages(packageNames)
        }
        return true
    }
}
