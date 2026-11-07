package com.system.location.service.hook.utils

import com.system.location.service.hook.runtime.Reflection
import com.system.location.service.hook.scope.HookInstaller
import com.system.location.service.hook.scope.HookStatus
import com.system.location.service.hook.scope.HookStatusRegistry
import io.github.libxposed.api.XposedInterface.HookHandle
import io.github.libxposed.api.XposedInterface.Hooker
import java.lang.reflect.Method

private val hookedMethods = mutableSetOf<Method>()

/** Method identity includes the declaring classloader, unlike textual signatures. */
fun Method.onceHook(callback: Hooker): HookHandle? = synchronized(hookedMethods) {
    if (this in hookedMethods) null else HookInstaller.hookMethod(this, callback).also {
        hookedMethods.add(this)
    }
}

fun Class<*>.onceHookAllMethod(methodName: String, callback: Hooker): Set<HookHandle> =
    declaredMethods.filter { it.name == methodName }.mapNotNullTo(mutableSetOf()) { it.onceHook(callback) }

fun Class<*>.onceHookMethod(
    methodName: String, vararg parameterTypes: Class<*>, callback: Hooker,
): HookHandle? = HookInstaller.findMethod(this, methodName, *parameterTypes)?.onceHook(callback)

fun Class<*>.hookAllMethods(methodName: String, callback: Hooker): Set<HookHandle> =
    HookInstaller.hookAllMethods(this, methodName, callback)

fun Method.hook(callback: Hooker): HookHandle = HookInstaller.hookMethod(this, callback)

fun Class<*>.hookMethod(
    methodName: String, vararg parameterTypes: Class<*>, callback: Hooker,
): HookHandle? = HookInstaller.findMethod(this, methodName, *parameterTypes)?.hook(callback)

fun <T> T.callMethod(methodName: String, vararg args: Any?): Any? =
    Reflection.callMethod(this, methodName, *args)

fun Class<*>.callStaticMethod(methodName: String, vararg args: Any?): Any? =
    Reflection.callStaticMethod(this, methodName, *args)

fun String.toClass(classLoader: ClassLoader?): Class<*>? =
    Reflection.findClassIfExists(this, classLoader).also {
        if (it == null) HookStatusRegistry.record(this, HookStatus(skipped = true, reason = "Class absent"))
    }

fun String.toClassOrThrow(classLoader: ClassLoader?): Class<*> = Reflection.findClass(this, classLoader)
