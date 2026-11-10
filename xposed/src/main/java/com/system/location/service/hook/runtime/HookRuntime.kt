package com.system.location.service.hook.runtime

import android.util.Log
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Constructor
import java.lang.reflect.Executable
import java.lang.reflect.Method
import java.util.concurrent.CopyOnWriteArrayList

/** Process-local framework access. Interceptors use the native libxposed chain directly. */
object HookRuntime {
    @Volatile internal var executionGate = ReloadExecutionGate()
    @Volatile private var framework: XposedInterface? = null
    private val handles = CopyOnWriteArrayList<XposedInterface.HookHandle>()
    internal fun attachedInterface(): XposedInterface? = framework
    fun attach(value: XposedInterface) { framework = value }
    fun interfaceInstance(): XposedInterface = checkNotNull(framework) {
        "libxposed module has not been loaded"
    }
    internal fun track(handle: XposedInterface.HookHandle) {
        handles.add(handle)
    }
    fun clearHooks() {
        handles.forEach { runCatching { it.unhook() } }
        handles.clear()
    }
    fun log(message: String) {
        framework?.log(Log.INFO, "Portal", message) ?: Log.i("Portal", message)
    }
    fun log(error: Throwable) {
        val message = error.message ?: error.javaClass.name
        framework?.log(Log.ERROR, "Portal", message, error) ?: Log.e("Portal", message, error)
    }
    /** Listener delivery skips our hooks and exposes the listener's actual exception. */
    fun invokeOriginalMethod(method: Method, receiver: Any?, args: Array<Any?>): Any? = try {
        interfaceInstance().getInvoker(method)
            .setType(XposedInterface.Invoker.Type.ORIGIN)
            .invoke(receiver, *args)
    } catch (error: InvocationTargetException) {
        throw error.targetException
    }

    /** A queued interceptor from a retired snapshot must execute the successor's complete chain. */
    internal fun invokeCurrentChain(executable: Executable, receiver: Any?, args: Array<Any?>): Any? = try {
        when (executable) {
            is Method -> interfaceInstance().getInvoker(executable)
                .setType(XposedInterface.Invoker.Type.Chain.FULL).invoke(receiver, *args)
            is Constructor<*> -> interfaceInstance().getInvoker(executable)
                .setType(XposedInterface.Invoker.Type.Chain.FULL).invoke(receiver, *args)
            else -> error("Unsupported executable: $executable")
        }
    } catch (error: InvocationTargetException) {
        throw error.targetException
    }
}
