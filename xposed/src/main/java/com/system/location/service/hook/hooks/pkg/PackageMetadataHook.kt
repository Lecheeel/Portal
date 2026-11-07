package com.system.location.service.hook.hooks.pkg

import android.content.pm.ApplicationInfo
import android.os.Binder
import android.os.Bundle
import com.system.location.service.core.privacy.XposedMetadataVisibilityPolicy
import com.system.location.service.hook.scope.HookInstaller
import com.system.location.service.hook.utils.afterHook
import de.robv.android.xposed.XC_MethodHook

/**
 * P6-02: hide legacy Xposed meta-data from ordinary PackageManager callers.
 *
 * The module's hooks are already installed before this runs; LSPosed/system callers (UID 0/1000)
 * keep the real meta-data, while third-party detectors see a sanitized copy.
 */
object PackageMetadataHook {
    fun hook(classLoader: ClassLoader) {
        val names = listOf(
            "com.android.server.pm.PackageManagerService",
            "com.android.server.pm.PackageManagerService\$ComputerEngine",
        )
        names.forEach { name ->
            val type = runCatching { classLoader.loadClass(name) }.getOrNull() ?: return@forEach
            listOf("getApplicationInfo", "getApplicationInfoAsUser").forEach { method ->
                HookInstaller.hookAllMethods(type, method, afterHook { sanitizeResult() })
            }
        }
    }

    private fun XC_MethodHook.MethodHookParam.sanitizeResult() {
        val callerUid = runCatching { Binder.getCallingUid() }.getOrDefault(-1)
        val info = this.result as? ApplicationInfo ?: return
        if (!XposedMetadataVisibilityPolicy.shouldHide(info.packageName, callerUid)) return
        this.result = sanitize(info)
    }

    private fun sanitize(info: ApplicationInfo): ApplicationInfo {
        val copy = ApplicationInfo(info)
        copy.metaData = sanitizeBundle(info.metaData)
        return copy
    }

    private fun sanitizeBundle(source: Bundle?): Bundle? {
        val bundle = source?.let { Bundle(it) } ?: return null
        XposedMetadataVisibilityPolicy.HIDDEN_KEYS.forEach { key ->
            runCatching { bundle.remove(key) }
        }
        if (bundle.isEmpty) return Bundle()
        return bundle
    }
}
