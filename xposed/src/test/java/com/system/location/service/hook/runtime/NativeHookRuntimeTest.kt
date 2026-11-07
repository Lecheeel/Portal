package com.system.location.service.hook.runtime

import com.system.location.service.hook.scope.HookInstaller
import com.system.location.service.hook.scope.HookStatusRegistry
import com.system.location.service.hook.utils.onceHook
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedInterface.Chain
import io.github.libxposed.api.XposedInterface.HookBuilder
import io.github.libxposed.api.XposedInterface.HookHandle
import io.github.libxposed.api.XposedInterface.Hooker
import io.github.libxposed.api.XposedInterface.Invoker
import java.lang.reflect.Executable
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test

/** JVM contract tests; the fake framework does not emulate ART or framework API protection. */
class NativeHookRuntimeTest {
    private val frameworkField = HookRuntime::class.java.getDeclaredField("framework").apply {
        isAccessible = true
    }
    private var previousFramework: XposedInterface? = null

    @Before
    fun isolateFramework() {
        previousFramework = frameworkField.get(HookRuntime) as? XposedInterface
        frameworkField.set(HookRuntime, null)
    }

    @After
    fun restoreFramework() {
        frameworkField.set(HookRuntime, previousFramework)
    }

    @Test
    fun originalInvocationUsesOriginAndForwardsReceiverAndArguments() {
        val framework = FakeFramework()
        HookRuntime.attach(framework.api)
        val receiver = Target()
        val method = Target::class.java.getDeclaredMethod("original", String::class.java)

        assertEquals("received:sample", HookRuntime.invokeOriginalMethod(method, receiver, arrayOf("sample")))

        assertSame(method, framework.invokedMethod)
        assertSame(receiver, framework.invokedReceiver)
        assertEquals(listOf("sample"), framework.invokedArguments)
        assertTrue(framework.invokerType is Invoker.Type.Origin)
    }

    @Test
    fun originalInvocationUnwrapsTheListenersException() {
        val framework = FakeFramework()
        HookRuntime.attach(framework.api)
        val receiver = Target()
        val method = Target::class.java.getDeclaredMethod("throwsFromListener")

        val failure = runCatching { HookRuntime.invokeOriginalMethod(method, receiver, emptyArray()) }.exceptionOrNull()

        assertSame(receiver.failure, failure)
    }

    @Test
    fun nativeCallbackRewritesArgumentsAndReturnsTheProceededOutcome() {
        val framework = FakeFramework()
        HookRuntime.attach(framework.api)
        val method = targetMethod("rewrite")
        HookInstaller.hookMethod(
            method,
            Hooker { chain ->
                val arguments = chain.args.toTypedArray()
                arguments[0] = "rewritten"
                "hook:${chain.proceed(arguments)}"
            },
            priority = 123,
            exceptionMode = XposedInterface.ExceptionMode.PASSTHROUGH,
        )
        val installed = framework.registrations.single()
        val proceeded = AtomicInteger()
        var received: List<Any?>? = null
        val chain = fakeChain(method, listOf("input")) {
            proceeded.incrementAndGet()
            received = it
            "original:${it.single()}"
        }

        assertEquals("hook:original:rewritten", installed.callback.intercept(chain))

        assertEquals(123, installed.priority)
        assertEquals(XposedInterface.ExceptionMode.PASSTHROUGH, installed.mode)
        assertEquals(listOf("rewritten"), received)
        assertEquals(1, proceeded.get())
        assertEquals(listOf("input"), chain.args)
        assertTrue(HookStatusRegistry.snapshot().getValue(method.toString()).matched)
    }

    @Test
    fun nativeCallbackCanShortCircuitWithoutCallingTheOriginal() {
        val framework = FakeFramework()
        HookRuntime.attach(framework.api)
        val method = targetMethod("shortCircuit")
        HookInstaller.hookMethod(method, Hooker { "replacement" })
        val chain = fakeChain(method, listOf("input")) { error("Original must not execute") }

        assertEquals("replacement", framework.registrations.single().callback.intercept(chain))
    }

