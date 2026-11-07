package com.system.location.service.core

import com.system.location.service.core.activity.ActivityRecognitionPolicy
import com.system.location.service.core.sensors.ActivityKind
import com.system.location.service.core.sensors.MotionMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ActivityRecognitionPolicyTest {
    @Test fun probabilitiesSumToHundred() {
        MotionMode.entries.forEach { mode ->
            assertEquals(100, ActivityRecognitionPolicy.probabilities(mode).values.sum())
        }
    }

    @Test fun dominantActivityMatchesMotion() {
        assertEquals(ActivityKind.STILL, ActivityRecognitionPolicy.mostProbable(MotionMode.STILL))
        assertEquals(ActivityKind.WALKING, ActivityRecognitionPolicy.mostProbable(MotionMode.WALK))
        assertEquals(ActivityKind.RUNNING, ActivityRecognitionPolicy.mostProbable(MotionMode.RUN))
        assertEquals(ActivityKind.IN_VEHICLE, ActivityRecognitionPolicy.mostProbable(MotionMode.VEHICLE))
    }

    @Test fun eachModeHasAtMostOneDominant() {
        MotionMode.entries.forEach { mode ->
            val max = ActivityRecognitionPolicy.probabilities(mode).values.max()
            assertTrue(ActivityRecognitionPolicy.probabilities(mode).values.count { it == max } == 1)
        }
    }
}
