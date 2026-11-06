package com.system.location.service.core

import com.system.location.service.core.config.ConfigEnvelope
import com.system.location.service.core.geo.Wgs84
import com.system.location.service.core.runtime.FileConfigStore
import com.system.location.service.core.scenario.Scenario
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FileConfigStoreTest {
    private val config = ConfigEnvelope(
        revision = 3,
        scenario = Scenario("p", "p", point = Wgs84(31.0, 121.0)),
        updatedAtMillis = 42,
    )

    @Test fun roundTripAndClear() {
        val dir = Files.createTempDirectory("config-store").toFile()
        try {
            val file = dir.resolve("config.json")
            val store = FileConfigStore(file)
            assertNull(store.load())
            store.save(config)
            assertTrue(file.isFile)
            val loaded = store.load()!!
            assertEquals(3L, loaded.revision)
            assertEquals(42L, loaded.updatedAtMillis)
            store.clear()
            assertNull(store.load())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test fun corruptFileIsIgnored() {
        val dir = Files.createTempDirectory("config-store").toFile()
        try {
            val file = dir.resolve("config.json")
            file.writeText("{not json")
            assertNull(FileConfigStore(file).load())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test fun concurrentSaveAndLoadNeverReturnsPartialConfig() {
        val dir = Files.createTempDirectory("config-store").toFile()
        try {
            val store = FileConfigStore(dir.resolve("config.json"))
            val failure = AtomicReference<String?>(null)
            val start = CountDownLatch(1)
            val reader = Thread {
                start.await()
                repeat(2_000) {
                    val loaded = store.load() ?: return@repeat
                    if (loaded.revision !in 1L..200L) failure.set("bad revision ${loaded.revision}")
                }
            }
            reader.start()
            start.countDown()
            repeat(200) { revision ->
                store.save(config.copy(revision = revision + 1L))
            }
            reader.join()
            assertNull(failure.get())
        } finally {
            dir.deleteRecursively()
        }
    }
}