    @Test
    fun nativeCallbackPreservesTheOriginalException() {
        val framework = FakeFramework()
        HookRuntime.attach(framework.api)
        val method = targetMethod("throwingChain")
        val expected = IllegalStateException("Original failed")
        HookInstaller.hookMethod(method, Hooker { it.proceed() })
        val chain = fakeChain(method, emptyList()) { throw expected }

        assertSame(expected, runCatching { framework.registrations.single().callback.intercept(chain) }.exceptionOrNull())
    }

    @Test
    fun failedInstallationRecordsFailureAndPropagatesIt() {
        val framework = FakeFramework().apply { failuresRemaining.set(1) }
        HookRuntime.attach(framework.api)
        val method = targetMethod("failedInstallation")

        val error = runCatching { HookInstaller.hookMethod(method, Hooker { it.proceed() }) }.exceptionOrNull()

        assertSame(framework.installFailure, error)
        val status = HookStatusRegistry.snapshot().getValue(method.toString())
        assertTrue(status.failed)
        assertFalse(status.installed)
        assertTrue(framework.registrations.isEmpty())
    }

    @Test
    fun firstInvocationDuringRegistrationIsNotLost() {
        val framework = FakeFramework()
        HookRuntime.attach(framework.api)
        val method = targetMethod("registrationRace")
        val calls = AtomicInteger()
        framework.beforeHandleReturned = { callback ->
            assertEquals("early", callback.intercept(fakeChain(method, emptyList()) { "early" }))
        }

        HookInstaller.hookMethod(method, Hooker { chain ->
            calls.incrementAndGet()
            chain.proceed()
        })

        val status = HookStatusRegistry.snapshot().getValue(method.toString())
        assertTrue(status.installed)
        assertTrue(status.matched)
        assertEquals(1, calls.get())
        assertEquals(1, framework.registrations.size)
    }

