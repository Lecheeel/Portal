package com.system.location.service.hook.privacy

import android.database.MatrixCursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import io.github.libxposed.api.XposedInterface.Hooker
import com.system.location.service.hook.runtime.Reflection
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
        val provider = Reflection.findClassIfExists(
            "com.android.providers.settings.SettingsProvider",
            classLoader,
        ) ?: return false

        val callHook = HookInstaller.hookAllMethods(provider, "call", Hooker { chain ->
            val original = chain.proceed()
            if (!SettingsPrivacyPolicy.shouldHide(Binder.getCallingUid())) return@Hooker original
            val strings = chain.args.filterIsInstance<String>()
            if (strings.size < 2) return@Hooker original
            val command = strings[0]
            val key = strings[1]
            if (!SettingsPrivacyPolicy.isRestricted(command, key)) return@Hooker original
            val reply = original as? Bundle ?: return@Hooker original
            reply.putString(key, SettingsPrivacyPolicy.HIDDEN_VALUE)
            if (FakeLoc.enableDebugLog) {
                Logger.debug("Settings hidden $command/$key from uid=${Binder.getCallingUid()}")
            }
            reply
        })

        val queryHook = HookInstaller.hookAllMethods(provider, "query", Hooker { chain ->
            val original = chain.proceed()
            if (!SettingsPrivacyPolicy.shouldHide(Binder.getCallingUid())) return@Hooker original
            val uri = chain.args.filterIsInstance<Uri>().firstOrNull() ?: return@Hooker original
            val key = uri.lastPathSegment ?: return@Hooker original
            if (!SettingsPrivacyPolicy.isQueryKey(key)) return@Hooker original
            MatrixCursor(arrayOf("value"), 0)
        })

        return callHook.isNotEmpty() || queryHook.isNotEmpty()
    }
}
