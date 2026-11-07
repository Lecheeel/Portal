package com.system.location.service.hook.runtime

import android.util.Log
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method

/** Process-local framework access. Interceptors use the native libxposed chain directly. */
object HookRuntime {
    @Volatile private var framework: XposedInterface? = null
    internal fun attachedInterface(): XposedInterface? = framework
    fun attach(value: XposedInterface) { framework = value }
    fun interfaceInstance(): XposedInterface = checkNotNull(framework) {
        "libxposed module has not been loaded"
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
}
