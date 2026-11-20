package com.android.location.fused

import android.location.Location

/** Package classes become visible through the package loader after system_server has started. */
class FusedLocationProvider {
    fun chooseBestLocation(first: Location?, second: Location?): Location? = first ?: second

    class ChildLocationListener {
        fun onLocationChanged(location: Location) = Unit
    }
}
