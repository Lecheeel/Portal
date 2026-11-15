package com.system.location.service.core

import com.system.location.service.core.geo.Wgs84
import com.system.location.service.core.gnss.*
import java.time.Instant
import kotlin.math.hypot
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GnssIndependentPositionSolverTest {
    private data class Range(val satellite: EcefPosition, val meters: Double)

    @Test
    fun rinexGpsGeometricRangesRecoverTargetAndReceiverClockOffset() {
        val resource = requireNotNull(javaClass.getResource("/gnss/bkg-2026315-four-system.rnx")).readText()
        val metadata = Json.parseToJsonElement(
            requireNotNull(javaClass.getResource("/gnss/bkg-2026315-four-system-source.json")).readText(),
        ).jsonObject
        val epochMillis = Instant.parse(metadata.getValue("evaluationUtc").jsonPrimitive.content).toEpochMilli()
        val utc = GnssUtcTimeScale.parseIers(
            requireNotNull(javaClass.getResource("/gnss/leap-seconds.list")).readText(),
        )
        val receptionTime = requireNotNull(utc.gpsAtUtcMillis(epochMillis).time)
        val target = Wgs84(31.0, 121.0)
        val targetEcef = GnssObserverGeometry.ecef(target, 18.0)
        val clockOffsetMeters = 75_000.0
        val ephemerides = RinexNavigationReader.read(resource).records
            .filter { it.system == RinexNavigationSystem.GPS }
            .mapNotNull { runCatching { RinexGpsImporter.decode(it).ephemeris }.getOrNull() }
        val observations = ephemerides.mapNotNull { ephemeris ->
            val geometry = GpsReceptionGeometry.observe(ephemeris, receptionTime, target, 18.0).observation
                ?: return@mapNotNull null
            if (!geometry.lineOfSight.aboveHorizon) return@mapNotNull null
            Range(geometry.satelliteInReceptionEcef,
                geometry.lineOfSight.rangeMeters + clockOffsetMeters)
        }

        assertTrue("Need at least four visible GPS records; found ${observations.size}", observations.size >= 4)
        val solved = solve(observations, targetEcef.copy(
            xMeters = targetEcef.xMeters + 1_000.0,
            yMeters = targetEcef.yMeters - 700.0,
            zMeters = targetEcef.zMeters + 400.0,
        ))

        assertEquals(targetEcef.xMeters, solved.first.xMeters, 0.02)
        assertEquals(targetEcef.yMeters, solved.first.yMeters, 0.02)
        assertEquals(targetEcef.zMeters, solved.first.zMeters, 0.02)
        assertEquals(clockOffsetMeters, solved.second, 0.02)
    }

    @Test
    fun synthesizedGpsL1ClockAndMeasurementsIndependentlyRecoverTarget() {
        val resource = requireNotNull(javaClass.getResource("/gnss/bkg-2026315-four-system.rnx")).readText()
        val metadata = Json.parseToJsonElement(
            requireNotNull(javaClass.getResource("/gnss/bkg-2026315-four-system-source.json")).readText(),
        ).jsonObject
        val epochMillis = Instant.parse(metadata.getValue("evaluationUtc").jsonPrimitive.content).toEpochMilli()
        val utc = GnssUtcTimeScale.parseIers(
            requireNotNull(javaClass.getResource("/gnss/leap-seconds.list")).readText(),
        )
        val referenceTime = requireNotNull(utc.gpsAtUtcMillis(epochMillis).time)
        val clockOffsetMeters = 75_000.0
        val ephemerides = RinexNavigationReader.read(resource).records
            .filter { it.system == RinexNavigationSystem.GPS }
            .mapNotNull { runCatching { RinexGpsImporter.decode(it).ephemeris }.getOrNull() }
            .filter { it.health == 0 && referenceTime.secondsSince(it.validFrom) >= 0 &&
                it.validUntil.secondsSince(referenceTime) >= 0 }
            .distinctBy { it.svid }
        val (receptionTime, target) = findTargetContext(referenceTime, ephemerides)
        val capturedAtMillis = epochMillis + (receptionTime.secondsSince(referenceTime) * 1_000).toLong()
        val targetEcef = GnssObserverGeometry.ecef(target, 18.0)
        val clockBiasSeconds = clockOffsetMeters / GpsReceptionGeometry.SPEED_OF_LIGHT_METERS_PER_SECOND
        val synthesizer = GpsL1RawMeasurementSynthesizer(
            ephemerides,
            receiverAt = {
                GpsL1ReceiverState(target, 18.0, clockBiasSeconds, 0.0, 0.0, 0.0,
                    clockUncertaintyNanos = 0.0, clockBiasUncertaintyNanos = 0.0,
                    clockDriftUncertaintyNanosPerSecond = 0.0, elapsedRealtimeUncertaintyNanos = 0.0)
            },
            qualityAt = { _, _ -> GpsL1SignalQuality(45.0, 25L, 0.2, 0.03) },
        )
        val batch = requireNotNull(synthesizer.synthesize(receptionTime, 100_000_000_000L, capturedAtMillis, 0))
        val receiverTime = requireNotNull(GpsReceiverClock.decode(batch).epoch).time
        val observations = batch.measurements.mapNotNull { measurement ->
            var transmitTime = GpsTime(receiverTime.week, measurement.receivedSvTimeNanos / 1e9)
            if (receiverTime.secondsSince(transmitTime) < 0) transmitTime = transmitTime.plusSeconds(-GpsTime.SECONDS_PER_WEEK)
            val ephemeris = ephemerides.single { it.svid == measurement.svid }
            val geometry = GpsReceptionGeometry.observe(ephemeris, receptionTime, target, 18.0).observation
                ?: return@mapNotNull null
            val rawPseudorange = receiverTime.secondsSince(transmitTime) *
                GpsReceptionGeometry.SPEED_OF_LIGHT_METERS_PER_SECOND
            Range(geometry.satelliteInReceptionEcef,
                rawPseudorange + GpsReceptionGeometry.SPEED_OF_LIGHT_METERS_PER_SECOND *
                    geometry.l1CaClockBiasSeconds)
        }
        assertTrue("Need at least four generated measurements; found ${observations.size}", observations.size >= 4)
        val solved = solve(observations, targetEcef.copy(
            xMeters = targetEcef.xMeters + 1_000.0,
            yMeters = targetEcef.yMeters - 700.0,
            zMeters = targetEcef.zMeters + 400.0,
        ))

        assertEquals(targetEcef.xMeters, solved.first.xMeters, 2.0)
        assertEquals(targetEcef.yMeters, solved.first.yMeters, 2.0)
        assertEquals(targetEcef.zMeters, solved.first.zMeters, 2.0)
        assertEquals(clockOffsetMeters, solved.second, 2.0)
    }

    private fun findTargetContext(reference: GpsTime, ephemerides: List<GpsLnavEphemeris>): Pair<GpsTime, Wgs84> {
        val points = (-80..80 step 10).flatMap { latitude ->
            (-180..160 step 20).map { longitude -> Wgs84(latitude.toDouble(), longitude.toDouble()) }
        }
        var best: Triple<GpsTime, Wgs84, Double>? = null
        for (offset in -3_600..3_600 step 300) {
            val time = reference.plusSeconds(offset.toDouble())
            for (point in points) {
                val satellites = ephemerides.mapNotNull { ephemeris ->
                    val line = GpsReceptionGeometry.observe(ephemeris, time, point, 18.0).observation?.lineOfSight
                        ?.takeIf { it.aboveHorizon && it.azimuthDegrees != null } ?: return@mapNotNull null
                    GnssSatellite(Constellation.GPS, ephemeris.svid, line.elevationDegrees.toFloat(),
                        line.azimuthDegrees!!.toFloat(), 45f, true, true, false,
                        GpsL1RawMeasurementSynthesizer.GPS_L1_CA_HZ.toFloat())
                }
                if (satellites.size < GpsL1RawMeasurementSynthesizer.MIN_MEASUREMENTS) continue
                val condition = GnssGeometry.solve(satellites).conditionNumber ?: continue
                if (best == null || condition < best.third) best = Triple(time, point, condition)
                if (condition < 20.0) return time to point
            }
        }
        val selected = requireNotNull(best) { "Bundled RINEX GPS subset has no four-satellite target geometry" }
        require(selected.third < 1e4) { "Bundled GPS geometry is too ill-conditioned: ${selected.third}" }
        return selected.first to selected.second
    }

    private fun solve(observations: List<Range>, initial: EcefPosition): Pair<EcefPosition, Double> {
        var receiver = initial
        var clockMeters = 0.0
        var lastStep = DoubleArray(4)
        repeat(100) {
            val matrix = Array(observations.size) { rowIndex ->
                val observation = observations[rowIndex]
                val dx = receiver.xMeters - observation.satellite.xMeters
                val dy = receiver.yMeters - observation.satellite.yMeters
                val dz = receiver.zMeters - observation.satellite.zMeters
                val distance = hypot(hypot(dx, dy), dz)
                doubleArrayOf(dx / distance, dy / distance, dz / distance, 1.0)
            }
            val residuals = DoubleArray(observations.size) { row ->
                observations[row].meters - hypot(hypot(
                    receiver.xMeters - observations[row].satellite.xMeters,
                    receiver.yMeters - observations[row].satellite.yMeters,
                ), receiver.zMeters - observations[row].satellite.zMeters) - clockMeters
            }
            val step = leastSquaresQr(matrix, residuals)
            lastStep = step
            receiver = EcefPosition(receiver.xMeters + step[0], receiver.yMeters + step[1],
                receiver.zMeters + step[2])
            clockMeters += step[3]
            if (step.take(3).maxOf { kotlin.math.abs(it) } < 1e-5) return receiver to clockMeters
        }
        error("Independent least-squares solver did not converge: position=$receiver clock=$clockMeters step=${lastStep.toList()}")
    }

    private fun leastSquaresQr(matrix: Array<DoubleArray>, values: DoubleArray): DoubleArray {
        val a = Array(matrix.size) { row -> matrix[row].copyOf() }
        val b = values.copyOf()
        for (column in 0..3) {
            val norm = kotlin.math.sqrt((column until a.size).sumOf { a[it][column] * a[it][column] })
            require(norm > 1e-10) { "rank-deficient geometry" }
            val reflector = DoubleArray(a.size - column) { a[column + it][column] }
            reflector[0] += if (reflector[0] >= 0) norm else -norm
            val normSquared = reflector.sumOf { it * it }
            for (target in column..3) {
                val projection = 2 * reflector.indices.sumOf { reflector[it] * a[column + it][target] } / normSquared
                for (row in reflector.indices) a[column + row][target] -= projection * reflector[row]
            }
            val projection = 2 * reflector.indices.sumOf { reflector[it] * b[column + it] } / normSquared
            for (row in reflector.indices) b[column + row] -= projection * reflector[row]
        }
        val solution = DoubleArray(4)
        for (row in 3 downTo 0) {
            solution[row] = (b[row] - (row + 1..3).sumOf { a[row][it] * solution[it] }) / a[row][row]
        }
        return solution
    }
}
