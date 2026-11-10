package com.system.location.service.core

import com.system.location.service.core.BeiDouNavigationTestEncoding.shifted
import com.system.location.service.core.gnss.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class BeiDouD2AssemblerTest {
    private val payloads = Json.parseToJsonElement(javaClass.getResource("/gnss/beidou-d2-reference.json")!!.readText())
        .jsonObject.getValue("records").jsonArray.map { it.jsonObject.getValue("payloads").jsonArray }
    private fun page(number: Int, record: Int = 0, svid: Int = 59) = GnssNavigationObservation(
        (number - 1) * 3000L, 0x0502, svid, 1, number, 1, payloads[record][number - 1].jsonPrimitive.content)
    private fun reference(number: Int) = BeiDouTime(700, 432001.0 + (number - 1) * 3)
    private fun assembler(from: BeiDouTime = BeiDouTime(700, 432000.0)) = BeiDouD2Assembler(from,
        from.plusSeconds(120.0), from.plusSeconds(-1000.0), from.plusSeconds(1000.0))
    private val required = listOf(1, 3, 4, 5, 6, 7, 8, 9, 10)

    @Test fun acceptsEveryCyclicArrivalOrderAndKeepsSatelliteIdentityIsolated() {
        for (first in required.indices) {
            val a = assembler()
            val order = required.drop(first) + required.take(first)
            order.forEach { a.offer(page(it), reference(it)) }
            val result = a.completed().single()
            assertEquals(59, result.ephemeris.svid)
            assertEquals(BeiDouOrbitType.GEO, result.ephemeris.orbitType)
            order.reversed().forEach { a.offer(page(it, svid = 1), reference(it)) }
            assertEquals(setOf(1, 59), a.completed().map { it.ephemeris.svid }.toSet())
            assertNull(a.offer(page(2), reference(2)).decoded) // independent ionospheric page is ignored
            assertEquals(2, a.completed().size)
            a.clear()
            assertTrue(a.completed().isEmpty())
        }
    }

    @Test fun bchValidLateConflictAtEveryRequiredPageWithdrawsCompletedResultUntilReset() {
        for (number in required) {
            val a = assembler()
            required.forEach { a.offer(page(it), reference(it)) }
            assertEquals(1, a.completed().size)
            assertTrue(a.offer(page(number, record = 1), reference(number)).reason!!.contains("conflicting"))
            assertTrue(a.completed().isEmpty())
            required.forEach { assertNull(a.offer(page(it), reference(it)).decoded) }
            assertTrue(a.completed().isEmpty())
        }
    }

    @Test fun newerIncompleteCycleWithdrawsOlderOutputAndCannotBeReversedByOldPages() {
        val a = assembler()
        required.forEach { a.offer(page(it), reference(it)) }
        assertEquals(1, a.completed().size)
        a.offer(shifted(page(5), 30), reference(5).plusSeconds(30.0))
        assertTrue(a.completed().isEmpty())
        required.forEach { assertNull(a.offer(page(it), reference(it)).decoded) }
        required.filter { it != 5 }.forEach { a.offer(shifted(page(it), 30), reference(it).plusSeconds(30.0)) }
        assertEquals(432030.0, a.completed().single().transmissionTimeOfPageOne.secondsOfWeek, 0.0)
    }

    @Test fun capacityDoesNotEvictConflictOrKeepOlderOutputAfterNewerCandidateCannotBeStored() {
        val a = assembler()
        required.forEach { a.offer(page(it, svid = 1), reference(it)) }
        a.offer(page(1), reference(1))
        a.offer(page(1, record = 1), reference(1))
        (2..63).filter { it != 59 }.forEach { a.offer(page(1, svid = it), reference(1)) }
        a.offer(shifted(page(1), 30), reference(1).plusSeconds(30.0)) // 64th candidate
        assertTrue(a.offer(shifted(page(1, svid = 1), 30), reference(1).plusSeconds(30.0)).reason!!.contains("capacity"))
        assertTrue(a.offer(page(1), reference(1)).reason!!.contains("conflicting"))
        assertTrue(a.completed().isEmpty())
        assertNull(a.offer(page(3), reference(3).plusSeconds(121.0)).decoded)
        assertNull(a.offer(page(3), reference(3).plusSeconds(-121.0)).decoded)
    }

    @Test fun latePagesAcrossWeekBoundaryJoinTheCorrectCycleAndEpochUnderflowIsRejected() {
        // Shift labels around the boundary to check key association; not receiver evidence.
        val from = BeiDouTime(700, 604790.0)
        val a = assembler(from)
        required.reversed().forEach { number ->
            a.offer(shifted(page(number), 172797), BeiDouTime(700, 604798.0).plusSeconds((number - 1) * 3.0))
        }
        assertEquals(BeiDouTime(700, 604797.0), a.completed().single().transmissionTimeOfPageOne)
        a.offer(shifted(page(9, record = 1), 172797), BeiDouTime(701, 22.0))
        assertTrue(a.completed().isEmpty())
        val epoch = BeiDouD2Assembler(BeiDouTime(0, 0.0), BeiDouTime(0, 120.0), BeiDouTime(0, 0.0), BeiDouTime(0, 1000.0))
        val result = epoch.offer(shifted(page(10), -432027), BeiDouTime(0, 1.0))
        assertNull(result.decoded)
        assertTrue(result.reason!!.contains("before BDT epoch"))
    }
}
