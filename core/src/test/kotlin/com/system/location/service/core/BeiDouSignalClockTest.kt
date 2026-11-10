package com.system.location.service.core

import com.system.location.service.core.gnss.*
import java.math.BigDecimal
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class BeiDouSignalClockTest {
    private val records = Json.parseToJsonElement(javaClass.getResource("/gnss/beidou-reference.json")!!.readText())
        .jsonObject.getValue("records").jsonArray.map { it.jsonObject }
    private fun JsonObject.n(name: String) = getValue(name).jsonPrimitive.double
    private fun ephemeris(r: JsonObject): BeiDouBroadcastEphemeris {
        val toe = BeiDouTime(r.n("week").toInt(), r.n("toe"))
        return BeiDouBroadcastEphemeris(r.n("svid").toInt(), BeiDouOrbitType.valueOf(r.getValue("orbitType").jsonPrimitive.content),
            toe, BeiDouTime(toe.week, r.n("toc")), toe.plusSeconds(-1000.0), toe.plusSeconds(1000.0),
            r.n("sqrtA"), r.n("e"), r.n("i0"), r.n("omega0"), r.n("omega"), r.n("m0"), r.n("deltaN"),
            r.n("omegaDot"), r.n("iDot"), r.n("cuc"), r.n("cus"), r.n("crc"), r.n("crs"), r.n("cic"),
            r.n("cis"), r.n("af0"), r.n("af1"), r.n("af2"))
    }

    @Test fun matchesIndependentCBaseClockMinusEachSignalTgdWithoutGalileoFrequencyFactors() {
        for (r in records) {
            val e = ephemeris(r)
            val applicability = BeiDouServiceValidity(e.validFrom, e.validUntil)
            for (vector in r.getValue("expected").jsonArray.map { it.jsonObject }) {
                val time = e.toe.plusSeconds(vector.n("offsetSeconds"))
                for ((signal, delay) in listOf(BeiDouLegacySignal.B1I to BigDecimal("-0.0000000345"),
                    BeiDouLegacySignal.B2I to BigDecimal("0.0000000234"))) {
                    val result = if (e.orbitType == BeiDouOrbitType.GEO) {
                        val decoded = BeiDouD2Decoded(e, 0, 5, -345 * 0.1e-9, 234 * 0.1e-9, e.toe, 3, 4)
                        BeiDouSignalClock.correction(decoded, time, signal, applicability)
                    } else {
                        val decoded = BeiDouD1Decoded(e, 0, 5, -345 * 0.1e-9, 234 * 0.1e-9, e.toe, 3, 4)
                        BeiDouSignalClock.correction(decoded, time, signal, applicability)
                    }
                    assertNotNull(result.reason, result.correctionSeconds)
                    val expected = vector.getValue("clockBiasSeconds").jsonPrimitive.content.toBigDecimal() - delay
                    assertEquals(expected.toDouble(), result.correctionSeconds!!, 1e-16)
                }
            }
        }
    }

    @Test fun requiresBothOrbitAndServiceValidityAndRejectsUnhealthyOrUnavailableSignalEvidence() {
        val e = ephemeris(records.last())
        val d = BeiDouD1Decoded(e, 0, 5, 0.0, 0.0, e.toe, 3, 4)
        val service = BeiDouServiceValidity(e.toe.plusSeconds(-1.0), e.toe.plusSeconds(1.0))
        fun correction(decoded: BeiDouD1Decoded = d, t: BeiDouTime = e.toe, signal: BeiDouLegacySignal = BeiDouLegacySignal.B1I) =
            BeiDouSignalClock.correction(decoded, t, signal, service)
        assertNotNull(correction().correctionSeconds)
        assertNull(correction(d.copy(health = 1)).correctionSeconds)
        assertNull(correction(d.copy(uraIndex = 15)).correctionSeconds)
        assertNull(correction(d.copy(tgd1Seconds = Double.NaN)).correctionSeconds)
        assertNull(correction(d.copy(tgd1Seconds = 512 * 0.1e-9)).correctionSeconds)
        assertNotNull(correction(d.copy(tgd1Seconds = -512 * 0.1e-9)).correctionSeconds)
        assertNotNull(correction(d.copy(tgd1Seconds = 511 * 0.1e-9)).correctionSeconds)
        assertNotNull(correction(d.copy(tgd1Seconds = Double.NaN), signal = BeiDouLegacySignal.B2I).correctionSeconds)
        assertNull(correction(t = e.toe.plusSeconds(1.001)).correctionSeconds)
        assertNull(correction(d.copy(transmissionTime = e.toe.plusSeconds(-2.0))).correctionSeconds)
        assertNull(correction(d.copy(ephemeris = e.copy(validUntil = e.toe.plusSeconds(-0.1)))).correctionSeconds)
        assertNull(correction(d.copy(ephemeris = e.copy(orbitType = BeiDouOrbitType.GEO))).correctionSeconds)
        val d2 = BeiDouD2Decoded(e, 0, 5, 0.0, 0.0, e.toe, 3, 4)
        assertNull(BeiDouSignalClock.correction(d2, e.toe, BeiDouLegacySignal.B1I, service).correctionSeconds)
    }
}
