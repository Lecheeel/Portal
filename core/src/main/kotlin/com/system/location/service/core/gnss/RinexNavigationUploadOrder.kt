package com.system.location.service.core.gnss

/** Native upload coordinate. Values from different systems must never be compared. */
internal object RinexNavigationUploadOrder {
    fun identity(record: RinexNavigationRecord): String = buildString {
        append(record.system.code); append(record.svid)
        // I/NAV and F/NAV have independent service/health histories in a mixed RINEX archive.
        if (record.system == RinexNavigationSystem.GALILEO) {
            append(':'); append(record.orbit.getOrNull(4)?.getOrNull(1)?.toString() ?: "unknown")
        }
    }

    fun seconds(record: RinexNavigationRecord): Double? = when (record.system) {
        RinexNavigationSystem.GPS, RinexNavigationSystem.GALILEO, RinexNavigationSystem.BEIDOU,
        RinexNavigationSystem.QZSS, RinexNavigationSystem.IRNSS -> {
            val week = record.orbit.getOrNull(4)?.getOrNull(2)
            val seconds = record.orbit.getOrNull(6)?.getOrNull(0)
            if (week != null && week >= 0 && week <= Int.MAX_VALUE && week == week.toInt().toDouble() &&
                seconds != null && seconds >= -604800 && seconds < 1209600) week * 604800 + seconds else null
        }
        RinexNavigationSystem.GLONASS, RinexNavigationSystem.SBAS -> record.epoch.let {
            it.date.toEpochDay() * 86400.0 + it.hour * 3600 + it.minute * 60 + it.second
        }
    }
}
