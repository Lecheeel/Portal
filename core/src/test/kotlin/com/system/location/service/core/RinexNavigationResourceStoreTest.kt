package com.system.location.service.core

import com.system.location.service.core.gnss.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RinexNavigationResourceStoreTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun resource(text: String = RinexNavigationResourceTest.navigation(), downloadedAt: Long = 1000) =
        RinexNavigationResource.import(text, "https://example.org/navigation.rnx", downloadedAt)

    @Test fun failedUpdatePreservesExactAcceptedFileAndReaders() {
        val file = temporary.root.resolve("gnss/navigation.json")
        val store = RinexNavigationResourceStore(file)
        assertNull(store.load())
        val original = store.update(RinexNavigationResource.encode(resource()))
        val bytes = file.readBytes()
        assertThrows(IllegalArgumentException::class.java) { store.update("{broken}") }
        assertArrayEquals(bytes, file.readBytes())
        val oldOrbit = resource(RinexNavigationResourceTest.navigation().replace("2026 11 08", "2026 11 07"), 9999)
        assertThrows(IllegalArgumentException::class.java) { store.update(RinexNavigationResource.encode(oldOrbit)) }
        assertArrayEquals(bytes, file.readBytes())
        assertEquals(original, store.load())
        assertEquals(listOf("navigation.json"), file.parentFile!!.list()!!.toList())
        // Corrupt files can be repaired with a complete validated resource.
        file.writeText("corrupt")
        assertThrows(Exception::class.java) { store.load() }
        assertEquals(original, store.update(RinexNavigationResource.encode(original)))
    }

    @Test fun sameEpochHealthChangesArePromotedAndOneSystemCannotHideAnotherRollback() {
        val file = temporary.root.resolve("navigation.json")
        val store = RinexNavigationResourceStore(file)
        val gps = RinexNavigationResourceTest.navigation()
        val body = gps.substringAfter("END OF HEADER\n")
        val mixed = resource(gps + "\n" + body.replaceFirst('G', 'E'))
        store.update(RinexNavigationResource.encode(mixed))
        assertThrows(IllegalArgumentException::class.java) { store.update(RinexNavigationResource.encode(resource(gps))) }
        val rollback = resource(gps.replace("2026 11 08", "2026 11 09") + "\n" +
            body.replaceFirst('G', 'E').replace("2026 11 08", "2026 11 07"))
        assertThrows(IllegalArgumentException::class.java) { store.update(RinexNavigationResource.encode(rollback)) }
        assertEquals(mixed, store.load())
        val updated = resource(mixed.navigation.replace("2.400000000000D+00 0.000000000000D+00", "2.400000000000D+00 1.000000000000D+00"))
        assertEquals(updated, store.update(RinexNavigationResource.encode(updated)))
        assertEquals(updated, store.load())
        // Downloading again changes metadata, never the native reference labels or digest.
        val again = updated.copy(downloadedAtMillis = 999999)
        assertEquals(again, store.update(RinexNavigationResource.encode(again)))
    }
}
