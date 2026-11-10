package com.system.location.service.core

import com.system.location.service.core.gnss.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class BeiDouD1ReferenceTest {
    @Test fun decodesIndependentCFieldsFromSyntheticInterleavedWireMessages() {
        val records = Json.parseToJsonElement(javaClass.getResource("/gnss/beidou-d1-reference.json")!!.readText())
            .jsonObject.getValue("records").jsonArray
        for (record in records.map { it.jsonObject }) {
            val subframes = record.getValue("payloads").jsonArray.mapIndexed { index, payload ->
                val observation = GnssNavigationObservation(1, 0x0501, 24, 1, 1, index + 1,
                    payload.jsonPrimitive.content)
                val result = BeiDouNavigationWords.decode(observation)
                assertNotNull(result.reason, result.subframe)
                result.subframe!!
            }
            val result = BeiDouD1EphemerisDecoder.decode(subframes, BeiDouTime(700, 431000.0),
                BeiDouTime(700, 433000.0), BeiDouOrbitType.IGSO, BeiDouTime(700, 432006.0))
            assertNotNull(result.reason, result.decoded)
            val decoded = result.decoded!!
            val e = decoded.ephemeris
            val expected = record.getValue("expected").jsonObject
            fun n(name: String) = expected.getValue(name).jsonPrimitive.double
            val actual = mapOf("sqrtA" to e.sqrtSemiMajorAxis, "e" to e.eccentricity,
                "i0" to e.inclinationRad, "omega0" to e.ascendingNodeRad, "omega" to e.argumentOfPerigeeRad,
                "m0" to e.meanAnomalyRad, "deltaN" to e.deltaMeanMotionRadPerSecond,
                "omegaDot" to e.ascendingNodeRateRadPerSecond, "iDot" to e.inclinationRateRadPerSecond,
                "crc" to e.crcMeters, "crs" to e.crsMeters, "cuc" to e.cucRad, "cus" to e.cusRad,
                "cic" to e.cicRad, "cis" to e.cisRad, "af0" to e.af0Seconds,
                "af1" to e.af1SecondsPerSecond, "af2" to e.af2SecondsPerSecondSquared,
                "tgd1" to decoded.tgd1Seconds, "tgd2" to decoded.tgd2Seconds)
            actual.forEach { (name, value) ->
                assertEquals(name, n(name), value, Math.ulp(n(name)) * 4)
            }
            assertEquals(n("week").toInt(), e.toe.week)
            assertEquals(n("toe"), e.toe.secondsOfWeek, 0.0)
            assertEquals(n("health").toInt(), decoded.health)
            assertEquals(n("ura").toInt(), decoded.uraIndex)
            assertEquals(n("aodc").toInt(), decoded.ageOfDataClock)
            assertEquals(n("aode").toInt(), decoded.ageOfDataEphemeris)
            assertEquals(BeiDouOrbitType.IGSO, e.orbitType)
        }
    }
}
