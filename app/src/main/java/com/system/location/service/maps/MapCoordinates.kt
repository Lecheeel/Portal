package com.system.location.service.maps

import com.system.location.service.core.geo.*

val Pair<Double, Double>.mapPoint: Gcj02
    get() = CoordinateTransform.toGcj02(Wgs84(first, second))
val Gcj02.storedPoint: Pair<Double, Double>
    get() = CoordinateTransform.toWgs84(this).let { it.latitude to it.longitude }
