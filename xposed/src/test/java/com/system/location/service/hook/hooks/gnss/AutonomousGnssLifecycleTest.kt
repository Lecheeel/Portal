package com.system.location.service.hook.hooks.gnss

import com.system.location.service.core.geo.Wgs84
import com.system.location.service.core.gnss.*
import com.system.location.service.core.location.LocationSample
import com.system.location.service.core.runtime.HookConfiguration
import com.system.location.service.hook.hooks.nmea.NmeaBridge
import moe.microbios.nmea.NMEA
import moe.microbios.nmea.NmeaValue
import org.junit.Assert.*
import org.junit.Test

class AutonomousGnssLifecycleTest {
    @Test fun twoDaysAcrossResourceExpiryKeepThreeRoutesAndAllOutputsCoherent() {
        val almanac = GpsAlmanacResource.decode(requireNotNull(javaClass.getResource("/gnss/gps-yuma.json")).readText())
        val start = almanac.referenceEpochMillis + 7 * 86_400_000L - 300_000
        val policy = GnssSimulationPolicy(mode = GnssSimulationMode.AUTONOMOUS,
            minimumPolicy = GnssMinimumPolicy.GUARANTEE_QUANTITY, virtualSeed = 80712)
        val configuration = HookConfiguration(gnssSimulation = policy, minimumSatellites = 64)
        val source = configuration.gnssFrameSource()!!
        val destinations = listOf(
            GnssFrameRoute.Global to Wgs84(31.0, 121.0),
            GnssFrameRoute.FixedPoint(Wgs84(-31.0, -59.0)) to Wgs84(-31.0, -59.0),
            GnssFrameRoute.Scenario("equator") to Wgs84(0.0, -78.0))
        var lastSequence = 0L
        var sawAlmanac = false
        var sawEntirelyVirtual = false
        repeat(577) { step -> // Five-minute snapshots over 48 hours, not a wall-time device soak.
            val at = start + step * 300_000L
            val elapsed = 1_000_000_000L + step * 300_000_000_000L
            val publications = destinations.map { (route, point) ->
                val sample = LocationSample(point, 10.0, 5f, 2f, 90f, at, elapsed)
                val publication = source.frame(sample, 64, route, at, 10)!!
                assertTrue(publication.sequence > lastSequence)
                lastSequence = publication.sequence
                assertEquals(point, publication.frame.observer)
                assertSame(publication, source.frame(sample.copy(timeMillis = at + 200,
                    elapsedNanos = elapsed + 200_000_000), 64, route, at + 200, 10))
                val frame = publication.frame
                assertEquals(64, frame.satellites.size)
                assertEquals(64, frame.satellites.map { it.constellation to it.svid }.distinct().size)
                assertTrue(frame.satellites.all { it.elevationDeg >= 0f && it.azimuthDeg in 0f..<360f })
                assertTrue(frame.satellites.filter { it.usedInFix }.all { it.hasEphemeris })
                assertEquals(GnssGeometry.solve(frame.satellites).dop, frame.geometry.dop)
                sawAlmanac = sawAlmanac || frame.orbitProvenance.values.any { it.kind == GnssOrbitSourceKind.ALMANAC }
                sawEntirelyVirtual = sawEntirelyVirtual || frame.orbitProvenance.values.all { it.kind == GnssOrbitSourceKind.VIRTUAL }
                val sentences = NmeaBridge.generatedSentences(publication)
                assertTrue(sentences.all { it.length <= 82 && NMEA.calculateNmeaChecksum(it) == it.substringAfter('*').trim() })
                val gga = sentences.single { it.substringBefore(',').endsWith("GGA") }.substringBefore('*').split(',')
                assertEquals(GnssLocationExtras.from(publication).satellites, gga[7].toInt())
                val gsv = sentences.filter { it.substringBefore(',').endsWith("GSV") }.map(NMEA::valueOf)
                val ids = gsv.flatMap { sentence -> (sentence.value as NmeaValue.GSV).satellites.map { sentence.talkerId to it.prn } }
                assertEquals(64, ids.size)
                assertEquals(ids.size, ids.distinct().size)
                val method = ReceiverGnssStatusAdapterTest.Callbacks::class.java.getDeclaredMethod("status5",
                    Int::class.javaPrimitiveType, IntArray::class.java, FloatArray::class.java,
                    FloatArray::class.java, FloatArray::class.java)
                val arrays = ReceiverGnssStatusAdapter.arguments(method, publication)
                assertEquals(64, arrays[0])
                assertEquals(frame.satellites.count { it.usedInFix }, (arrays[1] as IntArray).count { it and 4 != 0 })
                if (step % 144 == 0) {
                    // A fresh engine reconstructs the same sky from durable resource/policy state.
                    val restarted = GnssBundledOrbitResources.model(policy, GnssOutputLimits(128, 36, 32), 604800.0)
                        .frame(GnssSkyRequest(at, point, 10.0), 64)!!
                    assertEquals(frame, restarted)
                }
                publication
            }
            assertEquals(destinations.size, publications.map { it.sequence }.distinct().size)
            assertNotEquals(publications[0].frame.satellites, publications[1].frame.satellites)
        }
        assertTrue("Resource expiry was not exercised", sawAlmanac && sawEntirelyVirtual)
    }
}
