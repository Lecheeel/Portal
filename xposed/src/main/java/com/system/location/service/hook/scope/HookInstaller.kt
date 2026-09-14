package com.system.location.service.hook.scope

import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import java.lang.reflect.Member
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

object HookInstaller {
    private val probed = ConcurrentHashMap.newKeySet<Member>()

    fun hookMethod(member: Member, callback: XC_MethodHook): XC_MethodHook.Unhook {
        val key = member.toString()
        val handle = try { XposedBridge.hookMethod(member, callback) }
        catch (error: Throwable) {
            HookStatusRegistry.record(key, HookStatus(supported = true, failed = true, reason = error.javaClass.simpleName))
            throw error
        }
        HookStatusRegistry.installed(key)
        if (probed.add(member)) {
            val probe = AtomicReference<XC_MethodHook.Unhook?>()
            val matched = AtomicBoolean(false)
            try {
                val observation = XposedBridge.hookMethod(member, object : XC_MethodHook(PRIORITY_HIGHEST) {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        matched.set(true)
                        HookStatusRegistry.matched(key)
                        probe.getAndSet(null)?.unhook()
                    }
                })
                probe.set(observation)
                // The first invocation may occur before the hook API returns its handle.
                if (matched.get()) probe.getAndSet(null)?.unhook()
            } catch (error: Throwable) {
                probed.remove(member)
                HookStatusRegistry.record("$key [observation]", HookStatus(supported = true,
                    failed = true, reason = "Match observation unavailable: ${error.javaClass.simpleName}"))
            }
        }
        return handle
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
