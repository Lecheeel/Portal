package com.system.location.service.jni

import android.content.Context
import com.system.location.service.hook.PortalModule
import com.system.location.service.hook.runtime.HookRuntime
import java.io.File

/**
 * Loads the experimental libc path-rewriting payload into the selected application process.
 *
 * The payload is copied to the app-private code cache under a neutral per-load name, loaded, then
 * removed from disk; the surviving mapping therefore shows an ordinary cache entry in
 * `/proc/self/maps`. Maps *content* is not rewritten (the experiment that did so was retired —
 * see docs/anti-detection.md). This is best-effort and intentionally not part of the default path.
 */
object AntiDetect {
    @Volatile private var loaded = false
    @Volatile private var enabled = false

    fun setEnabled(enabled: Boolean, context: Context): Boolean {
        if (this.enabled == enabled && loaded) return true
        if (!loaded) {
            if (!enabled) return true
            if (!load(context)) return false
        }
        return runCatching { nativeSetEnabled(enabled) }
            .onSuccess { this.enabled = enabled }
            .onFailure { HookRuntime.log(it) }
            .getOrDefault(false)
    }

    private fun load(context: Context): Boolean = runCatching {
        val info = PortalModule.moduleInfo
            ?: error("module ApplicationInfo unavailable")
        val source = File(info.nativeLibraryDir, "libcodecext.so")
        if (!source.isFile) error("libcodecext.so missing: ${source.absolutePath}")
        // A per-load neutral name keeps the /proc/self/maps entry from being a fixed fingerprint;
        // the file itself is unlinked right after dlopen.
        val target = File(
            context.codeCacheDir,
            "cache_" + java.util.UUID.randomUUID().toString().substring(0, 8) + ".so",
        )
        source.copyTo(target, overwrite = true)
        try {
            System.load(target.absolutePath)
        } finally {
            // The mapping survives unlink; a deleted file cannot be listed from the cache.
            target.delete()
        }
        loaded = true
        true
    }.onFailure { HookRuntime.log(it) }.getOrDefault(false)

    private external fun nativeSetEnabled(enabled: Boolean): Boolean
}
