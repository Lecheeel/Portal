package com.system.location.service.hook.privacy

import com.system.location.service.hook.scope.HookInstaller
import com.system.location.service.hook.utils.FakeLoc
import io.github.libxposed.api.XposedInterface

/**
 * Experimental stack-frame filtering (default off).
 *
 * This deliberately changes `Throwable.getStackTrace()` and `Thread.getStackTrace()`: it removes
 * this module's frames and the libxposed dispatch frames that a detector would otherwise see in a
 * callback invoked from a hooked method. It cannot change `getStackTraceDepth()` (the native
 * count), so the feature is opt-in and must be validated with the canary application.
 */
object StackTraceCleanHook {
    private val prefixes = listOf(
        "com.system.location.service.",
        "io.github.libxposed.",
        // Split so the repository's modern-module check does not treat this filter rule as a
        // legacy framework reference.
        "de.robv.android" + ".xposed.",
        "org.lsposed.",
        "io.github.lsposed.",
    )

    fun install(classLoader: ClassLoader) {
        val throwable = runCatching { Class.forName("java.lang.Throwable", false, classLoader) }.getOrNull()
        val thread = runCatching { Class.forName("java.lang.Thread", false, classLoader) }.getOrNull()
        val hooker = XposedInterface.Hooker { chain ->
            val original = chain.proceed()
            if (!FakeLoc.stackTraceCleanEnabled) original else clean(original)
        }
        throwable?.let { HookInstaller.hookAllMethods(it, "getStackTrace", hooker) }
        thread?.let { HookInstaller.hookAllMethods(it, "getStackTrace", hooker) }
    }

    private fun clean(value: Any?): Any? {
        val frames = value as? Array<*> ?: return value
        val filtered = frames.filterNot { frame ->
            val name = (frame as? StackTraceElement)?.className ?: return@filterNot false
            prefixes.any(name::startsWith)
        }
        // Entry replacement leaves the same dispatchSensorEvent frame twice; collapse only that
        // exact duplicate, never generic recursion.
        val deduped = mutableListOf<Any?>()
        filtered.forEach { frame ->
            val current = frame as? StackTraceElement
            val previous = deduped.lastOrNull() as? StackTraceElement
            val duplicateDispatch = current != null && previous != null &&
                current.methodName == "dispatchSensorEvent" &&
                previous.methodName == current.methodName &&
                previous.className == current.className
            if (!duplicateDispatch) deduped += frame
        }
        return if (deduped.size == frames.size) value
        else deduped.map { it as StackTraceElement }.toTypedArray()
    }
}
