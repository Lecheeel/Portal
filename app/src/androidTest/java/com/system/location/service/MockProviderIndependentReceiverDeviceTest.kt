package com.system.location.service

import android.Manifest
import android.app.AppOpsManager
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.system.location.service.backend.mock.AndroidMockProviderPort
import com.system.location.service.backend.mock.MockProviderBackend
import com.system.location.service.backend.mock.MockProviderPort
import com.system.location.service.core.backend.BackendResult
import com.system.location.service.core.geo.Wgs84
import com.system.location.service.core.location.LocationSample
import com.system.location.service.runtime.ScenarioRuntime
import com.system.location.service.service.MockServiceHelper
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

/** Standard APIs observed by a separate UID, with no system Hook authority. */
@RunWith(AndroidJUnit4::class)
class MockProviderIndependentReceiverDeviceTest {
    @Test fun independentReceiverObservesMovementAndSystemMockMarkerWithoutGnssExtras() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(arguments.getString("standardProviderDevice") == "true")
        val receiver = arguments.getString("standardDetectorPackage")
        assumeTrue("Explicit lab Detector package required", receiver != null)
        require(checkNotNull(receiver).matches(Regex("[A-Za-z][A-Za-z0-9_.]+")))
        val context = instrumentation.targetContext
        val manager = context.getSystemService(LocationManager::class.java)
        ScenarioRuntime.awaitInitialized()
        assumeFalse("Preserve local simulation", ScenarioRuntime.state.value.isActive)
        for (permission in listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_BACKGROUND_LOCATION)) {
            assumeTrue(context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED)
        }
        assumeTrue(manager.isProviderEnabled(LocationManager.GPS_PROVIDER))
        MockServiceHelper.tryInitService(manager)
        assertNull("Hook authority must be absent", MockServiceHelper.runtimeStatus(manager))
        fun shell(command: String) = ParcelFileDescriptor.AutoCloseInputStream(
            instrumentation.uiAutomation.executeShellCommand(command)).bufferedReader().use { it.readText() }
        val receiverUid = Regex("uid:(\\d+)").find(shell("cmd package list packages -U $receiver"))
            ?.groupValues?.get(1)?.toInt()
        assumeTrue("Detector must already be installed", receiverUid != null)
        assertNotEquals(Process.myUid(), receiverUid)
        val receiverState = shell("dumpsys package $receiver")
        assumeTrue("Grant the lab receiver precise location before starting",
            receiverState.contains("android.permission.ACCESS_FINE_LOCATION: granted=true"))
        if (receiver != "com.system.location.service.detector") {
            assumeTrue("Avoid another debug bridge on port 8766",
                shell("pidof com.system.location.service.detector").isBlank())
        }
        val realPort = AndroidMockProviderPort(context)
        assumeTrue("Preserve provider ownership", realPort.pendingProviders().isEmpty())
        val backend = MockProviderBackend(object : MockProviderPort by realPort {
            override fun optionalProviders() = emptySet<String>()
        })
        val mode = context.getSystemService(AppOpsManager::class.java)
            .unsafeCheckOpNoThrow(AppOpsManager.OPSTR_MOCK_LOCATION, Process.myUid(), context.packageName)
        val restoreMode = when (mode) {
            AppOpsManager.MODE_ALLOWED -> "allow"
            AppOpsManager.MODE_IGNORED -> "ignore"
            AppOpsManager.MODE_ERRORED -> "deny"
            AppOpsManager.MODE_FOREGROUND -> "foreground"
            else -> "default"
        }
        fun authorize(value: String) { shell("appops set ${context.packageName} android:mock_location $value") }
        fun request(method: String, path: String, body: String = ""): JSONObject = Socket().use { socket ->
            socket.connect(InetSocketAddress("127.0.0.1", 8766), 500)
            socket.soTimeout = 60_000
            socket.getOutputStream().apply {
                write(("$method $path HTTP/1.1\r\nHost: localhost\r\nContent-Type: application/json\r\n" +
                    "Content-Length: ${body.toByteArray().size}\r\nConnection: close\r\n\r\n$body").toByteArray())
                flush()
            }
            val response = socket.getInputStream().bufferedReader().use { it.readText() }
            check(response.startsWith("HTTP/1.1 200")) { response.lineSequence().first() }
            JSONObject(response.substringAfter("\r\n\r\n"))
        }
        var publisher: Job? = null
        try {
            val launch = shell("am start -n $receiver/com.system.location.service.detector.MainActivity")
            assertFalse(launch, launch.contains("Error:"))
            withContext(Dispatchers.IO) {
                withTimeout(5_000L) {
                    while (true) {
                        try {
                            assertFalse("Preserve existing Detector run", request("GET", "/status").getBoolean("running"))
                            break
                        } catch (_: IOException) { delay(100L) }
                    }
                }
            }
            authorize("allow")
            assertEquals(BackendResult.Success, backend.prepare())
            assertEquals(BackendResult.Success, backend.start())
            val start = SystemClock.elapsedRealtimeNanos()
            publisher = launch(Dispatchers.IO) {
                while (isActive) {
                    val elapsed = SystemClock.elapsedRealtimeNanos()
                    val seconds = (elapsed - start) / 1_000_000_000.0
                    val sample = LocationSample(Wgs84(31.230416 + seconds * 0.00002, 121.473701),
                        altitude = 42.0, accuracy = 5f, speed = 2.2f, bearing = 0f,
                        timeMillis = System.currentTimeMillis(), elapsedNanos = elapsed)
                    assertEquals(BackendResult.Success, backend.publish(sample, null))
                    delay(100L)
                }
            }
            val report = withContext(Dispatchers.IO) { request("POST", "/run", "{\"sampleSeconds\":20}") }
            File(context.cacheDir, "standard-independent-detector.json").writeText(report.toString())
            val checks = report.getJSONArray("checks")
            val byId = (0 until checks.length()).map(checks::getJSONObject).associateBy { it.getString("id") }
            val mock = checkNotNull(byId["C01"])
            // FAIL is expected here: the standard system API preserves the mock marker.
            assertEquals(mock.getString("detail"), "FAIL", mock.getString("status"))
            val marked = checkNotNull(Regex("^(\\d+)/(\\d+)").find(mock.getString("detail")))
            assertEquals("Every inspected callback must retain its system mock marker",
                marked.groupValues[2], marked.groupValues[1])
            assertTrue(marked.groupValues[2].toInt() >= 30)
            val fields = checkNotNull(byId["C05"])
            assertEquals(fields.getString("detail"), "PASS", fields.getString("status"))
            val evidence = fields.getJSONArray("evidence")
            val lines = (0 until evidence.length()).map(evidence::getString)
            val distinct = lines.firstNotNullOfOrNull { Regex("GPS 不同坐标=(\\d+)").find(it) }
                ?.groupValues?.get(1)?.toInt() ?: error("Missing independent GPS coverage")
            // CheckSampler requests 500ms updates; standard Android honors that request. A 20s
            // run should observe about 40 GPS samples, with a 25% scheduling tolerance.
            assertTrue("Independent receiver did not observe the moving stream: $distinct", distinct >= 30)
            assertTrue("Standard backend supplied synthetic GNSS extras", lines.any { it.contains("GPS extras=0，") })
            assertNull(MockServiceHelper.runtimeStatus(manager))
        } finally {
            publisher?.cancelAndJoin()
            try {
                authorize("allow")
                assertEquals(BackendResult.Success, backend.stop())
                assertTrue(realPort.pendingProviders().isEmpty())
            } finally { authorize(restoreMode) }
        }
    }
}
