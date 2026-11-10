package com.system.location.service.core

import com.system.location.service.core.gnss.*
import com.system.location.service.core.BeiDouNavigationTestEncoding.shifted
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class BeiDouD1AssemblerTest {
    private val records = Json.parseToJsonElement(javaClass.getResource("/gnss/beidou-d1-reference.json")!!.readText())
        .jsonObject.getValue("records").jsonArray.map { it.jsonObject.getValue("payloads").jsonArray }
    private fun observation(id: Int, record: Int = 0, svid: Int = 24) = GnssNavigationObservation(
        id * 6000L, 0x0501, svid, 1, 1, id, records[record][id - 1].jsonPrimitive.content)
    private fun time(id: Int) = BeiDouTime(700, 432000.0 + id * 6)
    private fun assembler(types: Map<Int, BeiDouOrbitType> = mapOf(24 to BeiDouOrbitType.MEO)) = BeiDouD1Assembler(
        BeiDouTime(700, 432000.0), BeiDouTime(700, 432120.0),
        BeiDouTime(700, 431000.0), BeiDouTime(700, 433000.0), types)

    @Test fun acceptsEveryArrivalOrderAndKeepsIdentitiesAndOrbitTypeEvidenceIsolated() {
        val orders = listOf(listOf(1, 2, 3), listOf(1, 3, 2), listOf(2, 1, 3), listOf(2, 3, 1),
            listOf(3, 1, 2), listOf(3, 2, 1))
        for (order in orders) {
            val a = assembler(mapOf(24 to BeiDouOrbitType.MEO, 25 to BeiDouOrbitType.IGSO))
            order.forEach { id -> a.offer(observation(id), time(id)) }
            assertEquals(24, a.completed().single().ephemeris.svid)
            order.forEach { id -> a.offer(observation(id, svid = 25), time(id)) }
            assertEquals(listOf(BeiDouOrbitType.MEO, BeiDouOrbitType.IGSO), a.completed().map { it.ephemeris.orbitType })
            assertNull(a.offer(observation(1, svid = 26), time(1)).decoded)
            a.clear()
            assertTrue(a.completed().isEmpty())
        }
    }

    @Test fun lateBchValidConflictsWithdrawResultsAndReplayCannotRehabilitateThem() {
        for (conflictingId in 1..3) {
            val a = assembler()
            (1..3).forEach { a.offer(observation(it), time(it)) }
            assertEquals(1, a.completed().size)
            assertTrue(a.offer(observation(conflictingId, record = 1), time(conflictingId)).reason!!.contains("conflicting"))
            assertTrue(a.completed().isEmpty())
            (1..3).forEach { assertNull(a.offer(observation(it), time(it)).decoded) }
            assertTrue(a.completed().isEmpty())
            a.clear()
            (1..3).forEach { a.offer(observation(it), time(it)) }
            assertEquals(1, a.completed().size)
        }
    }

    @Test fun newerIncompleteFrameWithdrawsOldOutputAndFullNewFrameCanComplete() {
        val a = assembler()
        (1..3).forEach { a.offer(observation(it), time(it)) }
        assertEquals(1, a.completed().size)
        assertNull(a.offer(shifted(observation(2), 30), time(2).plusSeconds(30.0)).decoded)
        assertTrue(a.completed().isEmpty())
        (1..3).forEach { assertNull(a.offer(observation(it), time(it)).decoded) }
        assertNull(a.offer(shifted(observation(1), 30), time(1).plusSeconds(30.0)).decoded)
        assertNotNull(a.offer(shifted(observation(3), 30), time(3).plusSeconds(30.0)).decoded)
        assertEquals(432030.0, a.completed().single().transmissionTime.secondsOfWeek, 0.0)
    }

    @Test fun boundedCaptureAndCapacityRetainConflictsAndWithdrawUnretainedNewerFrames() {
        val a = assembler((1..63).associateWith { BeiDouOrbitType.MEO })
        (1..3).forEach { a.offer(observation(it, svid = 25), time(it)) }
        assertEquals(25, a.completed().single().ephemeris.svid)
        a.offer(observation(1), time(1))
        a.offer(observation(1, record = 1), time(1))
        (1..63).filter { it != 24 }.forEach { a.offer(observation(1, svid = it), time(1)) }
        a.offer(shifted(observation(1), 30), time(1).plusSeconds(30.0)) // 64th candidate
        assertTrue(a.offer(shifted(observation(1), 60), time(1).plusSeconds(60.0)).reason!!.contains("capacity"))
        assertTrue(a.offer(shifted(observation(1, svid = 25), 30), time(1).plusSeconds(30.0)).reason!!.contains("capacity"))
        assertTrue(a.offer(observation(1), time(1)).reason!!.contains("conflicting"))
        assertTrue(a.completed().isEmpty())
        assertNull(a.offer(observation(2), time(2).plusSeconds(121.0)).decoded)
        assertNull(a.offer(observation(3), time(3).plusSeconds(-121.0)).decoded)
        assertThrows(IllegalArgumentException::class.java) { assembler(mapOf(24 to BeiDouOrbitType.GEO)) }
    }

}
