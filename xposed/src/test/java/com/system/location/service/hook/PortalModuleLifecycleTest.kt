package com.system.location.service.hook

import com.system.location.service.hook.scope.HookScope
import com.system.location.service.hook.scope.HookScopeResolver
import com.system.location.service.hook.scope.ProcessIdentity
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
    fun sharedSystemPackagesWaitForCoreHooksAndUseTheirOwnLoadersOnce() {
        val installations = mutableListOf<Installation>()
        val shared = mutableListOf<Pair<String, ClassLoader>>()
        val scheduler = ModuleInstallScheduler(installSystemPackageHooks = { pkg, loader ->
            assertEquals("android", installations.single().pkg)
            shared.add(pkg to loader)
            true
        }) { process, pkg, loader, uid, flags ->
            installations.add(Installation(process, pkg, loader, uid, flags))
        }
        val settingsLoader = loader()
        val fusedLoader = loader()
        val systemLoader = loader()
        scheduler.moduleLoaded("system", true)
        scheduler.systemPackageReady("com.android.providers.settings", settingsLoader)
        scheduler.systemPackageReady("com.android.location.fused", fusedLoader)
        assertTrue(shared.isEmpty())

        scheduler.systemServerStarting(systemLoader)
        scheduler.systemPackageReady("com.android.location.fused", fusedLoader)
        scheduler.systemPackageReady("com.android.providers.settings", settingsLoader)
        scheduler.systemServerStarting(systemLoader)

        assertEquals(listOf("com.android.providers.settings", "com.android.location.fused"), shared.map { it.first })
        assertSame(settingsLoader, shared[0].second)
        assertSame(fusedLoader, shared[1].second)
        assertSame(systemLoader, installations.single().loader)
    }

    @Test
    fun missingSharedPackageHooksRetryOnALaterCallbackWithTheNewLoader() {
        val attemptedLoaders = mutableListOf<ClassLoader>()
        val scheduler = ModuleInstallScheduler(installSystemPackageHooks = { _, loader ->
            attemptedLoaders.add(loader)
            attemptedLoaders.size > 1
        }) { _, _, _, _, _ -> }
        scheduler.moduleLoaded("system_server", true)
        scheduler.systemServerStarting(loader())
        val early = loader()
        val ready = loader()
        scheduler.systemPackageReady("com.android.location.fused", early)
        scheduler.systemPackageReady("com.android.location.fused", ready)
        scheduler.systemPackageReady("com.android.location.fused", ready)

        assertEquals(listOf(early, ready), attemptedLoaders)
    }

    @Test
    fun sharedPackageLoadersSurviveHotReloadWithoutTransferringOldModuleObjects() {
        val old = ModuleInstallScheduler { _, _, _, _, _ -> }
        val fusedLoader = loader()
        old.moduleLoaded("system", true)
        old.systemServerStarting(loader())
        old.systemPackageReady("com.android.location.fused", fusedLoader)
        val installed = mutableListOf<Pair<String, ClassLoader>>()
        val replacement = ModuleInstallScheduler(installSystemPackageHooks = { pkg, loader ->
            installed.add(pkg to loader)
            true
        }) { _, _, _, _, _ -> }
        replacement.systemServerStarting(loader())
        replacement.restoreSystemPackages(old.systemPackageLoaders())
        replacement.systemPackageReady("com.android.location.fused", fusedLoader)

        assertEquals("com.android.location.fused", installed.single().first)
        assertSame(fusedLoader, installed.single().second)
    }

    @Test
    fun unrelatedPackagesAndOldReloadStateDoNotInstallSharedSystemHooks() {
        val installed = mutableListOf<String>()
        val scheduler = ModuleInstallScheduler(installSystemPackageHooks = { pkg, _ ->
            installed.add(pkg)
            true
        }) { _, _, _, _, _ -> }
        scheduler.systemServerStarting(loader())
        scheduler.systemPackageReady("com.android.phone", loader())
        scheduler.systemPackageReady("com.example.app", loader())
        scheduler.restoreSystemPackages(null)
        scheduler.restoreSystemPackages(arrayOf("legacy", arrayOf("com.android.location.fused")))

        assertTrue(installed.isEmpty())
        assertTrue(scheduler.systemPackageLoaders().isEmpty())
    }

    @Test
    fun hotReloadIsLimitedToSystemServerWithoutNativeHooks() {
        assertFalse(HotReloadPolicy.allowed(isSystemProcess = false, nativeLoaded = false))
        assertTrue(HotReloadPolicy.allowed(isSystemProcess = true, nativeLoaded = false))
        assertFalse(HotReloadPolicy.allowed(isSystemProcess = true, nativeLoaded = true))
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
