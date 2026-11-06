package com.system.location.service.core

import com.system.location.service.core.config.ConfigEnvelope
import com.system.location.service.core.geo.Wgs84
import com.system.location.service.core.runtime.FileConfigStore
import com.system.location.service.core.scenario.Scenario
import java.nio.file.Files
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
}
