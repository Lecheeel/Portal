package com.system.location.service.hook.privacy

import android.app.AppOpsManager
import android.os.Binder
import io.github.libxposed.api.XposedInterface.Hooker
import com.system.location.service.hook.runtime.Reflection
import com.system.location.service.hook.scope.HookInstaller
import com.system.location.service.hook.utils.BinderUtils
import com.system.location.service.hook.utils.FakeLoc
import com.system.location.service.core.privacy.AppOpsVisibilityPolicy
import com.system.location.service.hook.utils.Logger

/**
 * Hides `OP_MOCK_LOCATION` from third-party callers that inspect another package's AppOps state
 * (P1-02, work package 9).
 *
 * The controller (Portal itself) still receives the real mode because it asks about its own UID;
 * system/shell callers (uid < 10000) and the controller's self-check are deliberately left
 * untouched. Two surfaces are covered:
 *
 * 1. `checkOperation(code, callingUid, callingPid, packageName[, rawMode])` — the AOSP signature
 *    carries the CALLER uid in `callingUid`; the queried package is identified by `packageName`
 *    and its uid is resolved here. (The previous implementation passed `callingUid` as the
 *    queried uid, so `queriedUid == callerUid` always failed the self-check rule and the hiding
 *    never fired.)
 * 2. `getOpsForPackage` / `getUidOps` — privileged enumerators (they need `PACKAGE_USAGE_STATS`
 *    or system privileges, but a detector may legitimately hold them). Baseline for a package
 *    that is not the mock provider is *absence* of the mock_location entry, so the entry is
 *    removed from the reply instead of being rewritten to a mode value.
 *
 * `AppOpsManager.PackageOps` / `Op` are @hide, so every touch goes through reflection and any
 * reflection failure falls back to the original reply (fail-open to the pre-hook baseline).
 */
object AppOpsHook {
    fun hook(classLoader: ClassLoader): Boolean {
        val service = Reflection.findClassIfExists("com.android.server.appop.AppOpsService", classLoader)
            ?: return false

        val checkCallback = Hooker { chain ->
            val original = chain.proceed()
            if (original !is Int) return@Hooker original
            val ints = chain.args.filterIsInstance<Int>()
            if (ints.isEmpty() || ints[0] != AppOpsVisibilityPolicy.OP_MOCK_LOCATION) {
                return@Hooker original
            }
            // AOSP signature: (code, callingUid, callingPid, packageName[, rawMode]).
            val packageName = chain.args.filterIsInstance<String>().firstOrNull()
            val callerUid = Binder.getCallingUid()
            val queriedUid = resolveQueriedUid(packageName) ?: return@Hooker original
            if (!AppOpsVisibilityPolicy.shouldHideMockLocation(ints[0], queriedUid, callerUid)) {
                return@Hooker original
            }
            if (FakeLoc.enableDebugLog) {
                Logger.debug("AppOps hidden mock_location queried=$packageName/$queriedUid caller=$callerUid")
            }
            // Baseline answer for a non-mock package (see AppOpsVisibilityPolicy.HIDDEN_MODE_BASELINE).
            AppOpsManager.MODE_ERRORED
        }

        val opsForPackageCallback = Hooker { chain ->
            val original: Any? = chain.proceed()
            if (original == null) return@Hooker null
            val identity = packageOpsIdentity(original) ?: return@Hooker original
            val (packageName, uid) = identity
            val callerUid = Binder.getCallingUid()
            if (!AppOpsVisibilityPolicy.shouldHideMockLocation(
                    AppOpsVisibilityPolicy.OP_MOCK_LOCATION,
                    uid,
                    callerUid,
                )
            ) {
                return@Hooker original
            }
            val (changed, result) = withoutMockOp(original)
            if (changed && FakeLoc.enableDebugLog) {
                Logger.debug(
                    "AppOps getOpsForPackage removed mock_location for $packageName/$uid caller=$callerUid",
                )
            }
            if (changed) result else original
        }

        val uidOpsCallback = Hooker { chain ->
            val original: Any? = chain.proceed()
            if (original == null) return@Hooker null
            val list = original as? List<*> ?: return@Hooker original
            val callerUid = Binder.getCallingUid()
            var changed = false
            val sanitized = list.map { entry ->
                entry ?: return@map entry
                val identity = packageOpsIdentity(entry) ?: return@map entry
                val (_, uid) = identity
                if (!AppOpsVisibilityPolicy.shouldHideMockLocation(
                        AppOpsVisibilityPolicy.OP_MOCK_LOCATION,
                        uid,
                        callerUid,
                    )
                ) {
                    return@map entry
                }
                val (entryChanged, result) = withoutMockOp(entry)
                if (entryChanged) {
                    changed = true
                    result
                } else {
                    entry
                }
            }
            if (changed && FakeLoc.enableDebugLog) {
                Logger.debug("AppOps getUidOps removed mock_location entries for caller=$callerUid")
            }
            if (changed) sanitized else original
        }

        var installed = false
        for (name in CHECK_METHODS) {
            val hooks = HookInstaller.hookAllMethods(service, name, checkCallback)
            installed = installed || hooks.isNotEmpty()
        }
        // Enumerators are optional across versions; absence only narrows the covered surface.
        for ((name, callback) in listOf(
            "getOpsForPackage" to opsForPackageCallback,
            "getUidOps" to uidOpsCallback,
        )) {
            val hooks = HookInstaller.hookAllMethods(service, name, callback)
            if (hooks.isEmpty() && FakeLoc.enableDebugLog) {
                Logger.debug("AppOpsService.$name not found; enumerator surface uncovered")
            }
        }
        if (!installed) {
            Logger.warn("AppOpsService.checkOperation* not found; OP_MOCK_LOCATION cannot be hidden")
        }
        return installed
    }

