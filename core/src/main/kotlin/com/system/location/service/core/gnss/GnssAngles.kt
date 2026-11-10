package com.system.location.service.core.gnss

/** Preserve the circular [0, 360) domain when Float rounding reaches the north endpoint. */
internal fun Double.toGnssAzimuthFloat(): Float {
    require(isFinite() && this >= 0.0 && this < 360.0)
    return toFloat() % 360f
}
