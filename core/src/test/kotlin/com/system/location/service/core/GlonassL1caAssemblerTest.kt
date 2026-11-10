package com.system.location.service.core

import com.system.location.service.core.gnss.*
import kotlinx.serialization.json.*
import java.util.Base64
import org.junit.Assert.*
import org.junit.Test

class GlonassL1caAssemblerTest {
    private val records = Json.parseToJsonElement(javaClass.getResource("/gnss/glonass-immediate-reference.json")!!.readText())
        .jsonObject.getValue("records").jsonArray.map { it.jsonObject }
    private val raw = records.first().getValue("rawUnits").jsonObject
    private val date = GlonassCalendarCoordinate.fromBroadcastDate(8, raw.getValue("day").jsonPrimitive.int, 0.0)!!.date
    private val clock = GlonassTimeDomain("synthetic-independent-schedule", GlonassCalendarCoordinate(date, 0.0),
        GlonassCalendarCoordinate(date.plusDays(1), 0.0))
    private val frame = clock.at(GlonassCalendarCoordinate(date, 48270.0))!! // synthetic 13:24:30
    private fun assembler() = GlonassL1caAssembler(frame.plusSeconds(-30.0)!!, frame.plusSeconds(90.0)!!, GlonassReferenceFrame.PZ_90_02)
    private fun observation(id: Int, record: Int = 0, slot: Int = 1, frameId: Int = 5) = GnssNavigationObservation(
        1000L + id * 2000, 0x0301, slot, 1, frameId, id,
        records[record].getValue("androidBase64").jsonArray[id - 1].jsonPrimitive.content)
    private fun fill(a: GlonassL1caAssembler) {
        for (id in listOf(4, 2, 1, 3)) a.offer(observation(id), frame.plusSeconds(2.0 * (id - 1)))
    }

    @Test fun joinsOutOfOrderStringsOnlyWithCompleteIndependentScheduleAndResolvesFullTb() {
        val a = assembler()
        fill(a)
        val d = a.completed().single()
        assertEquals(GlonassCalendarCoordinate(date, 27900.0), d.referenceTime.coordinate)
        assertEquals(frame.coordinate, d.frameScheduleCoordinate)
        assertSame(clock, d.referenceTime.domain)
        // Fixture timing/clock fields are synthetic: joining does not establish orbital/service validity.
        assertEquals(2, d.fields.healthBn)
        for (id in 1..4) a.offer(observation(id), frame.plusSeconds(2.0 * (id - 1)))
        assertEquals(1, a.completed().size)
    }

    @Test fun newerIncompleteOrCorruptFramesWithdrawOldResultAndReplayCannotRestoreIt() {
        val a = assembler()
        fill(a)
        assertNotNull(a.offer(observation(4), frame.plusSeconds(6.0)).decoded)
        a.offer(observation(2, frameId = 1), frame.plusSeconds(32.0))
        assertTrue(a.completed().isEmpty())
        fill(a)
        assertTrue(a.completed().isEmpty())
        val b = assembler()
        fill(b)
        val o = observation(2, frameId = 1)
        val damaged = Base64.getDecoder().decode(o.dataBase64).also { it[10] = (it[10].toInt() xor 1).toByte() }
        b.offer(o.copy(dataBase64 = Base64.getEncoder().encodeToString(damaged)), frame.plusSeconds(32.0))
        fill(b)
        assertTrue(b.completed().isEmpty())
    }

    @Test fun lateValidParityConflictIsRetainedAndCannotBeReplayedAway() {
        val a = assembler()
        fill(a)
        // Another independently C-verified string 1, same slot/tk, different orbit/update fields.
        assertNotNull(GlonassNavigationStrings.decode(observation(1, record = 1)).string)
        assertNull(a.offer(observation(1, record = 1), frame).decoded)
        fill(a)
        assertTrue(a.completed().isEmpty())
        a.clear()
        fill(a)
        assertEquals(1, a.completed().size)
    }

    @Test fun missingTimingAndChangedCorrectionDomainsQuarantineRatherThanMergeOrFallback() {
        for (missing in listOf(true, false)) {
            val a = assembler()
            fill(a)
            val other = GlonassTimeDomain(clock.evidenceId, clock.from, clock.until)
            a.offer(observation(2), if (missing) null else other.at(frame.coordinate))
            fill(a)
            assertTrue(a.completed().isEmpty())
        }
        val a = assembler()
        fill(a)
        a.offer(observation(2), frame.plusSeconds(2.1)) // arrival/physical time is not a nominal schedule label
        assertTrue(a.completed().isEmpty())
        assertThrows(IllegalArgumentException::class.java) {
            GlonassL1caAssembler(frame, frame.plusSeconds(121.0)!!, GlonassReferenceFrame.PZ_90_02)
        }
    }

    @Test fun capacityRejectionKeepsConflictEvidenceAndWithdrawsOlderSelection() {
        val a = assembler()
        fill(a)
        val keys = listOf(0, 30, 60).flatMap { second -> (2..31).map { slot -> slot to second } }.take(63)
        keys.forEach { (slot, second) ->
            val start = frame.plusSeconds(second.toDouble())!!
            val frameId = start.coordinate.secondsOfDay.toInt() / 30 % 5 + 1
            a.offer(observation(2, slot = slot, frameId = frameId), start.plusSeconds(2.0))
        }
        assertEquals(1, a.completed().size)
        val rejected = a.offer(observation(2, frameId = 1), frame.plusSeconds(32.0))
        assertTrue(rejected.reason!!.contains("capacity"))
        fill(a)
        assertTrue(a.completed().isEmpty())
        assertTrue(a.offer(observation(2, frameId = 1), frame.plusSeconds(32.0)).reason!!.contains("capacity"))
    }

    @Test fun mismatchedTkFrameOrCalendarCannotCompleteAndCallbackArrivalIsNeverTheEpoch() {
        for (mode in 0..2) {
            val a = assembler()
            for (id in 1..4) {
                val o = observation(id, frameId = if (mode == 0) 3 else 5)
                val shift = if (mode == 1) 30.0 else 0.0
                a.offer(o, frame.plusSeconds(shift + 2.0 * (id - 1)))
            }
            if (mode < 2) assertTrue(a.completed().isEmpty()) else {
                val wrongDate = GlonassTimeDomain("other-complete-date", clock.from.copy(date = date.plusDays(1)), clock.until.copy(date = date.plusDays(2)))
                val t = wrongDate.at(frame.coordinate.copy(date = date.plusDays(1)))!!
                val b = GlonassL1caAssembler(t, t.plusSeconds(6.0)!!, GlonassReferenceFrame.PZ_90_02)
                for (id in 1..4) b.offer(observation(id), t.plusSeconds(2.0 * (id - 1)))
                assertTrue(b.completed().isEmpty())
            }
        }
    }
}
