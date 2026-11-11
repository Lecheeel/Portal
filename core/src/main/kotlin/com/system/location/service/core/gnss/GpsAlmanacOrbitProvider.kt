package com.system.location.service.core.gnss

/** A complete immutable Yuma upload, independent of local receiver capture or boot identity. */
class GpsAlmanacOrbitProvider(
    records: List<GpsYumaAlmanac>,
    private val provenance: GnssOrbitProvenance,
    private val utc: GnssUtcTimeScale,
    private val maximumEpochDistanceSeconds: Double,
) : GnssOrbitProvider {
    private val records = records.toList()
    override val kind = GnssOrbitSourceKind.ALMANAC

    init {
        require(records.isNotEmpty() && records.size <= 32)
        require(records.map { it.svid }.distinct().size == records.size)
        require(records.map { it.referenceTime }.distinct().size == 1)
        require(provenance.kind == kind)
        require(maximumEpochDistanceSeconds.isFinite() && maximumEpochDistanceSeconds >= 0)
        // Metadata and orbital epoch must describe the same resource, including resolved era.
        val reference = utc.gpsAtUtcMillis(provenance.referenceEpochMillis).time
        require(reference != null && kotlin.math.abs(reference.secondsSince(records.first().referenceTime)) <= 0.001)
    }

    override fun positions(request: GnssSkyRequest): GnssOrbitBatch {
        val conversion = utc.gpsAtUtcMillis(request.atMillis)
        val time = conversion.time ?: return GnssOrbitBatch(emptyList(), listOf(requireNotNull(conversion.reason)))
        val diagnostics = mutableListOf<String>()
        val positions = records.mapNotNull { record ->
            val result = GpsAlmanacPropagator.position(record, time, maximumEpochDistanceSeconds)
            val position = result.position
            if (position == null) {
                diagnostics += "GPS:${record.svid}: ${result.reason}"
                null
            } else GnssOrbitPosition(Constellation.GPS, record.svid, position, provenance,
                hasEphemeris = false, hasAlmanac = true)
        }
        return GnssOrbitBatch(positions, diagnostics)
    }
}
