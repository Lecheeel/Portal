package com.system.location.service.hook.scope

import com.system.location.service.hook.runtime.HookRuntime
import com.system.location.service.hook.runtime.Reflection
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedInterface.HookHandle
import io.github.libxposed.api.XposedInterface.Hooker
import java.lang.reflect.Executable
import java.lang.reflect.Member
import java.util.concurrent.atomic.AtomicBoolean

/** Observes the first invocation without installing a second probe hook. */
object HookInstaller {
    fun hookMethod(
        member: Member,
        callback: Hooker,
        priority: Int = XposedInterface.PRIORITY_DEFAULT,
        exceptionMode: XposedInterface.ExceptionMode = XposedInterface.ExceptionMode.PROTECTIVE,
    ): HookHandle {
        val executable = member as? Executable
            ?: throw IllegalArgumentException("Only methods and constructors can be hooked: $member")
        val key = member.toString()
        val matched = AtomicBoolean(false)
        val handle = try {
            HookRuntime.interfaceInstance().hook(executable)
                .setPriority(priority)
                .setExceptionMode(exceptionMode)
                .intercept { chain ->
                    if (matched.compareAndSet(false, true)) HookStatusRegistry.matched(key)
                    callback.intercept(chain)
                }
        } catch (error: Throwable) {
            HookStatusRegistry.record(key, HookStatus(supported = true, failed = true,
                reason = error.javaClass.simpleName))
            throw error
        }
        HookStatusRegistry.installed(key)
        HookRuntime.track(handle)
        // A call can arrive while the framework is still returning the registration handle.
        if (matched.get()) HookStatusRegistry.matched(key)
        return handle
    }

    fun hookAllMethods(
        type: Class<*>, name: String, callback: Hooker,
        priority: Int = XposedInterface.PRIORITY_DEFAULT,
        exceptionMode: XposedInterface.ExceptionMode = XposedInterface.ExceptionMode.PROTECTIVE,
    ): Set<HookHandle> {
        val methods = type.declaredMethods.filter { it.name == name }
        if (methods.isEmpty()) HookStatusRegistry.record("${type.name}#$name",
            HookStatus(skipped = true, reason = "Method absent"))
        return methods.mapTo(mutableSetOf()) { hookMethod(it, callback, priority, exceptionMode) }
    }

    fun hookAllConstructors(type: Class<*>, callback: Hooker): Set<HookHandle> =
        type.declaredConstructors.mapTo(mutableSetOf()) { hookMethod(it, callback) }

    fun findMethod(type: Class<*>, name: String, vararg parameters: Class<*>): java.lang.reflect.Method? {
        val method = Reflection.findMethodExactIfExists(type, name, *parameters)
        if (method == null) HookStatusRegistry.record("${type.name}#$name(${parameters.joinToString { it.name }})",
            HookStatus(skipped = true, reason = "Method absent"))
        return method
    }
}
