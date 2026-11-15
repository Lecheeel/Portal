package com.system.location.service.hook.privacy

import com.system.location.service.core.privacy.StackCleanPolicy
import com.system.location.service.hook.scope.HookInstaller
import com.system.location.service.hook.utils.FakeLoc
import io.github.libxposed.api.XposedInterface

/**
 * Experimental stack-frame cleaning (default off).
 *
 * Strategies come from [StackCleanPolicy]:
 * - FILTER removes this module's frames and the libxposed dispatch frames that a detector would
 *   otherwise see in a callback invoked from a hooked method. It cannot change
 *   `getStackTraceDepth()` (the native count), so the reported length diverges from the native
 *   depth — a known, reproducible artifact.
 * - REPLACE rewrites module frames in place with a neutral frame, keeping the reported array
 *   length identical to the uncleaned depth. Length parity is not invisibility; the neutral
 *   frame is a constant and cause chains are left alone (known boundary).
 *
 * Both are opt-in experiments; neither is a default strategy, and both must be validated with the
 * canary application. `get_anti_detect_state` publishes the two switches from the controller.
 */
object StackTraceCleanHook {
    fun install(classLoader: ClassLoader) {
        val throwable = runCatching { Class.forName("java.lang.Throwable", false, classLoader) }.getOrNull()
        val thread = runCatching { Class.forName("java.lang.Thread", false, classLoader) }.getOrNull()
        val hooker = XposedInterface.Hooker { chain ->
            val original = chain.proceed()
            val mode = currentMode()
            if (mode == StackCleanPolicy.Mode.OFF) return@Hooker original
            if (FakeLoc.enableDebugLog && mode == StackCleanPolicy.Mode.REPLACE) {
                // REPLACE is the newer experiment: make its activation visible in diagnostics.
                com.system.location.service.hook.utils.Logger.debug("stack-clean mode=replace")
            }
            clean(original, mode)
        }
        throwable?.let { HookInstaller.hookAllMethods(it, "getStackTrace", hooker) }
        thread?.let { HookInstaller.hookAllMethods(it, "getStackTrace", hooker) }
    }

    internal fun currentMode(): StackCleanPolicy.Mode = when {
        !FakeLoc.stackTraceCleanEnabled -> StackCleanPolicy.Mode.OFF
        FakeLoc.stackTraceReplaceEnabled -> StackCleanPolicy.Mode.REPLACE
        else -> StackCleanPolicy.Mode.FILTER
    }

    private fun clean(value: Any?, mode: StackCleanPolicy.Mode): Any? {
        val frames = value as? Array<StackTraceElement>
            ?: (value as? Array<*>)?.mapNotNull { it as? StackTraceElement }?.toTypedArray()
            ?: return value
        if (!StackCleanPolicy.hasModuleFrames(frames)) return value
        return when (mode) {
            StackCleanPolicy.Mode.FILTER -> StackCleanPolicy.filter(frames)
            StackCleanPolicy.Mode.REPLACE -> StackCleanPolicy.replace(frames)
            StackCleanPolicy.Mode.OFF -> value
        }
    }
}
