package com.system.location.service.core

import com.system.location.service.core.gnss.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class GalileoBroadcastPropagatorTest {
    private val records = Json.parseToJsonElement(javaClass.getResource("/gnss/galileo-reference.json")!!
        .readText()).jsonObject.getValue("records").jsonArray.map { it.jsonObject }
    private fun JsonObject.n(name: String) = getValue(name).jsonPrimitive.double
    private fun ephemeris(r: JsonObject = records.first()): GalileoBroadcastEphemeris {
        fun n(name: String) = r.n(name)
        val toe = GalileoTime(n("gstWeek").toInt(), n("toe"))
        return GalileoBroadcastEphemeris(n("svid").toInt(), toe, GalileoTime(toe.week, n("toc")),
            toe.plusSeconds(-7200.0), toe.plusSeconds(7200.0), n("iodnav").toInt(),
            GalileoClockReference.valueOf(r.getValue("clockReference").jsonPrimitive.content),
            n("sqrtA"), n("e"), n("i0"), n("omega0"), n("omega"), n("m0"), n("deltaN"), n("omegaDot"), n("iDot"),
            n("cuc"), n("cus"), n("crc"), n("crs"), n("cic"), n("cis"), n("af0"), n("af1"), n("af2"))
    }

    @Test fun matchesAllTwelveRtkLibCasesForFourActualRecordsAndBothClockReferences() {
        records.forEach { r ->
            val e = ephemeris(r)
            r.getValue("expected").jsonArray.map { it.jsonObject }.forEach { expected ->
                val result = GalileoBroadcastPropagator.propagate(e, e.toe.plusSeconds(expected.n("offsetSeconds")))
                assertNotNull(result.reason, result.state)
                val state = result.state!!
                assertEquals(expected.n("xMeters"), state.xMeters, 1e-5)
                assertEquals(expected.n("yMeters"), state.yMeters, 1e-5)
                assertEquals(expected.n("zMeters"), state.zMeters, 1e-5)
                assertEquals(expected.n("clockBiasSeconds"), state.clockBiasSeconds, 1e-16)
                assertEquals(e.clockReference, state.clockReference)
            }
        }
        assertEquals(2, records.map { ephemeris(it).clockReference }.distinct().size)
    }

    @Test fun rejectsStaleFullWeeksAndValidityEdgesBeforePeriodicOrbitCalculation() {
        val e = ephemeris()
        assertNull(GalileoBroadcastPropagator.propagate(e, e.toe.plusSeconds(604800.0)).state)
        assertNull(GalileoBroadcastPropagator.propagate(e, e.validFrom.plusSeconds(-0.001)).state)
        assertNull(GalileoBroadcastPropagator.propagate(e, e.validUntil.plusSeconds(0.001)).state)
        assertNotNull(GalileoBroadcastPropagator.propagate(e, e.validFrom).state)
        assertNotNull(GalileoBroadcastPropagator.propagate(e, e.validUntil).state)
    }

    @Test fun shortenedOperationalIntervalCanStartAfterParameterReferenceEpochs() {
        val e = ephemeris()
        val time = e.toe.plusSeconds(600.0)
        val shortened = e.copy(validFrom = time, validUntil = time.plusSeconds(60.0))
        assertEquals(GalileoBroadcastPropagator.propagate(e, time).state,
            GalileoBroadcastPropagator.propagate(shortened, time).state)
        assertNull(GalileoBroadcastPropagator.propagate(shortened, time.plusSeconds(-0.001)).state)
    }

    @Test fun clockModelIdentityIsPreservedAndDoesNotSilentlyApplyBgd() {
        val first = ephemeris()
        val second = ephemeris(records[1])
        val a = GalileoBroadcastPropagator.propagate(first, first.toe).state!!
        val b = GalileoBroadcastPropagator.propagate(second, second.toe).state!!
        assertEquals(a.xMeters, b.xMeters, 0.0)
        assertEquals(a.yMeters, b.yMeters, 0.0)
        assertEquals(a.zMeters, b.zMeters, 0.0)
        assertEquals(second.af0Seconds - first.af0Seconds, b.clockBiasSeconds - a.clockBiasSeconds, 1e-18)
        assertNotEquals(a.clockReference, b.clockReference)
    }

    @Test fun fullWeekCrossingKeepsGalileoTrajectoryContinuous() {
        val original = ephemeris()
        val toe = GalileoTime(1081, 604799.0)
        val e = original.copy(toe = toe, toc = toe, validFrom = toe.plusSeconds(-7200.0), validUntil = toe.plusSeconds(7200.0))
        val a = GalileoBroadcastPropagator.propagate(e, toe.plusSeconds(0.999)).state!!
        val b = GalileoBroadcastPropagator.propagate(e, toe.plusSeconds(1.001)).state!!
        val distance = kotlin.math.hypot(kotlin.math.hypot(a.xMeters - b.xMeters, a.yMeters - b.yMeters), a.zMeters - b.zMeters)
        assertTrue(distance in 0.1..20.0)
    }

    @Test fun invalidBroadcastFieldsCannotCreateParameters() {
        val e = ephemeris()
        assertThrows(IllegalArgumentException::class.java) { e.copy(svid = 0) }
        assertThrows(IllegalArgumentException::class.java) { e.copy(svid = 37) }
        assertThrows(IllegalArgumentException::class.java) { e.copy(iodnav = 1024) }
        assertThrows(IllegalArgumentException::class.java) { e.copy(eccentricity = 0.5) }
        assertThrows(IllegalArgumentException::class.java) { e.copy(eccentricity = Double.NaN) }
        assertThrows(IllegalArgumentException::class.java) { e.copy(sqrtSemiMajorAxis = 8192.0) }
        assertThrows(IllegalArgumentException::class.java) { e.copy(af0Seconds = Double.POSITIVE_INFINITY) }
    }
}
