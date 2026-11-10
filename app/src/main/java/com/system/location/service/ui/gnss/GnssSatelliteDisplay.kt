package com.system.location.service.ui.gnss

import com.system.location.service.android.widget.SatelliteData

/** One radar point and one count per spacecraft; zero-CN0 rows stay in raw capture only. */
internal fun displaySpacecraft(signals: List<SatelliteData>): List<SatelliteData> = signals
    .filter { it.snr > 0f && it.snr <= 63f && it.elevation in 0f..90f && it.azimuth in 0f..<360f }
    .groupBy { it.constellationType to it.prn }
    .values.map { candidates ->
        // Keep an entire measured row, prioritizing an in-use band. Stable tie-breaks
        // prevent callback array ordering from moving the radar point between bands.
        candidates.minWith(compareBy<SatelliteData>({ !it.usedInFix }, { -it.snr },
            { it.elevation }, { it.azimuth }, { !it.hasEphemeris }, { !it.hasAlmanac }))
    }
    .sortedWith(compareBy({ it.constellationType }, { it.prn }))
