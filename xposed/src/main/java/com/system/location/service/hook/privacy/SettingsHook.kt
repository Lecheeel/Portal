package com.system.location.service.hook.privacy

import android.database.MatrixCursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import com.system.location.service.hook.scope.HookInstaller
import com.system.location.service.hook.utils.FakeLoc
import com.system.location.service.hook.utils.Logger
import com.system.location.service.core.privacy.SettingsPrivacyPolicy

/**
 * Hides the two Settings keys that reveal a mock-location setup (P1-03):
 *
 * - `Settings.Secure.mock_location` / legacy `allow_mock_location`
 * - `Settings.Global.development_settings_enabled`
 *
 * The hook only changes answers for third-party callers (uid >= 10000). System/Settings UI
 * callers keep the real values, so developer options and the mock-location app picker still work.
 * `SettingsProvider.call` is the path used by `Settings.Secure.getString`; the `query` path is
 * handled as a fallback for clients that read the provider directly.
 */
object SettingsHook {
    fun hook(classLoader: ClassLoader): Boolean {
        val provider = XposedHelpers.findClassIfExists(
            "com.android.providers.settings.SettingsProvider",
            classLoader,
        ) ?: return false

        val callHook = HookInstaller.hookAllMethods(provider, "call", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                if (!SettingsPrivacyPolicy.shouldHide(Binder.getCallingUid())) return
                val strings = param.args.filterIsInstance<String>()
                if (strings.size < 2) return
                val command = strings[0]
                val key = strings[1]
                if (!SettingsPrivacyPolicy.isRestricted(command, key)) return
                val reply = param.result as? Bundle ?: return
                reply.putString(key, SettingsPrivacyPolicy.HIDDEN_VALUE)
                if (FakeLoc.enableDebugLog) {
                    Logger.debug("Settings hidden $command/$key from uid=${Binder.getCallingUid()}")
                }
            }
        })

        val queryHook = HookInstaller.hookAllMethods(provider, "query", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                if (!SettingsPrivacyPolicy.shouldHide(Binder.getCallingUid())) return
                val uri = param.args.filterIsInstance<Uri>().firstOrNull() ?: return
                val key = uri.lastPathSegment ?: return
                if (!SettingsPrivacyPolicy.isQueryKey(key)) return
                param.result = MatrixCursor(arrayOf("value"), 0)
            }
        })

        return callHook.isNotEmpty() || queryHook.isNotEmpty()
    }
}
