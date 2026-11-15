package com.system.location.service.environment

import com.system.location.service.core.environment.EnvironmentProfile
import com.system.location.service.core.environment.EnvironmentCodecException
import com.system.location.service.core.environment.EnvironmentProfileCodec
import com.system.location.service.core.environment.EnvironmentSourceType
import com.system.location.service.core.environment.WifiAp
import com.system.location.service.core.geo.Wgs84
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class EnvironmentStoreTest {
    private val directory = Files.createTempDirectory("environment-store").toFile()
    private val store = EnvironmentStore(directory)
    private val profile = EnvironmentProfile(
        source = EnvironmentSourceType.REPLAY,
        origin = Wgs84(31.2304, 121.4737),
        generatedAtMillis = 1,
        seed = 1,
        wifi = listOf(WifiAp("ChinaNet", "A4:50:46:12:34:56", -55, 2412, 1, connected = true)),
    )

    @Test fun saveLoadListDelete() {
        val file = store.save("home", profile)
        assertTrue(file.exists())
        assertEquals(profile, store.load("home"))
        assertEquals(listOf("home"), store.list())
        assertTrue(store.delete("home"))
        assertTrue(store.list().isEmpty())
    }

    @Test fun missingDirectoryIsEmptyButAFileAtItsPathIsAnError() {
        val path = java.io.File(directory, "blocked")
        val other = EnvironmentStore(path)
        assertEquals(emptyList<String>(), other.list())
        path.writeText("preserve this file")
        assertThrows(IllegalArgumentException::class.java) { other.list() }
        assertEquals("preserve this file", path.readText())
    }

    @Test fun unsafeNameIsSanitized() {
        val file = store.save("../evil name", profile)
        assertEquals(".._evil_name.json", file.name)
        assertEquals(profile, store.load("../evil name"))
    }

    @Test fun replaceIsAtomicFromCallerView() {
        store.save("home", profile)
        val changed = profile.copy(seed = 2)
        store.save("home", changed)
        assertEquals(changed, store.load("home"))
    }

    @Test fun saveNewKeepsExistingProfilesAndAddsStableSuffixes() {
        store.save("import-20261115-120000", profile)

        val second = store.saveNew("import-20261115-120000", profile.copy(seed = 2))
        val third = store.saveNew("import-20261115-120000", profile.copy(seed = 3))

        assertEquals("import-20261115-120000-2", second)
        assertEquals("import-20261115-120000-3", third)
        assertEquals(profile, store.load("import-20261115-120000"))
        assertEquals(2L, store.load(second).seed)
        assertEquals(3L, store.load(third).seed)
    }

    @Test fun saveNewWithMaximumLengthNameKeepsSuffixAndNeverOverwrites() {
        val name = "a".repeat(64)
        store.save(name, profile)

        val importedName = store.saveNew(name, profile.copy(seed = 2))

        assertTrue(importedName.length <= 64)
        assertTrue(importedName != name)
        assertEquals(profile, store.load(name))
        assertEquals(2L, store.load(importedName).seed)
    }

    @Test fun concurrentImportsReceiveDifferentNames() {
        val executor = Executors.newFixedThreadPool(6)
        try {
            val names = (1..6).map { seed ->
                executor.submit<String> {
                    EnvironmentStore(directory).saveNew("same-import-time", profile.copy(seed = seed.toLong()))
                }
            }.map { it.get(5, TimeUnit.SECONDS) }

            assertEquals(6, names.toSet().size)
            assertEquals(6, store.list().size)
            assertTrue(names.all { store.load(it).seed in 1L..6L })
        } finally {
            executor.shutdownNow()
        }
    }

    @Test fun concurrentImportAndRenameNeverReplaceTheRenamedProfile() {
        repeat(20) { iteration ->
            val baseName = "capture-$iteration"
            val sourceName = "pending-$iteration"
            store.save(baseName, profile)
            store.save(sourceName, profile.copy(seed = 3))

            val ready = CountDownLatch(2)
            val start = CountDownLatch(1)
            val executor = Executors.newFixedThreadPool(2)
            try {
                val imported = executor.submit<String> {
                    ready.countDown()
                    check(start.await(5, TimeUnit.SECONDS))
                    EnvironmentStore(directory).saveNew(baseName, profile.copy(seed = 2))
                }
                val renamed = executor.submit<Boolean> {
                    ready.countDown()
                    check(start.await(5, TimeUnit.SECONDS))
                    runCatching { EnvironmentStore(directory).rename(sourceName, "$baseName-2") }.isSuccess
                }

                assertTrue(ready.await(5, TimeUnit.SECONDS))
                start.countDown()
                val importedName = imported.get(5, TimeUnit.SECONDS)
                val renameSucceeded = renamed.get(5, TimeUnit.SECONDS)

                assertEquals(2L, store.load(importedName).seed)
                if (renameSucceeded) assertEquals(3L, store.load("$baseName-2").seed)
            } finally {
                start.countDown()
                executor.shutdownNow()
            }
            store.delete(baseName)
            store.delete("$baseName-2")
            store.delete("$baseName-3")
        }
    }

    @Test fun failedReplacementPreservesExistingPathAndCleansTemporaryFile() {
        val blockedDirectory = java.io.File(directory, "blocked.json")
        assertTrue(blockedDirectory.mkdir())
        val marker = java.io.File(blockedDirectory, "keep.txt")
        marker.writeText("preserve")

        assertThrows(java.io.IOException::class.java) { store.save("blocked", profile) }

        assertTrue(blockedDirectory.isDirectory)
        assertEquals("preserve", marker.readText())
        assertTrue(directory.listFiles()?.none { it.name.endsWith(".tmp") } == true)
    }

    @Test fun loadRejectsOversizedAndInvalidUtf8Profiles() {
        val oversized = java.io.File(directory, "oversized.json")
        oversized.writeBytes(ByteArray(EnvironmentProfileCodec.MAX_PROFILE_BYTES + 1) { ' '.code.toByte() })
        assertThrows(EnvironmentCodecException::class.java) { store.load("oversized") }

        val invalidUtf8 = java.io.File(directory, "invalid-utf8.json")
        invalidUtf8.writeBytes(byteArrayOf('{'.code.toByte(), 0xC3.toByte(), 0x28))
        assertThrows(EnvironmentCodecException::class.java) { store.load("invalid-utf8") }
    }

    @Test fun concurrentWritersUseIndependentTemporaryFilesAndLeaveAReadableProfile() {
        val workers = 8
        val ready = CountDownLatch(workers)
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(workers)
        try {
            val writes = (1..workers).map { seed ->
                executor.submit {
                    ready.countDown()
                    check(start.await(5, TimeUnit.SECONDS))
                    EnvironmentStore(directory).save("parallel", profile.copy(seed = seed.toLong()))
                }
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS))
            start.countDown()
            writes.forEach { it.get(5, TimeUnit.SECONDS) }

            assertTrue(store.load("parallel").seed in 1L..workers.toLong())
            assertTrue(directory.listFiles()?.none { it.name.endsWith(".tmp") } == true)
        } finally {
            start.countDown()
            executor.shutdownNow()
        }
    }

    @Test fun renameMovesProfile() {
        store.save("home", profile)
        val renamed = store.rename("home", "office")
        assertEquals("office.json", renamed.name)
        assertEquals(listOf("office"), store.list())
        assertEquals(profile, store.load("office"))
        assertThrows(java.io.FileNotFoundException::class.java) { store.load("home") }
    }

    @Test fun renameRejectsMissingOrExistingTarget() {
        assertThrows(IllegalArgumentException::class.java) { store.rename("missing", "office") }
        store.save("home", profile)
        store.save("office", profile)
        assertThrows(IllegalArgumentException::class.java) { store.rename("home", "office") }
        assertEquals(listOf("home", "office"), store.list())
    }
}
