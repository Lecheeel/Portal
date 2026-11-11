package com.system.location.service.hook.utils

import android.Manifest
import android.app.AppOpsManager
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Binder
import android.provider.Settings

/**
 * Read-only checks for hooks that must stay on the framework's success path.
 *
 * The real value of these hooks is replacing data after Android has decided that the caller is
 * allowed to receive it. Returning a synthetic value before that decision makes a no-permission
 * application receive location/Wi-Fi/cell data, which is both a security bug and the easiest
 * possible "is a hook installed?" probe.
 */
internal object LocationCallerGuard {

    enum class Access { FINE, COARSE, DENIED }

    /** Permission + AppOps check for callers of a void/synchronous API. */
    fun access(): Access {
        val context = BinderUtils.getSystemContext() ?: return Access.FINE
        val uid = Binder.getCallingUid()
        val pid = Binder.getCallingPid()
        val fine = context.checkPermission(Manifest.permission.ACCESS_FINE_LOCATION, pid, uid) ==
            PackageManager.PERMISSION_GRANTED
        val coarse = context.checkPermission(Manifest.permission.ACCESS_COARSE_LOCATION, pid, uid) ==
            PackageManager.PERMISSION_GRANTED
        if (!fine && !coarse) return Access.DENIED
        val packageName = runCatching {
            context.packageManager.getPackagesForUid(uid)?.firstOrNull()
        }.getOrNull() ?: return Access.DENIED
        val ops = context.getSystemService(AppOpsManager::class.java) ?: return Access.FINE
        fun allowed(op: String): Boolean = runCatching {
            ops.unsafeCheckOpNoThrow(op, uid, packageName) == AppOpsManager.MODE_ALLOWED
        }.getOrDefault(true)
        return when {
            fine && allowed(AppOpsManager.OPSTR_FINE_LOCATION) -> Access.FINE
            coarse && allowed(AppOpsManager.OPSTR_COARSE_LOCATION) -> Access.COARSE
            else -> Access.DENIED
        }
    }

    fun canReadLocation(): Boolean = access() != Access.DENIED

    fun isCoarse(): Boolean = access() == Access.COARSE

    /**
     * The real user location switch. Read through Settings instead of LocationManager so the
     * module's own availability hook cannot answer this check for us.
     */
    fun isLocationEnabled(): Boolean {
        val context = BinderUtils.getSystemContext() ?: return true
        // LocationAvailabilityHook forwards the real framework answer, so this stays truthful.
        runCatching {
            context.getSystemService(LocationManager::class.java)?.isLocationEnabled()
        }.getOrNull()?.let { return it }
        val mode = runCatching {
            Settings.Secure.getInt(
                context.contentResolver,
                Settings.Secure.LOCATION_MODE,
                Settings.Secure.LOCATION_MODE_OFF,
            )
        }.getOrDefault(Settings.Secure.LOCATION_MODE_OFF)
        return mode != Settings.Secure.LOCATION_MODE_OFF
    }
}
