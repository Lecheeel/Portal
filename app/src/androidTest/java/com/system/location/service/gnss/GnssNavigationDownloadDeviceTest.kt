package com.system.location.service.gnss

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.system.location.service.core.gnss.RinexNavigationResource
import com.system.location.service.core.gnss.RinexNavigationResourceStore
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Explicit public-network integration; no location permission, live configuration or Hook mutation. */
@RunWith(AndroidJUnit4::class)
class GnssNavigationDownloadDeviceTest {
    @Test fun publicResourcesDownloadValidateAndRoundTripOnAndroid() = runBlocking {
        assumeTrue("Explicit external navigation run required",
            InstrumentationRegistry.getArguments().getString("externalNavigation") == "true")
        if (InstrumentationRegistry.getArguments().getString("catalogOnly") == "true") {
            val catalog = HttpsGnssBeiDouCatalogDownload().fetch(System.currentTimeMillis())
            assertEquals(catalog, catalog.validate())
            android.util.Log.i("GnssNetworkProbe", "BeiDou catalog accepted: epoch=${catalog.creationLabel}")
            return@runBlocking
        }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "navigation-network-probe-${System.nanoTime()}")
        assertTrue(directory.mkdirs())
        val file = File(directory, "navigation.json")
        try {
            val status = GnssNavigationSyncStatus()
            assertTrue(GnssNavigationRepository(file, status = status).refresh())
            assertNull("Both public endpoints must succeed: ${status.state.value.problem}", status.state.value.problem)
            val accepted = requireNotNull(RinexNavigationResourceStore(file).load())
            assertNotNull(accepted.beiDouCatalog)
            val wire = RinexNavigationResource.encodeWire(accepted)
            assertEquals(accepted, RinexNavigationResource.decodeWire(wire))
            val restarted = RinexNavigationResourceStore(file).load()
            assertEquals(accepted, restarted)
            android.util.Log.i("GnssNetworkProbe", "Both resources accepted: navigationBytes=${accepted.navigation.length}, " +
                "wireChars=${wire.length}, catalogEpoch=${accepted.beiDouCatalog!!.creationLabel}")
            Unit
        } finally {
            file.delete()
            directory.delete()
        }
    }
}
