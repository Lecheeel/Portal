package com.system.location.service.core

import com.system.location.service.core.gnss.*
import java.util.Base64
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class GalileoInavAssemblerTest {
    private val fixture = Json.parseToJsonElement(javaClass.getResource("/gnss/galileo-pages-reference.json")!!
        .readText()).jsonObject.getValue("pages").jsonArray.map { it.jsonObject }
        .filter { it.getValue("type").jsonPrimitive.int == 0x0601 && it.getValue("wordType").jsonPrimitive.int in 1..5 }
    private val from = GalileoTime(1081, 424800.0)
    private val until = GalileoTime(1081, 439200.0)
    private fun reference(type: Int) = GalileoTime(1081, 432694.0 + type * 2)
    private fun assembler() = GalileoInavAssembler(GalileoTime(1081, 432690.0), GalileoTime(1081, 432810.0), from, until)

    /** Synthetic Android even/odd pair; CRC uses independent polynomial long division. */
    private fun observation(type: Int, svid: Int = 24, iod: Int = 80, tow: Int = 432704, week: Int = 1081,
        edits: List<Triple<Int, Int, Long>> = emptyList(), arrival: Long = type * 2000L): GnssNavigationObservation {
        val bits = fixture[type - 1].getValue("dataHex").jsonPrimitive.content.flatMap { c ->
            (3 downTo 0).map { (c.digitToInt(16) ushr it) and 1 }
        }.toMutableList()
        fun set(start: Int, length: Int, value: Long) = repeat(length) { bits[start + it] = (value ushr (length - it - 1) and 1).toInt() }
        if (type in 1..4) set(6, 10, iod.toLong())
        if (type == 4) set(16, 6, svid.toLong())
        if (type == 5) { set(73, 12, (week % 4096).toLong()); set(85, 20, tow.toLong()) }
        edits.forEach { (start, length, value) -> set(start, length, value) }
        val protected = MutableList(196) { 0 }.also { it[114] = 1 }
        repeat(128) { bit -> protected[if (bit < 112) bit + 2 else bit + 4] = bits[bit] }
        val divided = (protected + List(24) { 0 }).toMutableList()
        val polynomial = (24 downTo 0).map { (0x1864CFB ushr it) and 1 }
        repeat(196) { start -> if (divided[start] != 0) polynomial.forEachIndexed { bit, value ->
            divided[start + bit] = divided[start + bit] xor value
        } }
        val encoded = protected + divided.takeLast(24) + List(12) { 0 } // SSP + Android padding.
        val bytes = encoded.chunked(8).map { octet -> octet.fold(0) { n, bit -> n * 2 + bit }.toByte() }.toByteArray()
        return GnssNavigationObservation(arrival, 0x0601, svid, 1, -1, type, Base64.getEncoder().encodeToString(bytes))
    }
    private fun complete(a: GalileoInavAssembler, iod: Int = 80, svid: Int = 24, shift: Double = 0.0) {
        (1..5).forEach { a.offer(observation(it, svid, iod), reference(it).plusSeconds(shift)) }
    }

    @Test fun all120ArrivalOrdersMatchTheCompletePageDecoder() {
        val expected = GalileoInavEphemerisDecoder.decode((1..5).map {
            GalileoNavigationPages.decode(observation(it)).page!!
        }, reference(5), from, until).decoded!!
        fun permutations(values: List<Int>): List<List<Int>> = if (values.isEmpty()) listOf(emptyList()) else
            values.flatMap { head -> permutations(values - head).map { listOf(head) + it } }
        permutations((1..5).toList()).forEach { order ->
            val a = assembler()
            order.forEach { a.offer(observation(it), reference(it)) }
            assertEquals("order=$order", listOf(expected), a.completed())
        }
    }

    @Test fun wordFiveUpdatesEverySelectedIssueWithoutIodAndOldArrivalsDoNotRestoreHealth() {
        val a = assembler()
        complete(a)
        val newer = observation(5, tow = 432754, edits = listOf(Triple(69, 2, 1L), Triple(57, 10, 3L)))
        a.offer(newer, reference(5).plusSeconds(50.0))
        val changed = a.completed().single()
        assertFalse(changed.e1Status.usable)
        assertTrue(changed.e5bStatus.usable)
        assertEquals(Math.scalb(3.0, -32), changed.bgdE1E5bSeconds, 0.0)
        a.offer(observation(5, arrival = 90000), reference(5))
        assertEquals(changed, a.completed().single())
        val next = observation(5, tow = 432764, edits = listOf(Triple(71, 1, 1L)))
        a.offer(next, reference(5).plusSeconds(60.0))
        assertFalse(a.completed().single().e5bStatus.usable)
    }

    @Test fun newerPartialIssueWithdrawsOldClockAndKeepsLatestIndependentHealth() {
        val a = assembler()
        complete(a)
        a.offer(observation(5, tow = 432714, edits = listOf(Triple(69, 2, 1L))), reference(5).plusSeconds(10.0))
        a.offer(observation(2, iod = 81), reference(2).plusSeconds(20.0))
        assertTrue(a.completed().isEmpty())
        complete(a)
        assertTrue(a.completed().isEmpty())
        listOf(1, 3, 4).forEach { a.offer(observation(it, iod = 81), reference(it).plusSeconds(20.0)) }
        val decoded = a.completed().single()
        assertEquals(81, decoded.ephemeris.iodnav)
        assertFalse(decoded.e1Status.usable)
    }

    @Test fun simultaneousDifferentIssuesAreAmbiguousRatherThanOrderedByArrival() {
        val a = assembler()
        complete(a)
        a.offer(observation(4, iod = 81), reference(4))
        assertTrue(a.completed().isEmpty())
        complete(a)
        assertTrue(a.completed().isEmpty())
        complete(a, iod = 81, shift = 20.0)
        assertEquals(81, a.completed().single().ephemeris.iodnav)
    }

    @Test fun lateCrcValidConflictInEachCedWordRetractsCompletedEvidencePermanently() {
        val changes = listOf(Triple(30, 32, 0L), Triple(16, 32, 0L), Triple(16, 24, 0L), Triple(68, 31, 0L))
        changes.forEachIndexed { index, change ->
            val a = assembler()
            complete(a)
            assertEquals(1, a.completed().size)
            val conflict = observation(index + 1, edits = listOf(change))
            assertNotNull(GalileoNavigationPages.decode(conflict).page)
            assertTrue(a.offer(conflict, reference(index + 1)).reason!!.contains("conflicting"))
            assertTrue(a.completed().isEmpty())
            complete(a)
            assertTrue(a.completed().isEmpty())
        }
    }

    @Test fun conflictingServiceAtTheSameEpochNeedsNewerServiceEvidence() {
        val a = assembler()
        complete(a)
        a.offer(observation(5, edits = listOf(Triple(72, 1, 1L))), reference(5))
        assertTrue(a.completed().isEmpty())
        complete(a)
        assertTrue(a.completed().isEmpty())
        a.offer(observation(5, tow = 432714, edits = listOf(Triple(72, 1, 1L))), reference(5).plusSeconds(10.0))
        assertFalse(a.completed().single().e1Status.usable)
    }

    @Test fun satelliteEvidenceAndMissingWordFiveCannotBeBorrowedAcrossIdentities() {
        val a = assembler()
        (1..4).forEach { a.offer(observation(it), reference(it)) }
        a.offer(observation(5, svid = 23), reference(5))
        assertTrue(a.completed().isEmpty())
        a.offer(observation(5), reference(5))
        assertEquals(listOf(24), a.completed().map { it.ephemeris.svid })
        complete(a, svid = 23)
        assertEquals(listOf(23, 24), a.completed().map { it.ephemeris.svid })
    }

    @Test fun fullGstWeekAnd12BitEraRolloverRemainSeparateFromGpsAndUtc() {
        val start = GalileoTime(4095, 604796.0)
        val a = GalileoInavAssembler(start, start.plusSeconds(120.0), GalileoTime(4095, 424800.0), start.plusSeconds(200.0))
        (1..5).reversed().forEach { type ->
            val edits = when (type) {
                1 -> listOf(Triple(16, 14, 10079L)) // toe=604740, still usable across rollover.
                4 -> listOf(Triple(54, 14, 10079L))
                else -> emptyList()
            }
            a.offer(observation(type, tow = 4, week = 4096, edits = edits), start.plusSeconds((type - 1) * 2.0))
        }
        val decoded = a.completed().single()
        assertEquals(GalileoTime(4096, 4.0), decoded.transmissionTimeOfWordFive)
        assertEquals(4095, decoded.ephemeris.toe.week)
    }

    @Test fun invalidCrcTimeAndIdentityDoNotDisplaceValidEvidence() {
        val a = assembler()
        complete(a)
        val expected = a.completed()
        val original = observation(5)
        val bytes = Base64.getDecoder().decode(original.dataBase64).also { it[5] = (it[5].toInt() xor 1).toByte() }
        assertNull(a.offer(original.copy(dataBase64 = Base64.getEncoder().encodeToString(bytes)), reference(5)).decoded)
        assertNull(a.offer(observation(5, week = 1082), reference(5)).decoded)
        assertNull(a.offer(observation(5, tow = 604800), reference(5)).decoded)
        assertNull(a.offer(observation(5), reference(5).plusSeconds(121.0)).decoded)
        assertNull(a.offer(observation(4, edits = listOf(Triple(16, 6, 23L))), reference(4)).decoded)
        assertEquals(expected, a.completed())
    }

    @Test fun reservedBitsMayChangeWithoutChangingOrbitClockOrSisa() {
        val a = assembler()
        complete(a)
        val expected = a.completed().single().ephemeris
        a.offer(observation(1, edits = listOf(Triple(126, 2, 3L))), reference(1).plusSeconds(10.0))
        assertEquals(expected, a.completed().single().ephemeris)
    }

    @Test fun boundedStorageNeverFallsBackFromACapacityRejectedNewIssue() {
        val a = assembler()
        complete(a)
        (0..62).forEach { iod -> a.offer(observation(2, iod = iod), reference(2)) }
        assertEquals(1, a.completed().size)
        assertTrue(a.offer(observation(2, iod = 81), reference(2).plusSeconds(30.0)).reason!!.contains("capacity"))
        assertTrue(a.completed().isEmpty())
        complete(a)
        assertTrue(a.completed().isEmpty())
    }

    @Test fun captureScopeAndValidityEvidenceCannotBeInferredFromStatusFlags() {
        assertThrows(IllegalArgumentException::class.java) { GalileoInavAssembler(reference(1), reference(1).plusSeconds(121.0), from, until) }
        assertThrows(IllegalArgumentException::class.java) { GalileoInavAssembler(reference(2), reference(1), from, until) }
        assertThrows(IllegalArgumentException::class.java) { GalileoInavAssembler(reference(1), reference(5), until, from) }
        val expired = GalileoInavAssembler(reference(1), reference(5), from, reference(1))
        assertNull(expired.offer(observation(5), reference(5)).decoded)
    }
}
