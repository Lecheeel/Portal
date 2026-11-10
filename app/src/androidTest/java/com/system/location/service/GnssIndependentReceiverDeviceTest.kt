package com.system.location.service

import android.Manifest
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.Process
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
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.*
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in lab integration with the independent Detector debug application, never real sky data. */
@RunWith(AndroidJUnit4::class)
class GnssIndependentReceiverDeviceTest {
    @Test fun movingNoisySamplesRemainCoherentForAnIndependentReceiver() = verifyReceiver()

    @Test fun receiverDeathClearsFrameworkRegistrationAndRestartDoesNotDuplicateFrames() =
        verifyReceiver(terminateFirstRun = true)

    private fun verifyReceiver(terminateFirstRun: Boolean = false) = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue("Explicit independent receiver run required",
            InstrumentationRegistry.getArguments().getString("independentDetector") == "true")
        val context = instrumentation.targetContext
        val manager = context.getSystemService(LocationManager::class.java)
        ScenarioRuntime.awaitInitialized()
        assumeFalse("Preserve local simulation", ScenarioRuntime.state.value.isActive)
        for (permission in listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_BACKGROUND_LOCATION)) {
            assumeTrue("Existing permission required: $permission",
                context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED)
        }
        fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(
            instrumentation.uiAutomation.executeShellCommand(command)).bufferedReader().use { it.readText() }
        val receiverPackage = "com.system.location.service.detector"
        val receiverUid = Regex("uid:(\\d+)").find(shell("cmd package list packages -U $receiverPackage"))
            ?.groupValues?.get(1)?.toInt()
        assumeTrue("Independent Detector must already be installed", receiverUid != null)
        assertNotEquals("Receiver must have a separate UID", Process.myUid(), receiverUid)
        fun statusRegistrations(): Int {
            val dump = shell("dumpsys location").lines()
            val start = dump.indices.single { dump[it].trim() == "Status Provider:" }
            val indent = dump[start].takeWhile(Char::isWhitespace).length
            val section = dump.drop(start + 1).takeWhile { it.isBlank() || it.takeWhile(Char::isWhitespace).length > indent }
            check(section.any { it.trimStart().startsWith("service:") }) { "Provider dump has no service state" }
            return section.count { Regex("^\\s*\\d+/${Regex.escape(receiverPackage)}(?:\\[|/|\\s|$)").containsMatchIn(it) }
        }
        assumeTrue("Preserve existing receiver registrations", statusRegistrations() == 0)
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
        assumeTrue("Preserve user routing and evidence", original.routing == null && original.configuration.gnssSource == null)
        val now = System.currentTimeMillis()
        val observer = Wgs84(31.230416, 121.473701)
        val satellites = Constellation.entries.flatMap { system -> (0..3).map { index ->
            val svid = when (system) {
                Constellation.QZSS -> 193 + index
                Constellation.SBAS -> 120 + index
                else -> index + 1
            }
            GnssSatellite(system, svid, if (index == 3) 75f else 20f, index * 90f,
                32f + index, true, true, true)
        } }
        val fixture = GnssCapture(deviceModel = "SYNTHETIC INDEPENDENT RECEIVER TEST", osVersion = "transport fixture",
            startedAtMillis = now - 60_000L, endedAtMillis = now,
            frames = (0..60).map { index -> GnssObservationFrame(now - 60_000L + index * 1_000L,
                observer = observer, satellites = satellites) })
        var renewal: Job? = null
        var configured = false
        try {
            command("put_config") {
                putString("gnss_capture", GnssCaptureWire.encode(fixture))
                putBoolean("enable_nmea", true)
                putBoolean("enable_physical_noise", true)
                putInt("min_satellites", 0)
            }
            configured = true
            command("start_gnss_mock")
            val startElapsed = SystemClock.elapsedRealtimeNanos()
            fun publish() {
                val elapsed = SystemClock.elapsedRealtimeNanos()
                val seconds = (elapsed - startElapsed) / 1_000_000_000.0
                command("publish_sample") {
                    SampleWire.write(this, LocationSample(Wgs84(observer.latitude + seconds * 0.00002, observer.longitude),
                        altitude = 42.0, accuracy = 5f, speed = 2.2f, bearing = 0f,
                        timeMillis = System.currentTimeMillis(), elapsedNanos = elapsed))
                }
            }
            publish()
            renewal = launch(Dispatchers.IO) { while (isActive) { delay(100L); publish() } }
            val launchResult = shell("am start -W -n $receiverPackage/.MainActivity")
            assertFalse("Cannot launch independent receiver", launchResult.contains("Error:"))
            val rawReport = withContext(Dispatchers.IO) {
                // Raw loopback HTTP is limited to the explicitly enabled lab debug bridge.
                fun request(method: String, path: String, body: String = ""): String = Socket().use { socket ->
                    socket.connect(InetSocketAddress("127.0.0.1", 8766), 500)
                    socket.soTimeout = 60_000
                    socket.getOutputStream().apply {
                        write(("$method $path HTTP/1.1\r\nHost: localhost\r\nContent-Type: application/json\r\n" +
                            "Content-Length: ${body.toByteArray().size}\r\nConnection: close\r\n\r\n$body").toByteArray())
                        flush()
                    }
                    val response = socket.getInputStream().bufferedReader().use { it.readText() }
                    check(response.startsWith("HTTP/1.1 200")) { "Detector bridge failed: ${response.lineSequence().first()}" }
                    response.substringAfter("\r\n\r\n")
                }
                // Activity launch completion need not coincide with the bridge's worker startup.
                suspend fun bridgeReady(): JSONObject = withTimeout(5_000L) {
                    var status: String? = null
                    while (status == null) {
                        try { status = request("GET", "/status") }
                        catch (_: IOException) { delay(100L) }
                    }
                    JSONObject(status)
                }
                val status = bridgeReady()
                assertFalse("Preserve an existing independent receiver run", status.getBoolean("running"))
                if (terminateFirstRun) {
                    // No duration override: killing the process must not persist temporary settings
                    // whose normal restoration would otherwise depend on the bridge's finally block.
                    val interrupted = async { runCatching { request("POST", "/run", "{}") } }
                    withTimeout(5_000L) {
                        while (statusRegistrations() != 1) delay(100L)
                    }
                    assertTrue(request("GET", "/status").let(::JSONObject).getBoolean("running"))
                    val oldPid = shell("pidof $receiverPackage").trim()
                    assertTrue("Independent receiver process missing", oldPid.isNotBlank())
                    shell("am force-stop $receiverPackage")
                    withTimeout(5_000L) {
                        while (statusRegistrations() != 0 || shell("pidof $receiverPackage").isNotBlank()) delay(100L)
                    }
                    assertTrue("Interrupted bridge unexpectedly completed a report", interrupted.await().isFailure)
                    val restart = shell("am start -W -n $receiverPackage/.MainActivity")
                    assertFalse("Cannot restart independent receiver", restart.contains("Error:"))
                    assertFalse(bridgeReady().getBoolean("running"))
                    assertNotEquals("Receiver process was not replaced", oldPid, shell("pidof $receiverPackage").trim())
                }
                request("POST", "/run", "{\"sampleSeconds\":20}")
            }
            File(context.getExternalFilesDir(null), "synthetic-independent-gnss-report.json").writeText(rawReport)
            val report = JSONObject(rawReport)
            val frames = report.getJSONObject("gnssEvidence").getJSONArray("frames")
            assertTrue("Independent receiver did not see the GNSS stream", frames.length() >= 15)
            assertTrue("Duplicate 1 Hz GNSS deliveries in a 20 second window", frames.length() <= 22)
            val expectedCounts = mapOf(1 to 4, 2 to 4, 3 to 4, 4 to 4, 5 to 4, 6 to 4, 7 to 4)
            for (index in 0 until frames.length()) {
                val records = frames.getJSONObject(index).getJSONArray("satellites")
                val counts = (0 until records.length()).map { records.getJSONObject(it).getInt("constellation") }.groupingBy { it }.eachCount()
                assertEquals(expectedCounts, counts)
                for (record in 0 until records.length()) assertTrue(records.getJSONObject(record).getBoolean("usedInFix"))
            }
            val checks = report.getJSONArray("checks")
            val relevant = (0 until checks.length()).map { checks.getJSONObject(it) }.associateBy { it.getString("id") }
            for (id in listOf("C05", "C07")) {
                val check = checkNotNull(relevant[id])
                assertEquals("$id: ${check.getString("detail")}", "PASS", check.getString("status"))
            }
            val locationEvidence = checkNotNull(relevant["C05"]).getJSONArray("evidence")
            val lines = (0 until locationEvidence.length()).map { locationEvidence.getString(it) }
            val coverage = lines.firstNotNullOfOrNull {
                Regex("GPS extras=(\\d+)，同时间窗对账=(\\d+)").find(it)
            } ?: error("Detector did not report GPS extras comparison coverage")
            val suppliedGps = coverage.groupValues[1].toInt()
            val comparedGps = coverage.groupValues[2].toInt()
            assertTrue("GPS callbacks did not exercise the high-rate stream", suppliedGps >= 100)
            assertEquals("Every supplied GPS extras sample must be compared", suppliedGps, comparedGps)
            val distinctGps = lines.firstNotNullOfOrNull { Regex("GPS 不同坐标=(\\d+)").find(it) }
                ?.groupValues?.get(1)?.toInt() ?: error("Detector did not report GPS movement coverage")
            assertTrue("Independent GPS receiver did not observe movement/noise", distinctGps >= 100)
            assertNotEquals("Invalid GNSS fields observed", "FAIL", checkNotNull(relevant["C06"]).getString("status"))
            assertEquals("Completed receiver run leaked a framework status registration", 0, statusRegistrations())
        } finally {
            renewal?.cancelAndJoin()
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
            assertNull(restored.configuration.gnssSource)
            assertFalse(restored.configuration.enabled)
            assertEquals(original.physicalNoise, restored.physicalNoise)
            assertEquals(original.configuration.nmeaEnabled, restored.configuration.nmeaEnabled)
            assertEquals(original.configuration.gnssEnabled, restored.configuration.gnssEnabled)
            assertEquals(original.configuration.minimumSatellites, restored.configuration.minimumSatellites)
        }
    }
}
