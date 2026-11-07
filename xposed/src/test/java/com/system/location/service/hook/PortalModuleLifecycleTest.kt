package com.system.location.service.hook

import com.system.location.service.hook.scope.HookScope
import com.system.location.service.hook.scope.HookScopeResolver
import com.system.location.service.hook.scope.ProcessIdentity
import io.github.libxposed.api.XposedModuleInterface
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Exercises the actual install scheduler without installing Android hooks in the JVM. */
class PortalModuleLifecycleTest {
    @Test
    fun sharedAndroidPackageDoesNotPreventTheLaterPhonePackageFromInstalling() {
        val installations = mutableListOf<Installation>()
        val scheduler = recorder(installations)
        val loader = loader()
        scheduler.moduleLoaded("com.android.phone", false)

        // The framework reports android as its first package and phone as a later package.
        scheduler.packageReady("android", loader, 1001, 1)
        scheduler.packageReady("com.android.phone", loader, 1001, 1)

        assertEquals(listOf("android", "com.android.phone"), installations.map { it.pkg })
        val phone = installations.last()
        assertEquals("com.android.phone", phone.process)
        assertEquals(HookScope.PHONE_PROCESS, HookScopeResolver.resolve(
            ProcessIdentity(phone.pkg, phone.process, phone.uid, 37, "test")))
        assertSame(loader, phone.loader)
    }

    @Test
    fun systemServerWaitsForItsOfficialLoaderAndIgnoresPackageCallbacks() {
        val installations = mutableListOf<Installation>()
        val scheduler = recorder(installations)
        val packageLoader = loader()
        val systemLoader = loader()

        scheduler.moduleLoaded("system_server", true)
        scheduler.packageReady("android", packageLoader, 1000, 1)
        assertTrue(installations.isEmpty())

        scheduler.systemServerStarting(systemLoader)
        scheduler.packageReady("com.android.phone", packageLoader, 1001, 1)

        val installation = installations.single()
        assertSame(systemLoader, installation.loader)
        assertEquals("system_server", installation.process)
        assertEquals("android", installation.pkg)
        assertEquals(1000, installation.uid)
    }

    @Test
    fun duplicatePackageAndSystemCallbacksDoNotInstallTwice() {
        val installations = mutableListOf<Installation>()
        val packages = recorder(installations)
        packages.moduleLoaded("com.android.phone", false)
        packages.packageReady("com.android.phone", loader(), 1001, 7)
        packages.packageReady("com.android.phone", loader(), 1001, 9)
        assertEquals(1, installations.size)
        assertEquals(7, installations.single().flags)

        val system = recorder(installations)
        system.moduleLoaded("system_server", true)
        system.systemServerStarting(loader())
        system.systemServerStarting(loader())
        assertEquals(2, installations.size)
    }

    @Test
    fun failedInstallationCanBeRetriedByTheNextCallback() {
        val expected = IllegalStateException("Installer unavailable")
        var attempts = 0
        val scheduler = ModuleInstallScheduler { _, _, _, _, _ ->
            if (attempts++ == 0) throw expected
        }
        scheduler.moduleLoaded("com.android.phone", false)

        assertSame(expected, runCatching {
            scheduler.packageReady("com.android.phone", loader(), 1001, 0)
        }.exceptionOrNull())
        scheduler.packageReady("com.android.phone", loader(), 1001, 0)
        scheduler.packageReady("com.android.phone", loader(), 1001, 0)

        assertEquals(2, attempts)
    }

    @Test
    fun manualHotReloadIsRejectedWithoutTransferringPartialState() {
        val param = Proxy.newProxyInstance(
            javaClass.classLoader,
            arrayOf(XposedModuleInterface.HotReloadingParam::class.java),
        ) { _, method, _ -> error("Reload must not access ${method.name}") }
            as XposedModuleInterface.HotReloadingParam

        assertFalse(PortalModule().onHotReloading(param))
    }

    private fun recorder(installations: MutableList<Installation>): ModuleInstallScheduler =
        ModuleInstallScheduler { process, pkg, loader, uid, flags ->
            installations.add(Installation(process, pkg, loader, uid, flags))
        }

    private fun loader(): ClassLoader = object : ClassLoader(javaClass.classLoader) {}

    private data class Installation(
        val process: String,
        val pkg: String,
        val loader: ClassLoader,
        val uid: Int,
        val flags: Int,
    )
}
