package dev.portal.canary

import android.app.Activity
import android.content.Intent
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before

/** Host explicitly owns permissions, switches and module state for these device tests. */
abstract class CanaryDeviceFixture {
    private var activity: Activity? = null

    @Before fun enterFixture() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Requires explicit host canary fixture", args.getString("canaryDevice") == "true")
        if (args.getString("foreground", "true") == "true") {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val context = instrumentation.targetContext
            activity = instrumentation.startActivitySync(Intent(context, CanaryForegroundActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            instrumentation.waitForIdleSync()
            Thread.sleep(500)
        }
    }

    @After fun leaveFixture() {
        activity?.let { activity ->
            InstrumentationRegistry.getInstrumentation().runOnMainSync { activity.finish() }
        }
    }
}
