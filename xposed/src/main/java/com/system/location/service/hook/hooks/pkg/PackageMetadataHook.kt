package com.system.location.service.hook.hooks.pkg

import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.os.Binder
import android.os.Bundle
import com.system.location.service.core.privacy.XposedMetadataVisibilityPolicy
import com.system.location.service.hook.scope.HookInstaller
import com.system.location.service.hook.utils.Logger
import io.github.libxposed.api.XposedInterface.Hooker

/**
 * P6-02 + work package 9: hide legacy Xposed meta-data from ordinary PackageManager callers.
 *
 * The module's hooks are already installed before this runs; LSPosed/system callers (UID 0/1000)
 * keep the real meta-data, while third-party detectors see a sanitized copy. Consistency across
 * query entry points matters more than any single one: a detector that finds
 * `getApplicationInfo` sanitized but `getPackageInfo(GET_META_DATA)` / `getInstalledPackages`
 * still carrying `xposedmodule` would expose the hiding layer itself, so all three surfaces are
 * sanitized here.
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
                HookInstaller.hookAllMethods(type, method, Hooker { chain ->
                    sanitizeResult(chain.proceed())
                })
            }
            listOf("getPackageInfo", "getPackageInfoAsUser").forEach { method ->
                HookInstaller.hookAllMethods(type, method, Hooker { chain ->
                    sanitizePackageInfoResult(chain.proceed())
                })
            }
            listOf(
                "getInstalledPackages", "getInstalledPackagesAsUser",
                "getInstalledApplications", "getInstalledApplicationsAsUser",
            ).forEach { method ->
                HookInstaller.hookAllMethods(type, method, Hooker { chain ->
                    sanitizeListResult(chain.proceed())
                })
            }
        }
    }

    private fun sanitizeResult(result: Any?): Any? {
        val callerUid = runCatching { Binder.getCallingUid() }.getOrDefault(-1)
        val info = result as? ApplicationInfo ?: return result
        if (!XposedMetadataVisibilityPolicy.shouldHide(info.packageName, callerUid)) return result
        return sanitize(info)
    }

    private fun sanitizePackageInfoResult(result: Any?): Any? {
        val callerUid = runCatching { Binder.getCallingUid() }.getOrDefault(-1)
        val info = result as? PackageInfo ?: return result
        val applicationInfo = info.applicationInfo ?: return result
        if (!XposedMetadataVisibilityPolicy.shouldHide(applicationInfo.packageName, callerUid)) {
            return result
        }
        // Copy before rewriting: PackageManager may hand out cached objects that system callers
        // (including LSPosed itself) still read.
        val copy = copyPackageInfo(info)
        copy.applicationInfo = sanitize(ApplicationInfo(applicationInfo))
        return copy
    }

    /**
     * Installed-package queries return `ParceledListSlice<ApplicationInfo|PackageInfo>` on
     * Android 12+. The slice is unwrapped, only rewritten when the module is actually present
     * (cheap early exit for the common case), and rewrapped with the same slice type. If the
     * unwrap or rewrap is impossible on a given ROM, the original result is returned so the
     * behavior degrades to the pre-hook baseline instead of crashing the query.
     */
    private fun sanitizeListResult(result: Any?): Any? {
        val callerUid = runCatching { Binder.getCallingUid() }.getOrDefault(-1)
        val list = runCatching {
            result!!.javaClass.getMethod("getList").invoke(result) as? List<*>
        }.getOrNull() ?: return result
        fun targetOf(entry: Any?): String? = when (entry) {
            is ApplicationInfo -> entry.packageName
            is PackageInfo -> entry.applicationInfo?.packageName ?: entry.packageName
            else -> null
        }
        val hasModuleEntry = list.any { entry ->
            targetOf(entry)?.let { XposedMetadataVisibilityPolicy.shouldHide(it, callerUid) } == true
        }
        if (!hasModuleEntry) return result
        val sanitized = list.map { entry ->
            when (entry) {
                is PackageInfo ->
                    if (entry.applicationInfo?.packageName?.let {
                            XposedMetadataVisibilityPolicy.shouldHide(it, callerUid)
                        } == true) {
                        copyPackageInfo(entry).also { copy ->
                            copy.applicationInfo = sanitize(ApplicationInfo(entry.applicationInfo))
                        }
                    } else entry
                is ApplicationInfo ->
                    if (XposedMetadataVisibilityPolicy.shouldHide(entry.packageName, callerUid)) {
                        sanitize(entry)
                    } else entry
                else -> entry
            }
        }
        return runCatching {
            result!!.javaClass.getConstructor(List::class.java).newInstance(sanitized)
        }.onFailure {
            Logger.warn("PackageMetadataHook: cannot rewrap ${result!!.javaClass.name}; keeping original list")
        }.getOrNull() ?: result
    }

    /** Field-wise shallow copy: [PackageInfo] has no copy constructor and mutability is required. */
    private fun copyPackageInfo(source: PackageInfo): PackageInfo {
        val copy = PackageInfo()
        for (field in PackageInfo::class.java.fields) {
            runCatching { field.set(copy, field.get(source)) }
        }
        return copy
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
