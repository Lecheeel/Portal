package com.system.location.service.jni

import android.content.Context
import com.system.location.service.hook.PortalModule
import com.system.location.service.hook.runtime.HookRuntime
import java.io.File

/**
 * Loads the experimental libc path-rewriting payload into the selected application process.
 *
 * The payload is copied to the app-private code cache under a neutral name, loaded, then removed
 * from disk; the mapping is hidden from `/proc/self/maps` by the payload's own rewrite once it is
 * enabled. This is best-effort and intentionally not part of the default path.
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
        val target = File(context.codeCacheDir, "libcodec.so")
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
