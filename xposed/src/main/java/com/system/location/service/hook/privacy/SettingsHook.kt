package com.system.location.service.hook.privacy

import android.content.Context
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
    @Volatile private var installed = false

    fun hook(classLoader: ClassLoader): Boolean {
        if (installed) return true
        val provider = Reflection.findClassIfExists(
            "com.android.providers.settings.SettingsProvider",
            classLoader,
        ) ?: settingsPackageLoader()?.let { loader ->
            Reflection.findClassIfExists("com.android.providers.settings.SettingsProvider", loader)
        } ?: return false

        val callHook = HookInstaller.hookAllMethods(provider, "call", Hooker { chain ->
            val original = chain.proceed()
            if (!SettingsPrivacyPolicy.shouldHide(Binder.getCallingUid())) return@Hooker original
            val strings = chain.args.filterIsInstance<String>()
            if (strings.size < 2) return@Hooker original
            val command = strings[0]
            val key = strings[1]
            if (!SettingsPrivacyPolicy.isRestricted(command, key)) return@Hooker original
            val reply = original as? Bundle ?: return@Hooker original
            // SettingsProvider returns the value under "value"; keep the keyed entry as a
            // fallback for clients that read the provider reply directly.
            reply.putString("value", SettingsPrivacyPolicy.HIDDEN_VALUE)
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

        com.system.location.service.hook.runtime.HookRuntime.log(
            "settings hook class=${provider.name} call=${callHook.size} query=${queryHook.size}"
        )

        val hooked = callHook.isNotEmpty() || queryHook.isNotEmpty()
        if (hooked) installed = true
        return hooked
    }

    /** SettingsProvider is a package loaded into system_server on some ROMs; resolve its loader. */
    private fun settingsPackageLoader(): ClassLoader? = runCatching {
        val activityThreadClass = Class.forName("android.app.ActivityThread")
        val activityThread = activityThreadClass.getMethod("currentActivityThread").invoke(null)
        val systemContext = activityThreadClass.getMethod("getSystemContext").invoke(activityThread) as Context
        systemContext.createPackageContext(
            "com.android.providers.settings",
            Context.CONTEXT_INCLUDE_CODE or Context.CONTEXT_IGNORE_SECURITY,
        ).classLoader
    }.getOrNull()
}
