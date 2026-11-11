package dev.portal.canary

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Runs inside the selected detector UID; no Portal implementation dependency. */
class RuntimeSurfaceDeviceTest : CanaryDeviceFixture() {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun report(json: JSONObject) = instrumentation.sendStatus(0, Bundle().apply {
        putString("canary", json.put("pid", android.os.Process.myPid()).toString())
    })

    @Test fun mapsAndControlFlags() {
        // The module starts its target-state poll asynchronously after Application is available.
        Thread.sleep(2500)
        val reply = Bundle()
        val accepted = context.getSystemService(LocationManager::class.java)
            .sendExtraCommand("fused_ext", "get_anti_detect_state", reply)
        val maps = runCatching { File("/proc/self/maps").readText() }
        val text = maps.getOrDefault("")
        val valid = text.lineSequence().any { it.matches(Regex("^[0-9a-f]+-[0-9a-f]+\\s+[-rwxps]{4}\\s+.*")) }
        report(JSONObject().put("check", "maps").put("accepted", accepted)
            .put("antiDetectFlag", reply.getBoolean("anti_detect"))
            .put("stackFlag", reply.getBoolean("stack_trace"))
            .put("validMaps", valid).put("lineCount", text.lines().size)
            .put("libraryPresent", text.contains("libcodecext") || text.contains("libcodec.so"))
            .put("looksLikeStatus", text.startsWith("Name:"))
            .put("exception", maps.exceptionOrNull()?.toString()))
        val expected = InstrumentationRegistry.getArguments().getString("expectAntiDetect")
        if (expected != null) {
            assertTrue("Target not selected / command unavailable", accepted)
            assertEquals(expected.toBoolean(), reply.getBoolean("anti_detect"))
        }
        assertTrue("Reading maps failed", maps.isSuccess)
        assertTrue("Returned content is not a maps document", valid)
        if (expected == "true") assertFalse("Native library exposed", text.contains("libcodecext") || text.contains("libcodec.so"))
        InstrumentationRegistry.getArguments().getString("holdMillis")?.toLongOrNull()
            ?.coerceIn(0, 20000)?.let(Thread::sleep)
    }

    @Test fun sensorCallbackStacks() {
        Thread.sleep(2500)
        val manager = context.getSystemService(SensorManager::class.java)
        val sensor = manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        assertNotNull("No accelerometer", sensor)
        val latch = CountDownLatch(1)
        var throwableFrames: Array<StackTraceElement> = emptyArray()
        var threadFrames: Array<StackTraceElement> = emptyArray()
        var depth: Int? = null
        var depthError: String? = null
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                if (latch.count == 0L) return
                val throwable = Throwable("sensor canary")
                throwableFrames = throwable.stackTrace
                threadFrames = Thread.currentThread().stackTrace
                val method = Throwable::class.java.declaredMethods.firstOrNull {
                    it.name == "getStackTraceDepth" && it.parameterCount == 0
                }
                if (method == null) depthError = "method absent on this runtime"
                else runCatching { method.isAccessible = true; method.invoke(throwable) as Int }
                    .onSuccess { depth = it }.onFailure { depthError = it.toString() }
                latch.countDown()
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        try {
            assertTrue(manager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_GAME, Handler(Looper.getMainLooper())))
            assertTrue("Sensor callback timed out", latch.await(8, TimeUnit.SECONDS))
        } finally { manager.unregisterListener(listener) }
        fun suspect(frame: StackTraceElement) = frame.className.startsWith("com.system.location.service.") ||
            frame.className.startsWith("io.github.libxposed.") || frame.className.contains("lsposed", ignoreCase = true) ||
            frame.className == "Vector_" || frame.className.endsWith(".HookBridge")
        report(JSONObject().put("check", "sensorStacks")
            .put("throwable", JSONArray(throwableFrames.map { it.toString() }))
            .put("thread", JSONArray(threadFrames.map { it.toString() }))
            .put("nativeDepth", depth).put("depthError", depthError))
        if (InstrumentationRegistry.getArguments().getString("expectClean") == "true") {
            assertFalse("Module frames remain in Throwable stack", throwableFrames.any(::suspect))
            assertFalse("Module frames remain in Thread stack", threadFrames.any(::suspect))
            assertTrue("Target's own frame was removed", throwableFrames.any { it.className.startsWith("dev.portal.canary.") })
        }
    }

    @Test fun lastKnownSamples() {
        val manager = context.getSystemService(LocationManager::class.java)
        val samples = JSONArray()
        val count = InstrumentationRegistry.getArguments().getString("sampleCount")?.toIntOrNull()?.coerceIn(1, 8) ?: 8
        repeat(count) {
            val row = JSONObject().put("elapsedNanos", android.os.SystemClock.elapsedRealtimeNanos())
            for (provider in listOf("gps", "network")) {
                val result = runCatching { manager.getLastKnownLocation(provider) }
                val fix = result.getOrNull()
                row.put(provider, if (fix == null) JSONObject.NULL else JSONObject()
                    .put("latitude", fix.latitude).put("longitude", fix.longitude)
                    .put("accuracy", fix.accuracy).put("mock", fix.isMock)
                    .put("elapsedNanos", fix.elapsedRealtimeNanos).put("time", fix.time))
                result.exceptionOrNull()?.let { row.put(provider + "Error", it.toString()) }
            }
            samples.put(row)
            if (it < count - 1) Thread.sleep(3000)
        }
        report(JSONObject().put("check", "lastKnownSamples").put("samples", samples))
    }
}
