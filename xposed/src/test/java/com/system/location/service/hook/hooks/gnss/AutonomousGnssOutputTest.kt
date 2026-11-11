package com.system.location.service.hook.hooks.gnss

import com.system.location.service.core.geo.Wgs84
import com.system.location.service.core.gnss.*
import com.system.location.service.hook.hooks.nmea.NmeaBridge
import moe.microbios.nmea.NMEA
import moe.microbios.nmea.NmeaValue
import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class AutonomousGnssOutputTest {
    private val limits = GnssOutputLimits(128, 36, 32)

    @Test fun quantity128SurvivesLegacyStatusArraysAndNmeaWithoutTruncation() {
        for (at in listOf("2026-11-11T04:00:00Z", "2028-01-01T00:00:00Z")) {
            val model = GnssBundledOrbitResources.model(GnssSimulationPolicy(
                minimumPolicy = GnssMinimumPolicy.GUARANTEE_QUANTITY), limits, 604800.0)
            val frame = model.frame(GnssSkyRequest(Instant.parse(at).toEpochMilli(), Wgs84(31.0, 121.0), 10.0), 128)!!
            assertNotNull(frame.geometry.dop)
            val publication = PublishedGnssFrame(1, frame, 2.0, 90.0)
            for (method in ReceiverGnssStatusAdapterTest.Callbacks::class.java.declaredMethods.filter {
                it.name.startsWith("status") }) {
                val args = ReceiverGnssStatusAdapter.arguments(method, publication)
                assertEquals(128, args[0])
                val flags = args[1] as IntArray
                assertEquals(128, flags.size)
                assertEquals(128, flags.map { it ushr 8 }.distinct().size)
                frame.satellites.forEachIndexed { index, satellite ->
                    assertEquals(satellite.svid, flags[index] ushr 12)
                    assertEquals(satellite.usedInFix, flags[index] and 4 != 0)
                    assertEquals(satellite.hasEphemeris, flags[index] and 1 != 0)
                }
                for (array in args.drop(2)) assertEquals(128, (array as FloatArray).size)
            }
            val lines = NmeaBridge.generatedSentences(publication)
            for (line in lines) {
                assertTrue("Oversized NMEA: $line", line.length <= 82)
                assertEquals(NMEA.calculateNmeaChecksum(line), line.substringAfter('*').trim())
            }
            val gsv = lines.filter { it.substringBefore(',').endsWith("GSV") }.map { NMEA.valueOf(it) }
            assertEquals(128, gsv.sumOf { (it.value as NmeaValue.GSV).satellites.size })
            val reconstructed = gsv.flatMap { sentence -> (sentence.value as NmeaValue.GSV).satellites.map {
                sentence.talkerId to it.prn
            } }.toSet()
            assertEquals(128, reconstructed.size)
            for ((_, sentences) in gsv.groupBy { it.talkerId }) {
                val fields = sentences.map { it.value as NmeaValue.GSV }
                assertTrue(fields.size <= 9)
                assertEquals((1..fields.size).toList(), fields.map { it.messageNumber })
                assertTrue(fields.all { it.totalMessages == fields.size })
                assertTrue(fields.all { it.totalSatellitesInView == fields.sumOf { item -> item.satellites.size } })
            }
            val gga = lines.single { it.substringBefore(',').endsWith("GGA") }.substringBefore('*').split(',')
            assertEquals(frame.satellites.count { it.usedInFix }, gga[7].toInt())
            assertTrue(gga[7].length <= 2)
            val gsaIds = lines.filter { it.substringBefore(',').endsWith("GSA") }.flatMap { line ->
                val fields = line.substringBefore('*').split(',')
                fields.subList(3, 15).filter { it.isNotEmpty() }.map { fields[18].toInt() to it.toInt() }
            }.toSet()
            assertEquals(frame.satellites.count { it.usedInFix }, gsaIds.size)
        }
    }
}