    /**
     * Resolves the queried package's uid from its name. Returns null (fail-open to the real
     * answer) when the package cannot be resolved — hiding must never widen its own authority by
     * guessing identities.
     */
    private fun resolveQueriedUid(packageName: String?): Int? {
        if (packageName.isNullOrEmpty()) return null
        return runCatching {
            BinderUtils.getSystemContext()?.packageManager?.getApplicationInfo(packageName, 0)?.uid
        }.getOrNull()
    }

    /** (packageName, uid) of a PackageOps reply entry, read reflectively. */
    private fun packageOpsIdentity(entry: Any): Pair<String, Int>? = runCatching {
        val packageName = entry.javaClass.getMethod("getPackageName").invoke(entry) as? String
            ?: return null
        val uid = entry.javaClass.getMethod("getUid").invoke(entry) as Int
        packageName to uid
    }.getOrNull()

    /**
     * Removes the mock_location op entry from a PackageOps reply. Returns the original entry
     * unchanged when it carries no mock_location op or the reply cannot be rebuilt.
     */
    private fun withoutMockOp(entry: Any): Pair<Boolean, Any> = runCatching {
        val identity = packageOpsIdentity(entry) ?: return false to entry
        val (packageName, uid) = identity
        if (!AppOpsVisibilityPolicy.shouldHideMockLocation(
                AppOpsVisibilityPolicy.OP_MOCK_LOCATION,
                uid,
                Binder.getCallingUid(),
            )
        ) {
            return false to entry
        }
        val ops = entry.javaClass.getMethod("getOps").invoke(entry) as? List<*>
            ?: return false to entry
        val filtered = ops.filterNot { opEntry ->
            opEntry == null ||
                runCatching {
                    opEntry.javaClass.getField("op").getInt(opEntry)
                }.getOrDefault(-1) == AppOpsVisibilityPolicy.OP_MOCK_LOCATION
        }
        if (filtered.size == ops.size) return false to entry
        val constructor = entry.javaClass.constructors.firstOrNull { candidate ->
            candidate.parameterTypes.size == 3 &&
                candidate.parameterTypes[0] == String::class.java &&
                candidate.parameterTypes[1] == Integer.TYPE &&
                List::class.java.isAssignableFrom(candidate.parameterTypes[2])
        } ?: return false to entry
        true to constructor.newInstance(packageName, uid, filtered)
    }.getOrDefault(false to entry)

    private val CHECK_METHODS = listOf("checkOperation", "checkOperationImpl")
}
