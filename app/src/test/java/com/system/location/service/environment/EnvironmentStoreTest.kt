package com.system.location.service.environment

import com.system.location.service.core.environment.EnvironmentProfile
import com.system.location.service.core.environment.EnvironmentSourceType
import com.system.location.service.core.environment.WifiAp
import com.system.location.service.core.geo.Wgs84
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

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
}
