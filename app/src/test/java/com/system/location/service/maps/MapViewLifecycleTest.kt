package com.system.location.service.maps

import com.system.location.service.maps.MapViewLifecycle.Event.*
import org.junit.Assert.assertEquals
import org.junit.Test

class MapViewLifecycleTest {
    @Test fun compositionAndLifecycleCatchupDoNotResumeTheSdkTwice() {
        val events = mutableListOf<MapViewLifecycle.Event>()
        val lifecycle = MapViewLifecycle { events += it }
        lifecycle.start()
        lifecycle.resume()
        lifecycle.start()
        lifecycle.resume()
        assertEquals(listOf(START, RESUME), events)
    }

    @Test fun returningAfterTheViewStopsCreatesOneNewRenderingSession() {
        val events = mutableListOf<MapViewLifecycle.Event>()
        val lifecycle = MapViewLifecycle { events += it }
        lifecycle.resume()
        lifecycle.stop()
        lifecycle.stop()
        lifecycle.resume()
        lifecycle.pause()
        lifecycle.stop()
        assertEquals(listOf(START, RESUME, PAUSE, STOP, START, RESUME, PAUSE, STOP), events)
    }

    @Test fun releasingAVisibleMapStopsRenderingAndPreventsLateCallbacksFromRestartingIt() {
        val events = mutableListOf<MapViewLifecycle.Event>()
        val lifecycle = MapViewLifecycle { events += it }
        lifecycle.resume()
        lifecycle.close()
        lifecycle.close()
        lifecycle.start()
        lifecycle.resume()
        assertEquals(listOf(START, RESUME, PAUSE, STOP), events)
    }
}
