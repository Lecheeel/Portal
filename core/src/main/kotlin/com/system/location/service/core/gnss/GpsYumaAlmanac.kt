package com.system.location.service.core.gnss

import kotlinx.serialization.Serializable
import kotlin.math.PI

/** Yuma supplies absolute inclination in radians, unlike LNAV's transmitted delta-i. */
@Serializable
data class GpsYumaAlmanac(
    val svid: Int,
    val health: Int,
    val referenceTime: GpsTime,
    val eccentricity: Double,
    val inclinationRad: Double,
    val ascendingNodeRateRadPerSecond: Double,
    val sqrtSemiMajorAxis: Double,
    val ascendingNodeRad: Double,
    val argumentOfPerigeeRad: Double,
    val meanAnomalyRad: Double,
    val af0Seconds: Double,
    val af1SecondsPerSecond: Double,
) {
    init {
        require(svid in 1..32 && health in 0..255)
        require(eccentricity in 0.0..0.03 && inclinationRad in 0.0..PI)
        require(sqrtSemiMajorAxis in 2530.0..8192.0)
        require(listOf(ascendingNodeRateRadPerSecond, ascendingNodeRad, argumentOfPerigeeRad,
            meanAnomalyRad, af0Seconds, af1SecondsPerSecond).all(Double::isFinite))
    }
}

/**
 * Strict transactional import: a partial/corrupt update must not replace a complete resource.
 * The caller supplies an independently known approximate full week (e.g. archive date).
 * Truncated weeks are resolved once at import; they are never re-resolved at simulation time.
 * Format: https://celestrak.org/GPS/almanac/Yuma/definition.php
 */
object GpsYumaParser {
    const val MAX_BYTES = 262_144
    private val fields = setOf("ID", "Health", "Eccentricity", "Time of Applicability(s)",
        "Orbital Inclination(rad)", "Rate of Right Ascen(r/s)", "SQRT(A)  (m 1/2)",
        "Right Ascen at Week(rad)", "Argument of Perigee(rad)", "Mean Anom(rad)",
        "Af0(s)", "Af1(s/s)", "week")

    fun parse(text: String, approximateFullWeek: Int): List<GpsYumaAlmanac> {
        require(approximateFullWeek >= 0)
        require(text.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "Yuma resource too large" }
        val records = mutableListOf<GpsYumaAlmanac>()
        val record = linkedMapOf<String, String>()
        fun finish() {
            if (record.isEmpty()) return
            require(record.keys == fields) { "Incomplete Yuma record" }
            fun number(key: String) = record.getValue(key).replace('D', 'E').replace('d', 'e').toDouble()
            val rawWeek = record.getValue("week").toInt()
            require(rawWeek >= 0)
            val fullWeek = if (rawWeek >= 1024) rawWeek.toLong() else {
                val cycles = Math.floorDiv(approximateFullWeek.toLong() - rawWeek + 512, 1024)
                rawWeek + cycles * 1024
            }
            require(fullWeek in 0..Int.MAX_VALUE.toLong()) { "Unresolvable Yuma week" }
            // An exactly half-cycle anchor is ambiguous; do not silently select an era.
            require(kotlin.math.abs(fullWeek - approximateFullWeek) < 512) { "Ambiguous Yuma week anchor" }
            records += GpsYumaAlmanac(record.getValue("ID").toInt(), record.getValue("Health").toInt(),
                GpsTime(fullWeek.toInt(), number("Time of Applicability(s)")), number("Eccentricity"),
                number("Orbital Inclination(rad)"), number("Rate of Right Ascen(r/s)"),
                number("SQRT(A)  (m 1/2)"), number("Right Ascen at Week(rad)"),
                number("Argument of Perigee(rad)"), number("Mean Anom(rad)"), number("Af0(s)"), number("Af1(s/s)"))
            require(records.size <= 32) { "Too many GPS almanac records" }
            record.clear()
        }
        for (rawLine in text.lineSequence()) {
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("********")) {
                finish()
                continue
            }
            val split = line.indexOf(':')
            require(split > 0) { "Invalid Yuma line" }
            val key = line.substring(0, split).trim()
            require(key in fields && key !in record) { "Unknown or duplicate Yuma field: $key" }
            record[key] = line.substring(split + 1).trim()
        }
        finish()
        require(records.isNotEmpty()) { "Empty Yuma resource" }
        require(records.map { it.svid }.distinct().size == records.size) { "Duplicate GPS almanac identity" }
        // A downloaded set must have one reference epoch; never assemble mismatched uploads.
        require(records.map { it.referenceTime }.distinct().size == 1) { "Mixed Yuma reference epochs" }
        return records.toList()
    }
}
