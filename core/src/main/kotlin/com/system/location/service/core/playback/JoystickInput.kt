package com.system.location.service.core.playback

import kotlin.math.*

object JoystickInput {
    data class Motion(val bearing: Double, val strength: Double)
    fun evaluate(x: Double, y: Double, radius: Double): Motion {
        require(x.isFinite() && y.isFinite() && radius.isFinite() && radius > 0)
        val fraction = (hypot(x, y) / radius).coerceIn(0.0, 1.0)
        // A small dead zone prevents drift; the response gives more room for fine movement.
        val strength = ((fraction - 0.08) / 0.92).coerceIn(0.0, 1.0).pow(1.5)
        return Motion(if (fraction == 0.0) 0.0 else (Math.toDegrees(atan2(x, -y)) + 360) % 360, strength)
    }
}
