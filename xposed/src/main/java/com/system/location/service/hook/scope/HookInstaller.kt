package com.system.location.service.hook.scope

import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import java.lang.reflect.Member
import java.util.concurrent.ConcurrentHashMap

object HookInstaller {
    private val probed = ConcurrentHashMap.newKeySet<Member>()

    fun hookMethod(member: Member, callback: XC_MethodHook): XC_MethodHook.Unhook {
        val key = member.toString()
        try {
            val handle = XposedBridge.hookMethod(member, callback)
            HookStatusRegistry.installed(key)
            if (probed.add(member)) {
                var probe: XC_MethodHook.Unhook? = null
                probe = XposedBridge.hookMethod(member, object : XC_MethodHook(PRIORITY_HIGHEST) {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        HookStatusRegistry.matched(key)
                        probe?.unhook() // Probe costs only the first invocation.
                    }
                })
            }
            return handle
        } catch (error: Throwable) {
            HookStatusRegistry.record(key, HookStatus(supported = true, failed = true, reason = error.javaClass.simpleName))
            throw error
        }
    }

    fun hookAllMethods(type: Class<*>, name: String, callback: XC_MethodHook): Set<XC_MethodHook.Unhook> {
        val methods = type.declaredMethods.filter { it.name == name }
        if (methods.isEmpty()) HookStatusRegistry.record("${type.name}#$name", HookStatus(skipped = true, reason = "Method absent"))
        return methods.mapTo(mutableSetOf()) { hookMethod(it, callback) }
    }

    fun hookAllConstructors(type: Class<*>, callback: XC_MethodHook): Set<XC_MethodHook.Unhook> =
        type.declaredConstructors.mapTo(mutableSetOf()) { hookMethod(it, callback) }

    fun findMethod(type: Class<*>, name: String, vararg parameters: Class<*>): java.lang.reflect.Method? {
        val method = de.robv.android.xposed.XposedHelpers.findMethodExactIfExists(type, name, *parameters)
        if (method == null) HookStatusRegistry.record("${type.name}#$name(${parameters.joinToString { it.name }})",
            HookStatus(skipped = true, reason = "Method absent"))
        return method
    }
}
