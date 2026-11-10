package com.system.location.service.core

import com.system.location.service.core.gnss.*
import java.util.Base64
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class GalileoFnavAssemblerTest {
    private val fixture = Json.parseToJsonElement(javaClass.getResource("/gnss/galileo-pages-reference.json")!!
        .readText()).jsonObject.getValue("pages").jsonArray.map { it.jsonObject }
        .filter { it.getValue("type").jsonPrimitive.int == 0x0602 }
    private val from = GalileoTime(1081, 424800.0)
    private val until = GalileoTime(1081, 439200.0)
    private fun reference(type: Int) = GalileoTime(1081, 432690.0 + type * 10)
    private fun assembler() = GalileoFnavAssembler(reference(1), reference(1).plusSeconds(120.0), from, until)

    /** Repack independent polynomial long division after changing information fields. */
    private fun observation(type: Int, svid: Int = 24, iod: Int = 80, tow: Int = 432690 + type * 10,
        week: Int = 1081, edits: List<Triple<Int, Int, Long>> = emptyList(), arrival: Long = type * 10000L): GnssNavigationObservation {
        val hex = fixture[type - 1].getValue("dataHex").jsonPrimitive.content
        val bits = hex.flatMap { c -> (3 downTo 0).map { (c.digitToInt(16) ushr it) and 1 } }.take(214).toMutableList()
        fun set(start: Int, length: Int, value: Long) = repeat(length) { bits[start + it] = (value ushr (length - 1 - it) and 1).toInt() }
        if (type == 1) set(6, 6, svid.toLong())
        set(if (type == 1) 12 else 6, 10, iod.toLong())
        if (type != 4) set(when (type) { 1 -> 155; 2 -> 182; else -> 174 }, 12, (week % 4096).toLong())
        set(when (type) { 1 -> 167; 2 -> 194; 3 -> 186; else -> 189 }, 20, tow.toLong())
        edits.forEach { (start, length, value) -> set(start, length, value) }
        val divided = (bits + List(24) { 0 }).toMutableList()
        val polynomial = (24 downTo 0).map { (0x1864CFB ushr it) and 1 }
        repeat(214) { start -> if (divided[start] != 0) polynomial.forEachIndexed { bit, value ->
            divided[start + bit] = divided[start + bit] xor value
        } }
        val encoded = bits + divided.takeLast(24) + listOf(0, 0)
        val bytes = encoded.chunked(8).map { octet -> octet.fold(0) { n, bit -> n * 2 + bit }.toByte() }.toByteArray()
        return GnssNavigationObservation(arrival, 0x0602, svid, 1, -1, type, Base64.getEncoder().encodeToString(bytes))
    }
    private fun complete(a: GalileoFnavAssembler, iod: Int = 80, svid: Int = 24) {
        (1..4).forEach { a.offer(observation(it, svid, iod), reference(it)) }
    }

    @Test fun allArrivalOrdersMatchTheCompletePageDecoder() {
        val expected = GalileoFnavEphemerisDecoder.decode((1..4).map {
            GalileoNavigationPages.decode(observation(it)).page!!
        }, reference(1), from, until).decoded!!
        fun permutations(values: List<Int>): List<List<Int>> = if (values.isEmpty()) listOf(emptyList()) else
            values.flatMap { head -> permutations(values - head).map { listOf(head) + it } }
        permutations((1..4).toList()).forEach { order ->
            val a = assembler()
            order.forEach { a.offer(observation(it), reference(it)) }
            assertEquals("order=$order", listOf(expected), a.completed())
        }
    }

    @Test fun satelliteAndIssueComponentsDoNotSpliceAndDuplicatePagesAreIdempotent() {
        val a = assembler()
        a.offer(observation(1), reference(1))
        a.offer(observation(1), reference(1))
        a.offer(observation(2, iod = 81), reference(2))
        a.offer(observation(3, svid = 23), reference(3))
        a.offer(observation(4), reference(4))
        assertTrue(a.completed().isEmpty())
        complete(a)
        complete(a, svid = 23)
        assertEquals(listOf(23, 24), a.completed().map { it.ephemeris.svid })
        assertTrue(a.completed().all { it.ephemeris.iodnav == 80 })
    }

    @Test fun crcValidCedChangesInEveryPageRetractCompletedResultsWithoutRehabilitation() {
        val changes = listOf(Triple(36, 31, 0L), Triple(72, 32, 0L), Triple(16, 32, 0L), Triple(16, 16, 0L))
        changes.forEachIndexed { index, change ->
            val type = index + 1
            val a = assembler()
            complete(a)
            assertEquals(1, a.completed().size)
            val conflict = observation(type, edits = listOf(change))
            assertNotNull(GalileoNavigationPages.decode(conflict).page)
            assertTrue(a.offer(conflict, reference(type)).reason!!.contains("conflicting"))
            assertTrue(a.completed().isEmpty())
            complete(a)
            assertTrue(a.completed().isEmpty())
        }
    }

    @Test fun independentHealthAndBgdUpdatesUseTransmissionTimeNotArrivalOrderOrIod() {
        val a = assembler()
        complete(a)
        val newer = observation(1, tow = 432750, edits = listOf(Triple(153, 2, 1L), Triple(143, 10, 3L)))
        assertNotNull(a.offer(newer, reference(1).plusSeconds(50.0)).decoded)
        val changed = a.completed().single()
        assertFalse(changed.e5aStatus.usable)
        assertEquals(Math.scalb(3.0, -32), changed.bgdE1E5aSeconds, 0.0)
        a.offer(observation(1, arrival = 90000), reference(1))
        assertEquals(changed, a.completed().single())
        val invalid = observation(1, tow = 432760, edits = listOf(Triple(187, 1, 1L)))
        a.offer(invalid, reference(1).plusSeconds(60.0))
        assertFalse(a.completed().single().e5aStatus.usable)
    }

    @Test fun aNewerIssueCannotFallBackToOldHealthWhileItsOtherPagesAreMissing() {
        val a = assembler()
        complete(a)
        assertEquals(1, a.completed().size)
        val newer = observation(1, iod = 81, tow = 432750, edits = listOf(Triple(153, 2, 2L)))
        assertNull(a.offer(newer, reference(1).plusSeconds(50.0)).decoded)
        assertTrue(a.completed().isEmpty())
        complete(a) // Delayed old pages must not restore a superseded healthy issue.
        assertTrue(a.completed().isEmpty())
        (2..4).forEach { a.offer(observation(it, iod = 81), reference(it)) }
        assertEquals(81, a.completed().single().ephemeris.iodnav)
        assertFalse(a.completed().single().e5aStatus.usable)
    }

    @Test fun sameEpochConflictingServicePayloadsCannotBeResolvedByArrivalOrder() {
        for (differentIssue in listOf(false, true)) {
            val a = assembler()
            complete(a)
            val conflict = observation(1, iod = if (differentIssue) 81 else 80, edits = listOf(Triple(153, 2, 1L)))
            a.offer(conflict, reference(1))
            assertTrue(a.completed().isEmpty())
            complete(a)
            assertTrue(a.completed().isEmpty())
        }
    }

    @Test fun invalidCrcOrUntrustedTimeCannotDisplaceAcceptedEvidence() {
        val a = assembler()
        complete(a)
        val expected = a.completed()
        val original = observation(1)
        val bytes = Base64.getDecoder().decode(original.dataBase64).also { it[5] = (it[5].toInt() xor 1).toByte() }
        assertNull(a.offer(original.copy(dataBase64 = Base64.getEncoder().encodeToString(bytes)), reference(1)).decoded)
        assertNull(a.offer(observation(2, week = 1082), reference(2)).decoded)
        assertNull(a.offer(observation(3, tow = 604800), reference(3)).decoded)
        assertNull(a.offer(original, reference(1).plusSeconds(-1.0)).decoded)
        assertNull(a.offer(original, reference(1).plusSeconds(121.0)).decoded)
        assertEquals(expected, a.completed())
    }

    @Test fun independentlyChangingIonosphereAndConversionFieldsAreNotCedConflicts() {
        val a = assembler()
        complete(a)
        val expected = a.completed().single().ephemeris
        val newerOne = observation(1, tow = 432750, edits = listOf(Triple(102, 11, 3L)))
        val newerFour = observation(4, tow = 432780, edits = listOf(Triple(48, 32, 3L)))
        assertNotNull(a.offer(newerOne, reference(1).plusSeconds(50.0)).decoded)
        assertNotNull(a.offer(newerFour, reference(4).plusSeconds(50.0)).decoded)
        assertEquals(expected, a.completed().single().ephemeris)
    }

    @Test fun newerNapaServiceEvidenceWithdrawsTheOldValidIssue() {
        val a = assembler()
        complete(a)
        a.offer(observation(1, iod = 81, tow = 432750, edits = listOf(Triple(94, 8, 255L))),
            reference(1).plusSeconds(50.0))
        (2..4).forEach { a.offer(observation(it, iod = 81), reference(it)) }
        assertTrue(a.completed().isEmpty())
        complete(a)
        assertTrue(a.completed().isEmpty())
    }

    @Test fun fullGstEraAndWeekRolloverAreResolvedWithoutUtcOrGpsAliases() {
        val start = GalileoTime(4095, 604780.0)
        val a = GalileoFnavAssembler(start, start.plusSeconds(120.0), GalileoTime(4095, 424800.0), start.plusSeconds(200.0))
        (1..4).reversed().forEach { type ->
            val time = start.plusSeconds((type - 1) * 10.0)
            val edits = when (type) {
                1 -> listOf(Triple(22, 14, 10079L)) // toc=604740.
                3 -> listOf(Triple(160, 14, 10079L)) // toe=604740.
                else -> emptyList()
            }
            a.offer(observation(type, tow = time.secondsOfWeek.toInt(), week = time.week, edits = edits), time)
        }
        val decoded = a.completed().single()
        assertEquals(start, decoded.transmissionTimeOfPageOne)
        assertEquals(4095, decoded.ephemeris.toe.week)
        assertEquals(4095, decoded.ephemeris.toc.week)
    }

    @Test fun aLargeTransmissionSpanCannotHideBehindTwoOffsetsFromPageOne() {
        val start = reference(1)
        val a = GalileoFnavAssembler(start, start.plusSeconds(120.0), from, until)
        // Each page is within 120s of its capture and of page 1, but the whole set spans 240s.
        a.offer(observation(1), start)
        a.offer(observation(2, tow = 432580), start)
        a.offer(observation(3, tow = 432820), start.plusSeconds(120.0))
        assertNull(a.offer(observation(4), reference(4)).decoded)
        assertTrue(a.completed().isEmpty())
    }

    @Test fun boundedCandidateStorageDoesNotEvictConflictsOrRestoreSupersededHealth() {
        val a = assembler()
        complete(a)
        (0..62).forEach { iod -> a.offer(observation(2, iod = iod), reference(2)) }
        assertEquals(1, a.completed().size)
        val rejected = a.offer(observation(1, iod = 81, tow = 432750), reference(1).plusSeconds(50.0))
        assertTrue(rejected.reason!!.contains("capacity"))
        assertTrue(a.completed().isEmpty())
        complete(a)
        assertTrue(a.completed().isEmpty())
    }

    @Test fun retainedPageCapacityFailsClosedInsteadOfEvictingConflictingEvidence() {
        val a = assembler()
        (2..3).forEach { type -> repeat(256) { offset ->
            val tow = 432580 + offset
            val time = GalileoTime(1081, tow.toDouble().coerceIn(432700.0, 432820.0))
            val result = a.offer(observation(type, tow = tow), time)
            assertFalse(result.reason.orEmpty().contains("capacity"))
        } }
        assertTrue(a.offer(observation(1), reference(1)).reason!!.contains("capacity"))
        complete(a)
        assertTrue(a.completed().isEmpty())
    }

    @Test fun captureScopeAndValidityEvidenceAreExplicitNotInventedFitIntervals() {
        assertThrows(IllegalArgumentException::class.java) { GalileoFnavAssembler(reference(1), reference(1).plusSeconds(121.0), from, until) }
        assertThrows(IllegalArgumentException::class.java) { GalileoFnavAssembler(reference(2), reference(1), from, until) }
        assertThrows(IllegalArgumentException::class.java) { GalileoFnavAssembler(reference(1), reference(4), until, from) }
        val expired = GalileoFnavAssembler(reference(1), reference(4), from, reference(1).plusSeconds(-1.0))
        assertNull(expired.offer(observation(1), reference(1)).decoded)
    }
}
