package com.system.location.service.hook.utils

/** Argument guards shared by hooks; unknown signatures must pass through untouched. */
internal object LocationHookArguments {
    fun canIntercept(enabled: Boolean, args: Array<out Any?>, callbackIndex: Int): Boolean =
        enabled && args.getOrNull(callbackIndex) != null

    inline fun <reified T> rewriteBatch(
        enabled: Boolean, args: Array<Any?>, index: Int, transform: (T) -> T
    ): Boolean {
        if (!enabled) return false
        val original = args.getOrNull(index) as? Array<*> ?: return false
        if (original.any { it !is T }) return false
        args[index] = original.map { transform(it as T) }.toTypedArray()
        return true
    }
}
