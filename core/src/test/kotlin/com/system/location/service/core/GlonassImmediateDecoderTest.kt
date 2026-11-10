package com.system.location.service.core

import com.system.location.service.core.gnss.*
import kotlinx.serialization.json.*
import java.math.BigInteger
import java.util.Base64
import org.junit.Assert.*
import org.junit.Test

class GlonassImmediateDecoderTest {
    private val records = Json.parseToJsonElement(javaClass.getResource("/gnss/glonass-immediate-reference.json")!!.readText())
        .jsonObject.getValue("records").jsonArray.map { it.jsonObject }
    private fun JsonObject.n(key: String) = getValue(key).jsonPrimitive.double
    private fun observations(r: JsonObject = records.first()): List<GnssNavigationObservation> {
        val slot = r.getValue("rawUnits").jsonObject.n("slot").toInt()
        return r.getValue("androidBase64").jsonArray.mapIndexed { i, s ->
            GnssNavigationObservation(1000L + i * 2000, 0x0301, slot, 1, 3, i + 1, s.jsonPrimitive.content)
        }
    }
    private fun strings(r: JsonObject = records.first()) = observations(r).map {
        val decoded = GlonassNavigationStrings.decode(it)
        assertNotNull(decoded.reason, decoded.string)
        decoded.string!!
    }
    private fun decode(s: List<GlonassNavigationString>) = GlonassImmediateDecoder.decode(s, GlonassReferenceFrame.PZ_90_02)

    @Test fun matchesAllIndependentCOrbitalClockAndFlagFieldsWithoutInventingAnEpoch() {
        records.forEach { r ->
            val result = decode(strings(r).reversed())
            assertNotNull(result.reason, result.fields)
            val d = result.fields!!
            val e = r.getValue("expected").jsonObject
            val raw = r.getValue("rawUnits").jsonObject
            fun assertVector(name: String, v: GlonassCartesianVector) {
                val expected = e.getValue(name).jsonArray.map { it.jsonPrimitive.double }
                assertEquals(expected[0], v.x, 0.0)
                assertEquals(expected[1], v.y, 0.0)
                assertEquals(expected[2], v.z, 0.0)
            }
            assertVector("positionMeters", d.orbit.positionMeters)
            assertVector("velocityMetersPerSecond", d.orbit.velocityMetersPerSecond)
            assertVector("luniSolarAccelerationMetersPerSecondSquared", d.orbit.luniSolarAccelerationMetersPerSecondSquared)
            assertEquals(e.n("tauNSeconds"), d.tauNSeconds, 0.0)
            // Pinned C P2_40 is a rounded decimal macro, one ULP below exact 2^-40.
            // Preserve exact binary ICD scaling in Kotlin; do not reproduce that rounding.
            assertEquals(e.n("gammaN"), d.gammaN, 2 * Math.ulp(e.n("gammaN")))
            assertEquals(raw.n("gammaN") * Math.scalb(1.0, -40), d.gammaN, 0.0)
            assertEquals(e.n("deltaTauNSeconds"), d.deltaTauNSeconds, 0.0)
            assertEquals(e.n("bn").toInt(), d.healthBn)
            assertEquals(e.n("ft").toInt(), d.accuracyFt)
            assertEquals(e.n("age").toInt(), d.ageDays)
            assertEquals(e.n("tb").toInt() * 900, d.referenceTimeSecondsOfDay)
            assertEquals(raw.n("day").toInt(), d.dayInFourYearInterval)
            assertEquals((raw.n("hours") * 3600 + raw.n("minutes") * 60 + raw.n("halfMinute") * 30).toInt(), d.frameTimeSecondsOfDay)
            val flags = listOf(d.healthLn, d.updateP1, d.oddnessP2, d.almanacCountP3, d.ephemerisUpdateP4, d.timeParameterModeP)
            assertEquals(listOf("ln", "p1", "p2", "p3", "p4", "p").map { raw.n(it).toInt() }, flags)
            assertEquals(raw.n("slot").toInt(), d.orbit.slot)
        }
    }

    @Test fun rejectsIncompleteDuplicateMixedSlotFrameAndExcessiveArrivalWindows() {
        val s = strings()
        assertNull(decode(s.dropLast(1)).fields)
        assertNull(decode(s.take(3) + s.first()).fields)
        fun altered(o: GnssNavigationObservation) = GlonassNavigationStrings.decode(o).string!!
        val observations = observations()
        assertNull(decode(s.take(3) + altered(observations.last().copy(svid = 2))).fields)
        assertNull(decode(s.take(3) + altered(observations.last().copy(messageId = 4))).fields)
        assertNull(decode(s.take(3) + altered(observations.last().copy(capturedAtMillis = 31_001))).fields)
        assertNotNull(decode(s.take(3) + altered(observations.last().copy(capturedAtMillis = 31_000))).fields)
    }

    @Test fun rejectsInvalidEncodedSlotVariantDateTimeAndOrbitalStateAtFieldLayer() {
        // Test the field layer directly with internal objects; wire parity is separately exhaustively tested.
        fun withField(s: List<GlonassNavigationString>, id: Int, high: Int, length: Int, value: Int): List<GlonassNavigationString> {
            val o = observations()[id - 1]
            val bytes = Base64.getDecoder().decode(o.dataBase64)
            val shift = high - length
            val mask = BigInteger.ONE.shiftLeft(length).subtract(BigInteger.ONE).shiftLeft(shift)
            val modified = BigInteger(1, bytes).andNot(mask).or(BigInteger.valueOf(value.toLong()).shiftLeft(shift))
                .toByteArray().let { ByteArray(11 - it.size) + it }
            return s.map { if (it.stringId != id) it else GlonassNavigationString(it.slot, it.capturedAtMillis, it.frameId, id, modified) }
        }
        val original = strings()
        listOf(listOf(4,15,5,2), listOf(4,10,2,0), listOf(4,10,2,3), listOf(4,26,11,0),
            listOf(4,26,11,1462), listOf(2,76,7,0), listOf(2,76,7,96), listOf(1,76,5,24),
            listOf(1,71,6,60)).forEach { (id, high, length, value) ->
            assertNull(decode(withField(original, id, high, length, value)).fields)
        }
        var singular = original
        for (id in 1..3) singular = withField(singular, id, 35, 27, 0)
        assertNull(decode(singular).fields)
    }
}
