package com.system.location.service.detector.checks

import com.system.location.service.detector.model.*
import org.junit.Assert.*
import org.junit.Test

class NmeaCheckTest {
    private fun sentence(body: String, at: Long = 10000): NmeaSentenceSnapshot {
        val checksum = body.fold(0) { value, char -> value xor char.code }
        return NmeaSentenceSnapshot(at, "$" + body + "*" + "%02X".format(checksum))
    }
    private fun fixture(used: Int = 4, quality: Int = 1, fix: Int = 3,
        dop: String = "1.2,1.0,0.7", gsvAt: Long = 10000): List<NmeaSentenceSnapshot> {
        val gsa = listOf("A", fix.toString()) + (1..12).map { if (it <= used && fix != 1) "%02d".format(it) else "" } + dop.split(',')
        return listOf(
            sentence("GPGGA,120000.00,3100.000,N,12100.000,E,$quality,%02d,1.0,,M,,M,,".format(used)),
            sentence("GPRMC,120000.00,${if (quality > 0) "A" else "V"},3100.000,N,12100.000,E,0,0,101126,,,${if (quality > 0) "A" else "N"}"),
            sentence("GPGSA," + gsa.joinToString(",")),
            sentence("GPGSV,2,1,8,01,10,010,25,02,20,050,26,03,30,100,28,04,40,150,30", gsvAt),
            sentence("GPGSV,2,2,8,05,50,200,32,06,60,250,34,07,70,300,36,08,80,350,38", gsvAt),
        )
    }
    private fun evaluate(lines: List<NmeaSentenceSnapshot>) = NmeaCheck().evaluate(
        SampleSnapshot(startedAtMillis = 10000, endedAtMillis = 20000, nmeaSentences = lines))

    @Test fun usedAndVisibleCountsMayDifferAndGstZdaAreOptional() {
        val result = evaluate(fixture())
        assertEquals(result.detail, CheckStatus.PASS, result.status)
    }

    @Test fun noGnssFixIsValidWithoutInventedDopOrSatelliteUse() {
        val result = evaluate(fixture(used = 0, quality = 0, fix = 1, dop = ",,"))
        assertEquals(result.detail, CheckStatus.PASS, result.status)
    }

    @Test fun usedCannotExceedContemporaneousVisibleCount() {
        val result = evaluate(fixture(used = 9))
        assertEquals(CheckStatus.FAIL, result.status)
        assertTrue(result.detail.contains("在用数超过"))
    }

    @Test fun staleGsvIsNotComparedWithCurrentFix() {
        val result = evaluate(fixture(used = 9, gsvAt = 1000))
        assertFalse(result.detail, result.detail.contains("在用数超过"))
    }

    @Test fun threeDimensionalClaimRequiresAtLeastFourUsedSatellitesAndFiniteDop() {
        val sparse = evaluate(fixture(used = 3))
        assertEquals(CheckStatus.FAIL, sparse.status)
        assertTrue(sparse.detail.contains("三维定位"))
        for (dop in listOf(",,", "NaN,1,1", "1,0,1", "Infinity,1,1")) {
            val result = evaluate(fixture(dop = dop))
            assertTrue(result.detail, result.detail.contains("DOP"))
        }
    }

    @Test fun missingOptionalReceiverOutputIsUnknownInsteadOfForgery() {
        val result = evaluate(fixture().filterNot { it.sentence.contains("GSA") })
        assertEquals(CheckStatus.UNKNOWN, result.status)
    }

    @Test fun previousEpochZdaDoesNotContradictCurrentGga() {
        val result = evaluate(fixture() + sentence("GPZDA,115959.00,10,11,2026,00,00", 9000))
        assertEquals(result.detail, CheckStatus.PASS, result.status)
    }

    @Test fun multiSignalGsvCountsUniqueSatellitesInsteadOfAddingSignalCounts() {
        val base = fixture()
        val signalOne = base.filter { it.sentence.contains("GSV") }.map {
            sentence(it.sentence.substringAfter('$').substringBefore('*') + ",1")
        }
        val signalFive = base.filter { it.sentence.contains("GSV") }.map {
            sentence(it.sentence.substringAfter('$').substringBefore('*') + ",5")
        }
        val result = evaluate(base.filterNot { it.sentence.contains("GSV") } + signalOne + signalFive)
        assertEquals(result.detail, CheckStatus.PASS, result.status)
        assertTrue(result.evidence.joinToString().contains("最新合计=8"))
        assertTrue(result.evidence.joinToString().contains("1 组 burst"))
    }

    @Test fun multiSignalGsvDoesNotMaskTooManyUsedSpacecraft() {
        val base = fixture(used = 9)
        val gsv = base.filter { it.sentence.contains("GSV") }
        val signals = listOf(1, 5).flatMap { signal -> gsv.map {
            sentence(it.sentence.substringAfter('$').substringBefore('*') + ",$signal")
        } }
        val result = evaluate(base.filterNot { it.sentence.contains("GSV") } + signals)
        assertTrue(result.detail, result.detail.contains("在用数超过"))
    }

    @Test fun invalidGsvHeaderAtWindowBoundaryIsStillAnError() {
        val result = evaluate(fixture().filterNot { it.sentence.contains("GSV") } + sentence("GPGSV,0,1,-1"))
        assertEquals(CheckStatus.FAIL, result.status)
        assertTrue(result.detail.contains("GSV 头"))
    }

    @Test fun checksumFailureRemainsAnError() {
        val lines = fixture().toMutableList()
        lines[0] = lines[0].copy(sentence = lines[0].sentence.dropLast(2) + "ZZ")
        val result = evaluate(lines)
        assertEquals(CheckStatus.FAIL, result.status)
        assertTrue(result.detail.contains("校验和"))
    }
}
