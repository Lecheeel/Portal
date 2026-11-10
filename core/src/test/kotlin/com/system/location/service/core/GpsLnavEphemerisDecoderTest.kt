package com.system.location.service.core

import com.system.location.service.core.gnss.*
import java.util.Base64
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class GpsLnavEphemerisDecoderTest {
    private val fixture = Json.parseToJsonElement(javaClass.getResource("/gnss/gps-lnav-words-reference.json")!!
        .readText()).jsonObject
    private val words = fixture.getValue("subframes").jsonArray.map { it.jsonObject }
    private val references = fixture.getValue("decodedEphemerides").jsonArray.map { it.jsonObject }
    private fun JsonObject.n(name: String) = getValue(name).jsonPrimitive.double
    private fun frames(index: Int = 0) = words.drop(index * 3).take(3).map { f ->
        val payload = f.getValue("payloadHex").jsonPrimitive.content.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        GpsLnavWords.decode(GnssNavigationObservation(1000 + f.n("id").toLong() * 6000,
            0x0101, f.n("svid").toInt(), 1, -1, f.n("id").toInt(),
            Base64.getEncoder().encodeToString(payload))).subframe!!
    }
    private fun reference(frames: List<GpsLnavSubframe>) = GpsTime(1316, frames.first().nextTowSeconds.toDouble())
    private fun altered(frame: GpsLnavSubframe, start: Int = 0, length: Int = 1,
        value: Long = frame.unsigned(start, length), svid: Int = frame.svid,
        nextTow: Int = frame.nextTowSeconds, capturedAt: Long = frame.capturedAtMillis,
        alert: Boolean = frame.alert): GpsLnavSubframe {
        val bytes = ByteArray(30) { frame.unsigned(it * 8, 8).toByte() }
        repeat(length) { i ->
            val bit = start + i
            val mask = 1 shl (7 - bit % 8)
            bytes[bit / 8] = ((bytes[bit / 8].toInt() and mask.inv()) or
                (if (value ushr (length - i - 1) and 1 == 1L) mask else 0)).toByte()
        }
        return GpsLnavSubframe(svid, capturedAt, frame.id, nextTow, alert, bytes)
    }

    @Test fun decodesAllParametersAgainstIndependentPinnedRtkLibCDecoder() {
        references.forEachIndexed { index, r ->
            val f = frames(index)
            val result = GpsLnavEphemerisDecoder.decode(f.reversed(), reference(f))
            assertNotNull(result.reason, result.decoded)
            val e = result.decoded!!.ephemeris
            assertEquals(r.n("week").toInt(), e.toe.week)
            assertEquals(r.n("toe"), e.toe.secondsOfWeek, 0.0)
            assertEquals(e.toe, e.toc)
            assertEquals(r.n("svid").toInt(), e.svid)
            assertEquals(r.n("iodc").toInt(), e.iodc)
            assertEquals(r.n("iode").toInt(), e.iode2)
            assertEquals(e.iode2, e.iode3)
            assertEquals(r.n("health").toInt(), e.health)
            assertEquals(7200.0, e.toe.secondsSince(e.validFrom), 0.0)
            assertEquals(7200.0, e.validUntil.secondsSince(e.toe), 0.0)
            val values = mapOf("sqrtA" to e.sqrtSemiMajorAxis, "e" to e.eccentricity,
                "i0" to e.inclinationRad, "omega0" to e.ascendingNodeRad,
                "omega" to e.argumentOfPerigeeRad, "m0" to e.meanAnomalyRad,
                "deltaN" to e.deltaMeanMotionRadPerSecond, "omegaDot" to e.ascendingNodeRateRadPerSecond,
                "iDot" to e.inclinationRateRadPerSecond, "crc" to e.crcMeters, "crs" to e.crsMeters,
                "cuc" to e.cucRad, "cus" to e.cusRad, "cic" to e.cicRad, "cis" to e.cisRad,
                "af0" to e.af0Seconds, "af1" to e.af1SecondsPerSecond,
                "af2" to e.af2SecondsPerSecondSquared, "tgd" to e.tgdSeconds)
            val semicircleFields = setOf("i0", "omega0", "omega", "m0", "deltaN", "omegaDot", "iDot")
            values.forEach { (name, value) ->
                // RTKLIB uses SC2RAD=3.1415926535898; production uses full Double PI.
                val tolerance = if (name in semicircleFields) kotlin.math.abs(r.n(name)) * 3e-15
                    else Math.ulp(r.n(name)) * 4
                assertEquals(name, r.n(name), value, tolerance)
            }
            assertNotNull(GpsBroadcastPropagator.propagate(e, e.toe).state)
        }
    }

    @Test fun refusesPartialMixedStaleAndDifferentTransmissionFramesEvenWithSameIod() {
        val f = frames()
        fun rejected(candidate: List<GpsLnavSubframe>, ref: GpsTime = reference(f)) {
            val result = GpsLnavEphemerisDecoder.decode(candidate, ref)
            assertNotNull(result.reason)
            assertNull(result.decoded)
        }
        rejected(f.take(2))
        rejected(listOf(f[0], f[1], f[1]))
        rejected(listOf(f[0], f[1], altered(f[2], svid = 2)))
        rejected(listOf(f[0], f[1], altered(f[2], nextTow = f[2].nextTowSeconds + 30)))
        rejected(listOf(f[0], f[1], altered(f[2], capturedAt = 100_000)))
        rejected(f, GpsTime(1317, reference(f).secondsOfWeek))
        rejected(f, reference(f).plusSeconds(121.0))
        rejected(listOf(f[0], altered(f[1], 48, 8, 141), f[2]))
        rejected(listOf(f[0], f[1], altered(f[2], 216, 8, 141)))
    }

    @Test fun rejectsAlertUnhealthyReservedUnavailableOrInvalidOrbitData() {
        val f = frames()
        val malformed = listOf(
            listOf(altered(f[0], alert = true), f[1], f[2]),
            listOf(altered(f[0], 64, 6, 1), f[1], f[2]),
            listOf(altered(f[0], 60, 4, 15), f[1], f[2]),
            listOf(altered(f[0], 160, 8, 128), f[1], f[2]),
            listOf(altered(f[0], 176, 16, 0), f[1], f[2]),
            listOf(f[0], altered(f[1], 136, 32, 0xFFFFFFFF), f[2]),
            listOf(f[0], altered(f[1], 184, 32, 0), f[2]),
            listOf(f[0], altered(f[1], 216, 16, 65535), f[2]),
        )
        malformed.forEach { assertNull(GpsLnavEphemerisDecoder.decode(it, reference(f)).decoded) }
    }

    @Test fun resolvesModuloWeekAgainstExplicitTrustedEra() {
        val f = frames()
        val decoded = GpsLnavEphemerisDecoder.decode(f, GpsTime(2340, reference(f).secondsOfWeek)).decoded!!
        assertEquals(2340, decoded.ephemeris.toe.week)
        // Era resolution cannot certify the supplied clock; no wall-clock guess exists here.
        assertNull(GpsLnavEphemerisDecoder.decode(f, GpsTime(2341, reference(f).secondsOfWeek)).decoded)
    }

    @Test fun fitIntervalsFollowAllModernBlockTableRangesAndRejectReservedValues() {
        for (iodc in 0..1023) {
            val normal = iodc in 0..239 || iodc in 256..495 || iodc in 512..751 || iodc in 768..1007
            val expected = when {
                normal -> 6
                iodc in 240..247 -> 8
                iodc in 248..255 || iodc == 496 -> 14
                iodc in 497..503 || iodc in 1021..1023 -> 26
                else -> null
            }
            assertEquals(expected, GpsLnavEphemerisDecoder.fitHours(iodc, true))
            assertEquals(if (normal) 4 else null, GpsLnavEphemerisDecoder.fitHours(iodc, false))
        }
    }
}
