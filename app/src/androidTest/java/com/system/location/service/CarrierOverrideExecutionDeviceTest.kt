package com.system.location.service

import android.os.PersistableBundle
import com.system.location.service.hook.runtime.HookRuntime
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Executable
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import org.junit.Assert.*
import org.junit.Test

/** Real Android bundles + fake platform calls. Never hooks ART or changes the real phone service. */
class CarrierOverrideExecutionDeviceTest {
    @Test fun queuedApplyReapplyRestoreAndFailureUseRealHookCallbacks() {
        val framework = HookRuntime::class.java.getDeclaredField("framework").apply { isAccessible = true }
        val previousFramework = framework.get(HookRuntime)
        val hookType = Class.forName("com.system.location.service.hook.hooks.telephony.CarrierConfigOverrideHook")
        val instance = hookType.getField("INSTANCE").get(null)
        val callbacks = linkedMapOf<Method, XposedInterface.Hooker>()
        val helper = FakeLoader::class.java.getDeclaredMethod("overrideConfig",
            arrayOfNulls<PersistableBundle>(0).javaClass, Int::class.javaPrimitiveType, PersistableBundle::class.java)
        val outer = FakeLoader::class.java.getDeclaredMethod("overrideConfig",
            Int::class.javaPrimitiveType, PersistableBundle::class.java, Boolean::class.javaPrimitiveType)
        val fake = FakeLoader()
        fun call(method: Method, args: List<Any?>, action: (List<Any?>) -> Any?): Any? {
            val chain = proxy(XposedInterface.Chain::class.java) { called, values -> when (called.name) {
                "getExecutable" -> method
                "getThisObject" -> fake
                "getArgs" -> args
                "getArg" -> args[values!![0] as Int]
                "proceed" -> action(if (values.isNullOrEmpty()) args else (values[0] as Array<*>).toList())
                else -> error("Unexpected chain ${called.name}")
            } }
            return callbacks.getValue(method).intercept(chain)
        }
        val api = proxy(XposedInterface::class.java) { method, args -> when (method.name) {
            "log" -> null
            "hook" -> {
                val executable = args!![0] as Method
                lateinit var builder: XposedInterface.HookBuilder
                builder = proxy(XposedInterface.HookBuilder::class.java) { builderMethod, values ->
                    when (builderMethod.name) {
                        "setPriority", "setExceptionMode" -> builder
                        "intercept" -> {
                            callbacks[executable] = values!![0] as XposedInterface.Hooker
                            proxy(XposedInterface.HookHandle::class.java) { handleMethod, _ -> when (handleMethod.name) {
                                "getExecutable" -> executable
                                "unhook" -> null
                                else -> error("Unexpected handle")
                            } }
                        }
                        else -> error("Unexpected builder")
                    }
                }
                builder
            }
            "getInvoker" -> {
                val target = args!![0] as Method
                lateinit var invoker: XposedInterface.Invoker<*, *>
                invoker = proxy(XposedInterface.Invoker::class.java) { called, values -> when (called.name) {
                    "setType" -> invoker
                    "invoke" -> target.invoke(values!![0], *(values[1] as Array<*>))
                    else -> error("Unexpected invoker")
                } }
                invoker
            }
            else -> error("Unexpected framework ${method.name}")
        } }
        val fields = listOf("pending", "owned", "failures")
        val stateFields = fields.associateWith { hookType.getDeclaredField(it).apply { isAccessible = true } }
        val supported = hookType.getDeclaredField("supported").apply { isAccessible = true }
        val oldSupported = supported.getBoolean(instance)
        val handles = HookRuntime::class.java.getDeclaredField("handles").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val tracked = handles.get(HookRuntime) as MutableCollection<Any>
        val oldHandles = tracked.toList()
        var fixtureOwnsState = false
        try {
            check(stateFields.values.all { field -> when (val value = field.get(instance)) {
                is Map<*, *> -> value.isEmpty()
                is Collection<*> -> value.isEmpty()
                else -> false
            } }) { "Fixture must not overwrite an active local override session" }
            fixtureOwnsState = true
            framework.set(HookRuntime, api)
            val uid: () -> Int = { 1000 }
            hookType.declaredMethods.single { it.name.startsWith("installType") &&
                it.parameterTypes.toList() == listOf(Class::class.java, kotlin.jvm.functions.Function0::class.java) }
                .invoke(instance, FakeLoader::class.java, uid)
            fun failed(sub: Int) = hookType.getDeclaredMethod("failed", Int::class.javaPrimitiveType)
                .invoke(instance, sub) as Boolean
            fun status(sub: Int) = hookType.getDeclaredMethod("status", Int::class.javaPrimitiveType)
                .invoke(instance, sub) as Pair<*, *>
            fun queue(sub: Int, action: String, name: String? = null) {
                val request = PersistableBundle().apply {
                    putString("portal_carrier_override_action", action)
                    name?.let { putString("carrier_name_string", it) }
                }
                call(outer, listOf(sub, request, false)) { values ->
                    val clean = values[1] as PersistableBundle
                    assertFalse(clean.containsKey("portal_carrier_override_action"))
                    fake.queued += clean
                    null
                }
            }
            fun drain(phoneId: Int = 0) {
                val bundle = fake.queued.removeAt(0)
                call(helper, listOf(fake.layer, phoneId, bundle)) { values ->
                    if (fake.failMerge) error("platform merge failed")
                    @Suppress("UNCHECKED_CAST")
                    fake.overrideConfig(values[0] as Array<PersistableBundle?>, values[1] as Int,
                        values[2] as PersistableBundle?)
                    null
                }
            }
            fake.layer[0] = PersistableBundle().apply {
                putString("carrier_name_string", "original")
                putBoolean("unrelated_volte", true)
            }
            queue(10, "apply", "Portal")
            assertEquals(false to true, status(10))
            drain()
            assertEquals(true to false, status(10))
            assertFalse(failed(10))
            assertEquals("Portal", fake.layer[0]!!.getString("carrier_name_string"))
            fake.layer[0]!!.putString("carrier_name_string", "other tool")
            queue(10, "apply", "Portal second")
            drain()
            queue(10, "restore")
            drain()
            assertEquals("other tool", fake.layer[0]!!.getString("carrier_name_string"))
            assertTrue(fake.layer[0]!!.getBoolean("unrelated_volte"))
            assertEquals(false to false, status(10))
            queue(10, "apply", "active")
            drain()
            fake.failMerge = true
            queue(10, "apply", "must fail")
            assertThrows(IllegalStateException::class.java) { drain() }
            assertTrue(failed(10))
            assertEquals(true to false, status(10))
            assertEquals("active", fake.layer[0]!!.getString("carrier_name_string"))
            fake.failMerge = false
            queue(11, "apply", "wrong subscription")
            assertThrows(IllegalStateException::class.java) { drain() }
            assertTrue(failed(11))
            assertEquals("active", fake.layer[0]!!.getString("carrier_name_string"))
            queue(10, "restore")
            drain()
            assertFalse(failed(10))
            assertEquals("other tool", fake.layer[0]!!.getString("carrier_name_string"))
        } finally {
            framework.set(HookRuntime, previousFramework)
            if (fixtureOwnsState) {
                stateFields.values.forEach { field -> when (val value = field.get(instance)) {
                    is MutableMap<*, *> -> value.clear()
                    is MutableCollection<*> -> value.clear()
                    else -> Unit
                } }
                supported.setBoolean(instance, oldSupported)
                tracked.toList().filter { handle -> oldHandles.none { it === handle } }
                    .forEach { tracked.remove(it) }
            }
        }
    }

    class FakeLoader {
        val layer: Array<PersistableBundle?> = arrayOfNulls(2)
        val queued = mutableListOf<PersistableBundle>()
        var failMerge = false
        fun overrideConfig(subId: Int, values: PersistableBundle?, persistent: Boolean) = Unit
        fun overrideConfig(current: Array<PersistableBundle?>, phoneId: Int, patch: PersistableBundle?) {
            if (patch == null) current[phoneId] = PersistableBundle()
            else if (current[phoneId] == null) current[phoneId] = patch
            else current[phoneId]!!.putAll(patch)
        }
    }

    private fun <T> proxy(type: Class<T>, action: (Method, Array<out Any?>?) -> Any?): T {
        @Suppress("UNCHECKED_CAST")
        return Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { instance, method, args -> when (method.name) {
            "equals" -> instance === args?.get(0)
            "hashCode" -> System.identityHashCode(instance)
            "toString" -> "CarrierFixture${type.simpleName}"
            else -> action(method, args)
        } } as T
    }
}
