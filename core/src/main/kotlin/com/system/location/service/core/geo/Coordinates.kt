package com.system.location.service.core.geo

import kotlinx.serialization.Serializable

private fun validate(latitude: Double, longitude: Double) {
    require(latitude in -90.0..90.0 && longitude in -180.0..180.0) { "Invalid coordinate" }
}

@Serializable data class Wgs84(val latitude: Double, val longitude: Double) {
    init { validate(latitude, longitude) }
}
@Serializable data class Gcj02(val latitude: Double, val longitude: Double) {
    init { validate(latitude, longitude) }
}
@Serializable data class Bd09(val latitude: Double, val longitude: Double) {
    init { validate(latitude, longitude) }
}
