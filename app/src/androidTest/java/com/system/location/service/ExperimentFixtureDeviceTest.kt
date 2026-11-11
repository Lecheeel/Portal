package com.system.location.service

import androidx.test.platform.app.InstrumentationRegistry
import com.system.location.service.ext.antiDetectEnabled
import com.system.location.service.ext.stackTraceCleanEnabled
import com.system.location.service.runtime.ScenarioRuntime
import kotlinx.coroutines.runBlocking
import org.junit.Test

/** Host-only fixture: change only the explicitly supplied experiment preferences. */
class ExperimentFixtureDeviceTest {
    @Test fun applyFlags() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val args = InstrumentationRegistry.getArguments()
        args.getString("antiDetect")?.let { context.antiDetectEnabled = it.toBooleanStrict() }
        args.getString("stackClean")?.let { context.stackTraceCleanEnabled = it.toBooleanStrict() }
        ScenarioRuntime.applyConfigNow()
        Unit
    }
}
