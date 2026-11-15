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
 *
 * Developer-options and adb keys are intentionally not rewritten: they are not caused by mock
 * location, and a false answer is cross-checkable against build properties and device policy.
 * The hook only changes answers for third-party callers (uid >= 10000); System/Settings UI
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
            // AOSP SettingsProvider returns only the "value" entry. Adding the key name would
            // make the Bundle itself a hook fingerprint.
            // Baseline preservation: when the key is absent on this device, the stock reply has
            // no "value" entry at all. Writing one here would fabricate a value that the system
            // never stored, so only existing answers are rewritten (work package 9).
            if (!reply.containsKey("value")) return@Hooker original
            reply.putString("value", SettingsPrivacyPolicy.HIDDEN_VALUE)
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
            // Contract preservation: mirror the original cursor shape exactly — same column set,
            // same row count. An absent key stays absent (empty cursor passes through untouched)
            // and present rows keep every column value except the restricted "value" entry.
            // Fabricating a one-column cursor would itself be a queryable fingerprint.
            // NOTE: the provider-side cursor must be returned open; it is closed by the transport
            // layer, so this code must not wrap it in Cursor.use.
            val cursor = original as? android.database.Cursor ?: return@Hooker original
            if (!cursor.moveToFirst()) return@Hooker cursor
            val columns = cursor.columnNames
            val valueIndex = columns.indexOfFirst { it.equals("value", ignoreCase = true) }
            val row = Array<Any?>(columns.size) { index ->
                if (index == valueIndex) SettingsPrivacyPolicy.HIDDEN_VALUE
                else runCatching { cursor.getString(index) }.getOrNull()
            }
            MatrixCursor(columns).apply { addRow(row) }
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
