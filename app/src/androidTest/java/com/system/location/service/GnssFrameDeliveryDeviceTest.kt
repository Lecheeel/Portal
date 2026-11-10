package com.system.location.service

import android.Manifest
import android.content.pm.PackageManager
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.location.OnNmeaMessageListener
import android.os.Bundle
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.system.location.service.core.geo.Wgs84
import com.system.location.service.core.gnss.*
import com.system.location.service.core.location.LocationSample
import com.system.location.service.core.runtime.RuntimeTransferWire
import com.system.location.service.hook.RuntimeSyncCommand
import com.system.location.service.hook.security.CommandClient
import com.system.location.service.hook.security.SampleWire
import com.system.location.service.runtime.ScenarioRuntime
import java.util.concurrent.ConcurrentLinkedQueue
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Assume.*
import org.junit.Test
import org.junit.runner.RunWith

/** Synthetic transport fixture only: no real-profile, constellation-distribution or orbit claim. */
@RunWith(AndroidJUnit4::class)
class GnssFrameDeliveryDeviceTest {
    private data class Status(val arrived: Long, val satellites: List<GnssSatellite>)
    private data class Sentence(val epoch: Long, val text: String)

    @Test fun sharedFrameReachesStatusNmeaAndLocationExtrasThroughSystemServer() = runBlocking {
        assumeTrue("Explicit device run required", InstrumentationRegistry.getArguments().getString("libxposedDevice") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val manager = context.getSystemService(LocationManager::class.java)
        ScenarioRuntime.awaitInitialized()
        assumeFalse("Preserve local simulation", ScenarioRuntime.state.value.isActive)
        for (permission in listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_BACKGROUND_LOCATION)) {
            assumeTrue("Existing permission required: $permission",
                context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED)
        }
        assumeTrue("GPS must already be enabled", manager.isProviderEnabled(LocationManager.GPS_PROVIDER))
        val client = CommandClient()
        assertTrue(client.connect(manager))
        fun command(name: String, fill: Bundle.() -> Unit = {}): Bundle = Bundle().apply {
            putString("command_id", name); fill()
            assertTrue("Command failed: $name", client.send(manager, this))
        }
        val initial = command("get_runtime_status")
        assertEquals("SYSTEM_SERVER", initial.getString("hook_scope"))
        assumeFalse("Preserve system simulation", initial.getBoolean("is_start"))
        val original = RuntimeTransferWire.decode(checkNotNull(command("sync_config").getByteArray(RuntimeSyncCommand.PAYLOAD))).state
        assumeTrue("Fixture requires global routing; preserve existing rules", original.routing == null)
        assumeTrue("Fixture requires no production source; do not replace user evidence", original.configuration.gnssSource == null)
        val statuses = ConcurrentLinkedQueue<Status>()
        val sentences = ConcurrentLinkedQueue<Sentence>()
        val locations = ConcurrentLinkedQueue<Location>()
        val callback = object : GnssStatus.Callback() {
            override fun onSatelliteStatusChanged(status: GnssStatus) {
                val satellites = (0 until status.satelliteCount).map { index ->
                    GnssSatellite(system(status.getConstellationType(index)), status.getSvid(index),
                        status.getElevationDegrees(index), status.getAzimuthDegrees(index), status.getCn0DbHz(index),
                        status.usedInFix(index), status.hasEphemerisData(index), status.hasAlmanacData(index))
                }
                statuses += Status(System.currentTimeMillis(), satellites)
            }
        }
        val nmea = OnNmeaMessageListener { message, timestamp -> sentences += Sentence(timestamp, message) }
        val location = LocationListener { locations += Location(it) }
        val now = System.currentTimeMillis()
        val observer = Wgs84(31.230416, 121.473701)
        val satellites = Constellation.entries
            .flatMap { constellation -> (0..3).map { index ->
                val svid = when (constellation) {
                    Constellation.QZSS -> 193 + index
                    Constellation.SBAS -> 120 + index
                    else -> index + 1
                }
                GnssSatellite(constellation, svid, if (index == 3) 75f else 20f,
                    index * 90f, 32f + index, true, true, true)
            } }
        val fixture = GnssCapture(deviceModel = "SYNTHETIC DEVICE TEST", osVersion = "transport fixture",
            startedAtMillis = now - 60_000L, endedAtMillis = now,
            frames = (0..60).map { index -> GnssObservationFrame(now - 60_000L + index * 1_000L,
                observer = observer, satellites = satellites) })
        assertNotNull("Fixture must have valid geometry", GnssGeometry.solve(satellites).dop)
        var renewal: Job? = null
        var configured = false
        try {
            command("put_config") {
                putString("gnss_capture", GnssCaptureWire.encode(fixture))
                putBoolean("enable_nmea", true)
                putBoolean("enable_physical_noise", false)
                putInt("min_satellites", 0)
            }
            configured = true
            command("start_gnss_mock")
            assertTrue(manager.registerGnssStatusCallback({ it.run() }, callback))
            assertTrue(manager.addNmeaListener({ it.run() }, nmea))
            fun publish() {
                command("publish_sample") {
                    SampleWire.write(this, LocationSample(observer, altitude = 42.0, accuracy = 5f,
                        speed = 0f, bearing = 73f, timeMillis = System.currentTimeMillis(), elapsedNanos = SystemClock.elapsedRealtimeNanos()))
                }
            }
            publish()
            renewal = launch(Dispatchers.IO) { while (isActive) { delay(100L); publish() } }
            manager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 0L, 0f, { it.run() }, location)
            withTimeout(12_000L) {
                while (statuses.size < 3 || sentences.count { type(it.text) == "GGA" } < 3 || locations.size < 3) delay(100L)
            }
            val expectedIds = satellites.map { it.constellation to it.svid }.toSet()
            for (status in statuses) {
                assertEquals(expectedIds, status.satellites.map { it.constellation to it.svid }.toSet())
                assertTrue(status.satellites.all { it.usedInFix && it.hasEphemeris && it.hasAlmanac })
                assertEquals(satellites.sortedBy { it.svid }.map { it.cn0DbHz }.sorted(), status.satellites.map { it.cn0DbHz }.sorted())
            }
            val groups = sentences.groupBy { it.epoch }
            val complete = groups.values.filter { batch -> batch.any { type(it.text) == "ZDA" } }
            assertTrue("Need at least two complete epochs", complete.size >= 2)
            val expectedDop = checkNotNull(GnssGeometry.solve(satellites).dop)
            for (batch in complete) {
                val gga = batch.single { type(it.text) == "GGA" }.text.substringBefore('*').split(',')
                assertEquals(1, gga[6].toInt())
                assertEquals(satellites.size, gga[7].toInt())
                assertEquals(expectedDop.hdop, gga[8].toDouble(), 0.051)
                assertEquals("One GGA per frame", 1, batch.count { type(it.text) == "GGA" })
                val gsv = batch.filter { type(it.text) == "GSV" }
                assertEquals(setOf("GP", "BD", "GA", "GL", "GQ", "GI"), gsv.map { it.text.substring(1, 3) }.toSet())
                assertEquals(7, gsv.size) // GPS and SBAS share GP, requiring two GSV pages.
                for ((talker, pages) in gsv.groupBy { it.text.substring(1, 3) }) {
                    val expected = when (talker) {
                        "GP" -> listOf(1, 2, 3, 4, 33, 34, 35, 36)
                        "GL" -> listOf(65, 66, 67, 68)
                        else -> listOf(1, 2, 3, 4)
                    }
                    val ids = pages.sortedBy { it.text.substringBefore('*').split(',')[2].toInt() }.flatMap { sentence ->
                        val fields = sentence.text.substringBefore('*').split(',')
                        assertEquals(expected.size, fields[3].toInt())
                        (4 until fields.size step 4).map { fields[it].toInt() }
                    }
                    assertEquals(expected, ids)
                }
                val gsa = batch.filter { type(it.text) == "GSA" }.map { it.text.substringBefore('*').split(',') }
                assertEquals(setOf("1", "2", "3", "4", "5", "6"), gsa.map { it.last() }.toSet())
                assertEquals(satellites.size, gsa.sumOf { fields -> fields.subList(3, 15).count { it.isNotBlank() } })
            }
            for (fix in locations) {
                val extras = checkNotNull(fix.extras) { "Missing extras: sampleAfterCapture=${fix.time - fixture.endedAtMillis}ms provider=${fix.provider}" }
                assertEquals(satellites.size, extras.getInt("satellites", -1))
                assertEquals(33.5f, extras.getFloat("meanCn0", -1f), 0.001f)
                assertEquals(35f, extras.getFloat("maxCn0", -1f), 0.001f)
            }
            // A requested lower bound cannot invent identities beyond the fixture.
            command("put_config") { putInt("min_satellites", 128) }
            statuses.clear()
            withTimeout(5_000L) { while (statuses.size < 2) delay(100L) }
            assertTrue(statuses.all { it.satellites.size == satellites.size })
        } finally {
            renewal?.cancelAndJoin()
            try {
                manager.removeUpdates(location)
                manager.unregisterGnssStatusCallback(callback)
                manager.removeNmeaListener(nmea)
            } finally {
                command("stop")
                if (configured) {
                    command("put_config") {
                        putString("gnss_capture", null)
                        putBoolean("enable_nmea", original.configuration.nmeaEnabled)
                        putBoolean("enable_physical_noise", original.physicalNoise)
                        putInt("min_satellites", original.configuration.minimumSatellites)
                    }
                    command(if (original.configuration.gnssEnabled) "start_gnss_mock" else "stop_gnss_mock")
                }
                val restored = RuntimeTransferWire.decode(checkNotNull(command("sync_config").getByteArray(RuntimeSyncCommand.PAYLOAD))).state
                assertNull("Synthetic evidence must not survive fixture cleanup", restored.configuration.gnssSource)
                assertFalse(restored.configuration.enabled)
            }
        }
    }

    private fun type(sentence: String) = sentence.substring(3, 6)

    private fun system(androidType: Int): Constellation = when (androidType) {
        GnssStatus.CONSTELLATION_GPS -> Constellation.GPS
        GnssStatus.CONSTELLATION_BEIDOU -> Constellation.BEIDOU
        GnssStatus.CONSTELLATION_GALILEO -> Constellation.GALILEO
        GnssStatus.CONSTELLATION_GLONASS -> Constellation.GLONASS
        GnssStatus.CONSTELLATION_QZSS -> Constellation.QZSS
        GnssStatus.CONSTELLATION_IRNSS -> Constellation.IRNSS
        GnssStatus.CONSTELLATION_SBAS -> Constellation.SBAS
        else -> error("Unknown constellation")
    }
}
