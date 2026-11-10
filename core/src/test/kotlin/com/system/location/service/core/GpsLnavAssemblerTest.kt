package com.system.location.service.core

import com.system.location.service.core.gnss.*
import java.util.Base64
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class GpsLnavAssemblerTest {
    private val fixture = Json.parseToJsonElement(javaClass.getResource("/gnss/gps-lnav-words-reference.json")!!
        .readText()).jsonObject.getValue("subframes").jsonArray.map { it.jsonObject }
    private fun observation(index: Int): GnssNavigationObservation {
        val f = fixture[index]
        val id = f.getValue("id").jsonPrimitive.int
        val bytes = f.getValue("payloadHex").jsonPrimitive.content.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        return GnssNavigationObservation(id * 6000L, 0x0101, f.getValue("svid").jsonPrimitive.int,
            1, -1, id, Base64.getEncoder().encodeToString(bytes))
    }
    private fun reference(index: Int) = GpsTime(1316, fixture[index].getValue("nextTowSeconds").jsonPrimitive.double)

    @Test fun acceptsAnyArrivalOrderAndKeepsSatellitesAndFramesIsolated() {
        val assembler = GpsLnavAssembler()
        assertNull(assembler.offer(observation(2), reference(2)).decoded)
        assertNull(assembler.offer(observation(4), reference(4)).decoded)
        assertNull(assembler.offer(observation(0), reference(0)).decoded)
        val result = assembler.offer(observation(1), reference(1))
        assertNotNull(result.reason, result.decoded)
        assertEquals(1, result.decoded!!.ephemeris.svid)
        assertNull(assembler.offer(observation(3), reference(3)).decoded)
        assertEquals(3, assembler.offer(observation(5), reference(5)).decoded!!.ephemeris.svid)
        assembler.clear()
        assertNull(assembler.offer(observation(2), reference(2)).decoded)
    }

    @Test fun duplicateAndInvalidWordsCannotReplaceValidComponents() {
        val assembler = GpsLnavAssembler()
        val first = observation(0)
        assertNull(assembler.offer(first, reference(0)).decoded)
        assertNull(assembler.offer(first, reference(0)).decoded)
        assertNull(assembler.offer(first.copy(dataBase64 = Base64.getEncoder().encodeToString(ByteArray(40))), reference(0)).decoded)
        assertNull(assembler.offer(observation(1), reference(1)).decoded)
        assertNotNull(assembler.offer(observation(2), reference(2)).decoded)
    }

    @Test fun wrongWeekCannotJoinAnOtherwiseMatchingTowAndIssue() {
        val assembler = GpsLnavAssembler()
        assertNull(assembler.offer(observation(0), reference(0)).decoded)
        assertNull(assembler.offer(observation(1), GpsTime(1317, reference(1).secondsOfWeek)).decoded)
        assertNull(assembler.offer(observation(2), reference(2)).decoded)
        assertNotNull(assembler.offer(observation(1), reference(1)).decoded)
        assertNull(assembler.offer(observation(0), reference(0).plusSeconds(121.0)).decoded)
    }

    @Test fun conflictingButParityValidDuplicatePoisonsCandidateUntilExplicitReset() {
        val assembler = GpsLnavAssembler()
        val first = observation(0)
        val conflict = conflictingTlm(first)
        assertNotNull(GpsLnavWords.decode(conflict).subframe)
        assertNull(assembler.offer(first, reference(0)).decoded)
        assertTrue(assembler.offer(conflict, reference(0)).reason!!.contains("conflicting"))
        assertNull(assembler.offer(observation(1), reference(1)).decoded)
        assertNull(assembler.offer(observation(2), reference(2)).decoded)
        assertNull(assembler.offer(first, reference(0)).decoded)
        assembler.clear()
        assembler.offer(first, reference(0))
        assembler.offer(observation(1), reference(1))
        assertNotNull(assembler.offer(observation(2), reference(2)).decoded)
    }

    /** Independently encode Table 20-XIV equations after changing one TLM information bit. */
    private fun conflictingTlm(observation: GnssNavigationObservation): GnssNavigationObservation {
        val frame = GpsLnavWords.decode(observation).subframe!!
        val equations = listOf(
            listOf(29,1,2,3,5,6,10,11,12,13,14,17,18,20,23),
            listOf(30,2,3,4,6,7,11,12,13,14,15,18,19,21,24),
            listOf(29,1,3,4,5,7,8,12,13,14,15,16,19,20,22),
            listOf(30,2,4,5,6,8,9,13,14,15,16,17,20,21,23),
            listOf(30,1,3,5,6,7,9,10,14,15,16,17,18,21,22,24),
            listOf(29,3,5,6,8,9,10,11,13,15,19,22,23,24))
        var previous = 0
        val bytes = ByteArray(40)
        repeat(10) { index ->
            var data = frame.unsigned(index * 24, 24).toInt()
            if (index == 0) data = data xor 0x1000
            fun encoded(value: Int): Int {
                var parity = 0
                equations.forEach { terms ->
                    val bit = terms.fold(0) { acc, term -> acc xor when (term) {
                        29 -> previous ushr 1
                        30 -> previous and 1
                        else -> value ushr (24 - term) and 1
                    } }
                    parity = (parity shl 1) or bit
                }
                val transmitted = value xor if (previous and 1 != 0) 0xFFFFFF else 0
                return (transmitted shl 6) or parity
            }
            if (index == 1 || index == 9) data = (0..3).map { (data and 3.inv()) or it }
                .first { encoded(it) and 3 == 0 }
            val word = encoded(data)
            repeat(4) { bytes[index * 4 + it] = (word ushr (24 - it * 8)).toByte() }
            previous = word and 3
        }
        return observation.copy(dataBase64 = Base64.getEncoder().encodeToString(bytes))
    }

    @Test fun boundedCacheEvictsOldCandidatesAndNeverSplicesDifferentEpochs() {
        val assembler = GpsLnavAssembler()
        assertNull(assembler.offer(observation(0), reference(0)).decoded)
        // The same raw TOW in distinct trusted weeks must not alias; bounded state evicts first.
        for (week in 1317..1381) {
            assertNull(assembler.offer(observation(0), GpsTime(week, reference(0).secondsOfWeek)).decoded)
        }
        assertNull(assembler.offer(observation(1), reference(1)).decoded)
        assertNull(assembler.offer(observation(2), reference(2)).decoded)
        assertNotNull(assembler.offer(observation(0), reference(0)).decoded)
    }
}
