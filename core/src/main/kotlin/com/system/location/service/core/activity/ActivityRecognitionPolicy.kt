package com.system.location.service.core.activity

import com.system.location.service.core.sensors.ActivityKind
import com.system.location.service.core.sensors.MotionMode

/**
 * P7-03: deterministic activity-recognition distribution for the current motion mode.
 *
 * This is the policy layer only; the GMS/AOSP adapter is intentionally absent until a device
 * validates the callback surface. Probabilities always sum to 100.
 */
object ActivityRecognitionPolicy {
    fun probabilities(mode: MotionMode): Map<ActivityKind, Int> = when (mode) {
        MotionMode.STILL -> mapOf(
            ActivityKind.STILL to 90,
            ActivityKind.UNKNOWN to 10,
        )
        MotionMode.WALK -> mapOf(
            ActivityKind.WALKING to 80,
            ActivityKind.STILL to 10,
            ActivityKind.RUNNING to 10,
        )
        MotionMode.RUN -> mapOf(
            ActivityKind.RUNNING to 80,
            ActivityKind.WALKING to 15,
            ActivityKind.IN_VEHICLE to 5,
        )
        MotionMode.VEHICLE -> mapOf(
            ActivityKind.IN_VEHICLE to 80,
            ActivityKind.ON_BICYCLE to 5,
            ActivityKind.RUNNING to 5,
            ActivityKind.UNKNOWN to 10,
        )
    }

    fun mostProbable(mode: MotionMode): ActivityKind =
        probabilities(mode).maxByOrNull { it.value }?.key ?: ActivityKind.UNKNOWN
}
