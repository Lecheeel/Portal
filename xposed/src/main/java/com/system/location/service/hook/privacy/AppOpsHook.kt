package com.system.location.service.hook.privacy

import android.app.AppOpsManager
import android.os.Binder
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import com.system.location.service.hook.scope.HookInstaller
import com.system.location.service.hook.utils.FakeLoc
import com.system.location.service.core.privacy.AppOpsVisibilityPolicy
import com.system.location.service.hook.utils.Logger

/**
 * Hides `OP_MOCK_LOCATION` from third-party callers that inspect another package's AppOps state
 * (P1-02).
 *
 * The controller (Portal itself) still receives the real mode because it asks about its own UID;
 * system callers and the controller's self-check are deliberately left untouched. The hook matches
 * `checkOperation*` by name so it does not depend on the exact overload added in each Android
 * release (12–17).
 */
object AppOpsHook {
    fun hook(classLoader: ClassLoader): Boolean {
        val service = XposedHelpers.findClassIfExists("com.android.server.appop.AppOpsService", classLoader)
            ?: return false
        val callback = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                if (param.result !is Int) return
                val ints = param.args.filterIsInstance<Int>()
                if (ints.size < 2 || ints[0] != AppOpsVisibilityPolicy.OP_MOCK_LOCATION) return
                val queriedUid = ints[1]
                val callerUid = Binder.getCallingUid()
                // System processes and the controller checking its own UID keep the real answer.
                if (!AppOpsVisibilityPolicy.shouldHideMockLocation(ints[0], queriedUid, callerUid)) return
                param.result = AppOpsManager.MODE_ERRORED
                if (FakeLoc.enableDebugLog) {
                    val packageName = param.args.filterIsInstance<String>().firstOrNull()
                    Logger.debug("AppOps hidden mock_location queriedUid=$queriedUid caller=$callerUid package=$packageName")
                }
            }
        }
        var installed = false
        for (name in HOOK_METHODS) {
            val hooks = HookInstaller.hookAllMethods(service, name, callback)
            installed = installed || hooks.isNotEmpty()
        }
        if (!installed) {
            Logger.warn("AppOpsService.checkOperation* not found; OP_MOCK_LOCATION cannot be hidden")
        }
        return installed
    }

    private val HOOK_METHODS = listOf("checkOperation", "checkOperationImpl")
}
