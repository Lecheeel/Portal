package com.system.location.service.core.gnss

/**
 * RINEX 3.05 Table A5 fractional time difference, excluding leap seconds. It is not an
 * arbitrary UTC offset. The producer supplies reference time/week in the specified native
 * scale (GAL references are GPS-week aligned in the file). No validity duration is encoded.
 */
data class RinexTimeSystemCorrection(
    val type: String,
    val a0Seconds: Double,
    val a1SecondsPerSecond: Double,
    val referenceSeconds: Int,
    val referenceWeek: Int,
    val source: String,
    val utcIdentifier: Int?,
) {
    init {
        require(type in TYPES && a0Seconds.isFinite() && a1SecondsPerSecond.isFinite())
        require(referenceWeek >= 0 && referenceSeconds in 0..604799)
        require(utcIdentifier == null || utcIdentifier in 0..7)
        require(source.length <= 5)
    }

    /**
     * GAGP = GST - GPS, evaluated at GST (Galileo OS SIS ICD 2.2 Eq.24).
     * Solve x = a0 + a1*(GPS+t_offset-t_ref), rather than evaluating the RHS at GPS.
     * Caller supplies an absolute GST applicability interval independently of the header.
     */
    fun gpsToGalileo(time: GpsTime, validFrom: GalileoTime, validUntil: GalileoTime): GalileoTime? {
        require(type == "GAGP")
        require(validUntil.secondsSince(validFrom) > 0)
        // This is the polynomial solver, not validation of a particular broadcast bit capacity.
        // Require an invertible, positively advancing target scale; the source adapter owns bounds.
        if (a1SecondsPerSecond >= 1) return null
        val delta = (time.week.toLong() - referenceWeek) * 604800.0 + time.secondsOfWeek - referenceSeconds
        val offset = (a0Seconds + a1SecondsPerSecond * delta) / (1 - a1SecondsPerSecond)
        val normalized = GnssWeekTime.normalize(time.week.toLong() - 1024, time.secondsOfWeek + offset) ?: return null
        val result = GalileoTime(normalized.first, normalized.second)
        return result.takeIf { it.secondsSince(validFrom) >= 0 && validUntil.secondsSince(it) >= 0 }
    }

    fun galileoToGps(time: GalileoTime, validFrom: GalileoTime, validUntil: GalileoTime): GpsTime? {
        require(type == "GAGP")
        require(validUntil.secondsSince(validFrom) > 0)
        if (time.secondsSince(validFrom) < 0 || validUntil.secondsSince(time) < 0 ||
            a1SecondsPerSecond >= 1) return null
        val delta = (time.week.toLong() + 1024 - referenceWeek) * 604800.0 + time.secondsOfWeek - referenceSeconds
        val offset = a0Seconds + a1SecondsPerSecond * delta
        val normalized = GnssWeekTime.normalize(time.week.toLong() + 1024, time.secondsOfWeek - offset) ?: return null
        return GpsTime(normalized.first, normalized.second)
    }

    companion object {
        private val TYPES = setOf("GPUT", "GLUT", "GAUT", "BDUT", "QZUT", "IRUT", "SBUT", "GLGP", "GAGP", "QZGP", "IRGP")
        private val NUMBER = Regex("[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[EeDd][+-]?[0-9]+)?")

        /** A damaged relevant correction cannot be ignored in favour of another healthy line. */
        internal fun galileoCorrection(headers: List<String>): RinexTimeSystemCorrection? {
            val corrections = headers.filter { it.take(4) in setOf("GAGP", "GPGA") }.map(::parse).distinct()
            require(corrections.size <= 1) { "Conflicting Galileo GAGP corrections" }
            return corrections.singleOrNull()
        }

        /** Header content columns 1–60, retained by the navigation reader. */
        fun parse(header: String): RinexTimeSystemCorrection {
            require(header.length in 50..60 && header[4] == ' ' && header[38] == ' ')
            val line = header.padEnd(60)
            fun decimal(start: Int, end: Int): Double {
                val token = line.substring(start, end).trim()
                require(NUMBER.matches(token)) { "Invalid RINEX time correction coefficient" }
                return token.replace('D', 'E').replace('d', 'e').toDouble().also { require(it.isFinite()) }
            }
            val type = line.substring(0, 4)
            // RINEX 3.02/3.03 GPGA was renamed GAGP in 3.04; its GAL-GPS sign is unchanged.
            val normalized = if (type == "GPGA") "GAGP" else type
            // Published examples/producers differ by one column in the optional right-aligned
            // source token. Coefficient/time columns stay strict; optional metadata is tokenized
            // only inside columns 51–60, never across the numeric fields.
            val suffix = line.substring(50).trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
            require(suffix.isEmpty() || suffix.size == 2)
            val source = suffix.firstOrNull().orEmpty()
            require(source.matches(Regex("[A-Za-z0-9]{0,5}")))
            val utc = suffix.getOrNull(1)?.toInt()
            return RinexTimeSystemCorrection(normalized, decimal(5, 22), decimal(22, 38),
                line.substring(39, 45).trim().toInt(), line.substring(45, 50).trim().toInt(),
                source, utc)
        }
    }
}
