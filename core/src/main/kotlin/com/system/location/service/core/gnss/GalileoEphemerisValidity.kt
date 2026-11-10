package com.system.location.service.core.gnss

data class GalileoValidityInterval(val from: GalileoTime, val until: GalileoTime) {
    init { require(until.secondsSince(from) > 0) }
    fun contains(time: GalileoTime) = time.secondsSince(from) >= 0 && until.secondsSince(time) >= 0
}

/**
 * Galileo OS SDD 1.3 p.17 and Annex C.4.4.1, footnote 22: 0 <= t-toe <= 4 hours.
 * This is an upper limit on age of ephemeris, NOT four hours around toe, an IOD lifetime,
 * a GGTO validity duration, or proof that health/DVS/service state remains current.
 * Full resolved GST epochs are used; modulo-week arithmetic must not revive expired data.
 * https://www.gsc-europa.eu/sites/default/files/sites/all/files/Galileo-OS-SDD_in_force.pdf
 */
object GalileoEphemerisValidity {
    const val MAXIMUM_AGE_SECONDS = 14400.0

    /** Additional independently established limits may only shorten the standard age interval. */
    fun resolve(toe: GalileoTime, suppliedFrom: GalileoTime? = null,
        suppliedUntil: GalileoTime? = null): GalileoValidityInterval? {
        if ((suppliedFrom == null) != (suppliedUntil == null)) return null
        val expiration = runCatching { toe.plusSeconds(MAXIMUM_AGE_SECONDS) }.getOrNull() ?: return null
        val from = if (suppliedFrom != null && suppliedFrom.secondsSince(toe) > 0) suppliedFrom else toe
        val until = if (suppliedUntil != null && suppliedUntil.secondsSince(expiration) < 0) suppliedUntil else expiration
        return if (until.secondsSince(from) > 0) GalileoValidityInterval(from, until) else null
    }
}
