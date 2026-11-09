package com.system.location.service.ui.viewmodel

import android.app.Notification
import androidx.lifecycle.ViewModel

class AMapViewModel: ViewModel() {
    var isExists = false

    /**
     * Current location
     * WGS84
     */
    var currentLocation: Pair<Double, Double>? = null
    // First trusted real fix for this app session. Never assigned from simulation samples.
    var originalLocation: Pair<Double, Double>? = null
        private set

    fun rememberOriginalLocation(point: Pair<Double, Double>, isMock: Boolean, simulationActive: Boolean): Boolean {
        if (originalLocation != null || isMock || simulationActive ||
            point.first !in -90.0..90.0 || point.second !in -180.0..180.0) return false
        originalLocation = point
        return true
    }

    var markName: String? = null

    /**
     * Marked location
     * WGS84
     * first => latitude
     * second => longitude
     */
    var markedLoc: Pair<Double, Double>? = null
    var showDetailView = false

    /* Notification */
    var mNotification: Notification? = null

    // Kept for the legacy AMap configuration extension; map views own SDK resources.
    enum class Perspective { NORMAL, FOLLOWING }
}
