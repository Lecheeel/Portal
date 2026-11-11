package com.system.location.service.core

import com.system.location.service.core.gnss.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RinexNavigationResourceStoreTest {
    @Test fun perIdentityUploadWatermarksSurviveOmissionAndRestartAndLegacyMigration() {
        val file = temporary.root.resolve("navigation.json")
        val fixture = RinexNavigationResourceTest.navigation()
        val header = fixture.substringBefore("G01")
        val body = fixture.substringAfter("END OF HEADER\n")
        fun upload(svid: String, transmission: Double) = body.replace("G01", "G$svid")
            .replace("-3.000000000000D+01", "%19.12E".format(java.util.Locale.ROOT, transmission))
        fun candidate(vararg bodies: String) = resource(header + bodies.joinToString("\n"))
        val initial = candidate(upload("01", 100.0), upload("02", 200.0))
        // Existing unwrapped cache files are read without retiming or losing upload order.
        file.writeText(RinexNavigationResource.encode(initial))
        val store = RinexNavigationResourceStore(file)
        assertEquals(initial, store.load())
        val omission = candidate(upload("02", 300.0))
        store.update(RinexNavigationResource.encode(omission))
        assertEquals(omission, RinexNavigationResourceStore(file).load())
        val acceptedBytes = file.readBytes()
        val regression = candidate(upload("01", 50.0), upload("02", 400.0))
        assertThrows(IllegalArgumentException::class.java) {
            RinexNavigationResourceStore(file).update(RinexNavigationResource.encode(regression))
        }
        assertArrayEquals(acceptedBytes, file.readBytes())
        val unknown = candidate(upload("01", 9_999_999.0), upload("02", 400.0))
        assertThrows(IllegalArgumentException::class.java) {
            RinexNavigationResourceStore(file).update(RinexNavigationResource.encode(unknown))
        }
        val recovered = candidate(upload("01", 100.0), upload("02", 400.0))
        assertEquals(recovered, RinexNavigationResourceStore(file).update(RinexNavigationResource.encode(recovered)))
        assertEquals(recovered, RinexNavigationResourceStore(file).load())
    }

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
