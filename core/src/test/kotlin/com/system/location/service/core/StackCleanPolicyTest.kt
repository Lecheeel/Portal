package com.system.location.service.core.privacy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Work package 9: reproduce the observable artifacts of the two stack-clean strategies before
 * shipping either as an experiment.
 */
class StackCleanPolicyTest {
    private fun frame(cls: String, method: String = "call", line: Int = 10) =
        StackTraceElement(cls, method, "$cls.kt", line)

    private fun sample(): Array<StackTraceElement> = arrayOf(
        frame("com.example.detector.Main", "check"),
        // libxposed dispatch pair: module frame between two ordinary frames.
        frame("io.github.libxposed.api.XposedInterface", "handle"),
        frame("com.system.location.service.hook.privacy.StackTraceCleanHook", "clean"),
        frame("de.robv.android.xposed.XposedBridge", "invoke"),
        frame("android.hardware.SystemSensorManager\$SensorEventQueue", "dispatchSensorEvent"),
        frame("com.example.detector.SensorProbe", "onSensorChanged"),
    )

    private val moduleFrames = sample().count(StackCleanPolicy::isModuleFrame)

    @Test
    fun filterRemovesModuleFramesAndShortensTheArray() {
        val original = sample()
        val cleaned = StackCleanPolicy.filter(original)
        // The reproduction: the Java-reported array is now shorter than the native
        // getStackTraceDepth() count would still be, which is the documented detectable artifact.
        assertNotEquals(original.size, cleaned.size)
        assertEquals(original.size - moduleFrames, cleaned.size)
        cleaned.forEach { assertFalse(StackCleanPolicy.isModuleFrame(it)) }
    }

    @Test
    fun filterCollapsesOnlyExactConsecutiveDispatchDuplicates() {
        val duplicated = arrayOf(
            frame("com.example.detector.Main", "check"),
            frame("android.hardware.SystemSensorManager\$SensorEventQueue", "dispatchSensorEvent"),
            frame("android.hardware.SystemSensorManager\$SensorEventQueue", "dispatchSensorEvent"),
            frame("com.example.detector.SensorProbe", "onSensorChanged"),
        )
        val cleaned = StackCleanPolicy.filter(duplicated)
        assertEquals(duplicated.size - 1, cleaned.size)
        // Non-consecutive duplicates of other methods must survive: never generic recursion collapse.
        val spread = arrayOf(
            frame("com.example.detector.Main", "check"),
            frame("com.example.detector.Middle", "run"),
            frame("com.example.detector.Main", "check"),
        )
        assertEquals(spread.size, StackCleanPolicy.filter(spread).size)
    }

    @Test
    fun replaceKeepsArrayLengthIdenticalToTheNativeDepth() {
        val original = sample()
        val cleaned = StackCleanPolicy.replace(original)
        // Length parity: the cheapest consistency check (array length vs getStackTraceDepth)
        // now agrees, at the cost of a synthetic neutral frame.
        assertEquals(original.size, cleaned.size)
        cleaned.forEachIndexed { index, frame ->
            if (StackCleanPolicy.isModuleFrame(original[index])) {
                assertEquals(StackCleanPolicy.NEUTRAL_CLASS, frame.className)
                assertEquals(StackCleanPolicy.NEUTRAL_METHOD, frame.methodName)
            } else {
                assertEquals(original[index], frame)
            }
        }
    }

    @Test
    fun replaceReportsNoModuleFramesAfterCleaning() {
        val cleaned = StackCleanPolicy.replace(sample())
        cleaned.forEach { assertFalse(StackCleanPolicy.isModuleFrame(it)) }
    }

    @Test
    fun hasModuleFramesAllowsIdentityFastPath() {
        val clean = arrayOf(frame("com.example.detector.Main"), frame("android.os.Looper", "loop"))
        assertFalse(StackCleanPolicy.hasModuleFrames(clean))
        assertTrue(StackCleanPolicy.hasModuleFrames(sample()))
    }

    @Test
    fun emptyAndSingleElementArraysAreSafe() {
        val empty = arrayOf<StackTraceElement>()
        assertEquals(0, StackCleanPolicy.filter(empty).size)
        assertEquals(0, StackCleanPolicy.replace(empty).size)
        val single = arrayOf(frame("com.system.location.service.hook.FakeLoc", "config"))
        assertEquals(0, StackCleanPolicy.filter(single).size)
        assertEquals(1, StackCleanPolicy.replace(single).size)
    }
}
