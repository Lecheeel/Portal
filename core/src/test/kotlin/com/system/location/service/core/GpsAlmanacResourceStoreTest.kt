package com.system.location.service.core

import com.system.location.service.core.gnss.*
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class GpsAlmanacResourceStoreTest {
    @get:Rule val temporary = TemporaryFolder()
    private val utc = GnssUtcTimeScale.parseIers(requireNotNull(
        javaClass.getResource("/gnss/leap-seconds.list")).readText())
    private val resource = GpsAlmanacResource.decode(requireNotNull(
        javaClass.getResource("/gnss/gps-yuma.json")).readText())

    @Test fun absentCacheAndRestartLoadDoNotRequireCapture() {
        val file = File(temporary.root, "orbits/gps.json")
        val store = GpsAlmanacResourceStore(file, utc)
        assertNull(store.load())
        assertEquals(resource, store.update(GpsAlmanacResource.encode(resource)))
        assertEquals(resource, GpsAlmanacResourceStore(file, utc).load())
        assertEquals(listOf("gps.json"), file.parentFile.list()!!.toList())
    }

    @Test fun malformedOrCorruptUpdateKeepsLastCompleteFileByteForByte() {
        val file = temporary.newFile("gps.json")
        val store = GpsAlmanacResourceStore(file, utc)
        store.update(GpsAlmanacResource.encode(resource))
        val before = file.readBytes()
        for (text in listOf("{broken", GpsAlmanacResource.encode(resource.copy(sha256 = "0".repeat(64))),
            GpsAlmanacResource.encode(resource.copy(referenceEpochMillis = resource.referenceEpochMillis + 1000)))) {
            assertThrows(IllegalArgumentException::class.java) { store.update(text) }
            assertArrayEquals(before, file.readBytes())
            assertEquals(resource, store.load())
        }
    }

    @Test fun olderDownloadDoesNotReplaceNewerOrbitButCorruptCacheCanBeRepaired() {
        val file = temporary.newFile("gps.json")
        val store = GpsAlmanacResourceStore(file, utc)
        val olderYuma = resource.yuma.replace("503808.0000", "409600.0000")
        val older = GpsAlmanacResource.importYuma(olderYuma, resource.sourceUrl,
            resource.downloadedAtMillis, resource.downloadedAtMillis + 1000, utc)
        assertTrue(older.referenceEpochMillis < resource.referenceEpochMillis)
        store.update(GpsAlmanacResource.encode(resource))
        assertThrows(IllegalArgumentException::class.java) { store.update(GpsAlmanacResource.encode(older)) }
        assertEquals(resource, store.load())
        file.writeText("broken")
        assertThrows(IllegalArgumentException::class.java) { store.load() }
        store.update(GpsAlmanacResource.encode(resource))
        assertEquals(resource, store.load())
    }
}