    @Test
    fun onceHookDeduplicatesConcurrentInstallationsOfTheSameMethod() {
        val framework = FakeFramework()
        HookRuntime.attach(framework.api)
        val method = targetMethod("concurrentOnce")
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(8)
        try {
            val requests = (1..16).map {
                pool.submit<HookHandle?> {
                    check(start.await(5, TimeUnit.SECONDS))
                    method.onceHook(Hooker { it.proceed() })
                }
            }
            start.countDown()

            assertEquals(1, requests.map { it.get(5, TimeUnit.SECONDS) }.count { it != null })
            assertEquals(1, framework.registrations.size)
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun onceHookCanRetryAfterAnInstallationFailure() {
        val framework = FakeFramework().apply { failuresRemaining.set(1) }
        HookRuntime.attach(framework.api)
        val method = targetMethod("retryOnce")

        assertSame(framework.installFailure, runCatching { method.onceHook(Hooker { it.proceed() }) }.exceptionOrNull())
        assertNotNull(method.onceHook(Hooker { it.proceed() }))

        assertEquals(2, framework.attempts.get())
        assertEquals(1, framework.registrations.size)
        val recovered = HookStatusRegistry.snapshot().getValue(method.toString())
        assertTrue(recovered.installed)
        assertFalse(recovered.failed)
        assertEquals(null, recovered.reason)
    }

    @Test
    fun onceHookKeepsIdenticalSignaturesFromDifferentClassloadersSeparate() {
        val framework = FakeFramework()
        HookRuntime.attach(framework.api)
        val source = LoaderTarget::class.java
        val bytes = source.getResourceAsStream("/${source.name.replace('.', '/')}.class")!!.use { it.readBytes() }
        fun duplicate(): Class<*> = object : ClassLoader(source.classLoader) {
            fun defineCopy(): Class<*> = defineClass(source.name, bytes, 0, bytes.size)
        }.defineCopy()
        val first = duplicate().getDeclaredMethod("value")
        val second = duplicate().getDeclaredMethod("value")
        assertEquals(first.toString(), second.toString())
        assertNotSame(first.declaringClass.classLoader, second.declaringClass.classLoader)

        assertNotNull(first.onceHook(Hooker { it.proceed() }))
        assertNotNull(second.onceHook(Hooker { it.proceed() }))

        assertEquals(2, framework.registrations.size)
    }

    private fun targetMethod(name: String): Method = Target::class.java.declaredMethods.single { it.name == name }

    private fun fakeChain(method: Method, arguments: List<Any?>, proceed: (List<Any?>) -> Any?): Chain =
        proxy(Chain::class.java) { called, values ->
            when (called.name) {
                "getExecutable" -> method
                "getThisObject" -> null
                "getArgs" -> Collections.unmodifiableList(arguments)
                "getArg" -> arguments[values!![0] as Int]
                "proceed" -> proceed(if (values.isNullOrEmpty()) arguments else (values[0] as Array<*>).toList())
                else -> error("Unexpected chain call: ${called.name}")
            }
        }

    private data class Registration(
        val executable: Executable,
        val priority: Int,
        val mode: XposedInterface.ExceptionMode,
        val callback: Hooker,
    )

    private class FakeFramework {
        val registrations: MutableList<Registration> = Collections.synchronizedList(mutableListOf())
        val attempts = AtomicInteger()
        val failuresRemaining = AtomicInteger()
        val installFailure = IllegalStateException("Cannot register hook")
        var beforeHandleReturned: ((Hooker) -> Unit)? = null
        var invokedMethod: Method? = null
        var invokedReceiver: Any? = null
        var invokedArguments: List<Any?>? = null
        var invokerType: Any? = null

        val api: XposedInterface = proxy(XposedInterface::class.java) { method, args ->
            when (method.name) {
                "hook" -> builder(args!![0] as Executable)
                "getInvoker" -> invoker(args!![0] as Method)
                else -> error("Unexpected framework call: ${method.name}")
            }
        }

        private fun builder(executable: Executable): HookBuilder {
            var priority = XposedInterface.PRIORITY_DEFAULT
            var mode = XposedInterface.ExceptionMode.DEFAULT
            lateinit var builder: HookBuilder
            builder = proxy(HookBuilder::class.java) { method, args ->
                when (method.name) {
                    "setPriority" -> builder.also { priority = args!![0] as Int }
                    "setExceptionMode" -> builder.also { mode = args!![0] as XposedInterface.ExceptionMode }
                    "intercept" -> {
                        attempts.incrementAndGet()
                        if (failuresRemaining.getAndUpdate { if (it > 0) it - 1 else 0 } > 0) throw installFailure
                        val callback = args!![0] as Hooker
                        registrations.add(Registration(executable, priority, mode, callback))
                        beforeHandleReturned?.invoke(callback)
                        proxy(HookHandle::class.java) { called, _ ->
                            when (called.name) {
                                "getExecutable" -> executable
                                "unhook" -> null
                                else -> error("Unexpected handle call: ${called.name}")
                            }
                        }
                    }
                    else -> error("Unexpected builder call: ${method.name}")
                }
            }
            return builder
        }

        private fun invoker(target: Method): Invoker<*, *> {
            lateinit var invoker: Invoker<*, *>
            invoker = proxy(Invoker::class.java) { method, args ->
                when (method.name) {
                    "setType" -> invoker.also { invokerType = args!![0] }
                    "invoke" -> {
                        invokedMethod = target
                        invokedReceiver = args!![0]
                        val arguments = args[1] as Array<*>
                        invokedArguments = arguments.toList()
                        target.invoke(invokedReceiver, *arguments)
                    }
                    else -> error("Unexpected invoker call: ${method.name}")
                }
            }
            return invoker
        }
    }

    class Target {
        val failure = IllegalArgumentException("Listener failed")
        fun original(value: String): String = "received:$value"
        fun throwsFromListener(): Nothing = throw failure
        fun rewrite(value: String): String = value
        fun shortCircuit(value: String): String = value
        fun throwingChain() = Unit
        fun failedInstallation() = Unit
        fun registrationRace() = Unit
        fun concurrentOnce() = Unit
        fun retryOnce() = Unit
    }

    class LoaderTarget { fun value(): Int = 7 }

    companion object {
        private fun <T : Any> proxy(type: Class<T>, call: (Method, Array<out Any?>?) -> Any?): T {
            val instance = Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { proxy, method, args ->
                when (method.name) {
                    "equals" -> proxy === args?.get(0)
                    "hashCode" -> System.identityHashCode(proxy)
                    "toString" -> "Fake${type.simpleName}"
                    else -> call(method, args)
                }
            }
            return requireNotNull(type.cast(instance))
        }
    }
}
