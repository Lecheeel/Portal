package com.system.location.service.core

import com.system.location.service.core.gnss.*
import kotlinx.serialization.json.*
import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class GlonassOrbitalEphemerisTest {
    private val date = LocalDate.of(2023, 12, 31)
    private val domain = GlonassTimeDomain("synthetic-continuous-domain", GlonassCalendarCoordinate(date.minusDays(1), 0.0),
        GlonassCalendarCoordinate(date.plusDays(9), 0.0))
    private val reference = domain.at(GlonassCalendarCoordinate(date, 86399.875))!!
    private fun ephemeris(): GlonassOrbitalEphemeris {
        val r = Json.parseToJsonElement(javaClass.getResource("/gnss/glonass-reference.json")!!.readText())
            .jsonObject.getValue("records").jsonArray.first().jsonObject
        fun xyz(key: String): GlonassCartesianVector {
            val v = r.getValue(key).jsonArray.map { it.jsonPrimitive.double }
            return GlonassCartesianVector(v[0], v[1], v[2])
        }
        val orbit = GlonassBroadcastStateVector(1, GlonassReferenceFrame.PZ_90_02, xyz("positionMeters"),
            xyz("velocityMetersPerSecond"), xyz("luniSolarAccelerationMetersPerSecondSquared"))
        // Deliberately re-epoch the numerical test state; no claim this is real 2023 navigation data.
        return GlonassOrbitalEphemeris(orbit, reference, reference.plusSeconds(-600.0)!!, reference.plusSeconds(600.0)!!)
    }

    @Test fun absolutePropagationMatchesRelativeMathematicsAcrossYearBoundary() {
        val e = ephemeris()
        for (seconds in listOf(-600.0, -30.25, 0.0, 0.25, 600.0)) {
            val result = GlonassAbsolutePropagator.propagate(e, reference.plusSeconds(seconds)!!)
            assertNotNull(result.reason, result.state)
            assertEquals(GlonassBroadcastPropagator.propagate(e.orbit, seconds).state, result.state)
        }
        assertNull(GlonassAbsolutePropagator.propagate(e, reference.plusSeconds(-600.001)!!).state)
        assertNull(GlonassAbsolutePropagator.propagate(e, reference.plusSeconds(600.001)!!).state)
        assertNull(GlonassAbsolutePropagator.propagate(e, reference.plusSeconds(86400.0)!!).state)
        assertNull(GlonassAbsolutePropagator.propagate(e, reference.plusSeconds(604800.0)!!).state)
    }

    @Test fun rejectsDifferentCorrectionDomainsAndUnjustifiedExtrapolationBounds() {
        val e = ephemeris()
        val other = GlonassTimeDomain(domain.evidenceId, domain.from, domain.until)
        assertNull(GlonassAbsolutePropagator.propagate(e, other.at(reference.coordinate)!!).state)
        assertThrows(IllegalArgumentException::class.java) { e.copy(validFrom = other.at(e.validFrom.coordinate)!!) }
        assertThrows(IllegalArgumentException::class.java) { e.copy(validFrom = reference.plusSeconds(1.0)!!) }
        assertThrows(IllegalArgumentException::class.java) { e.copy(validUntil = reference.plusSeconds(-1.0)!!) }
        assertThrows(IllegalArgumentException::class.java) { e.copy(validUntil = reference.plusSeconds(900.0)!!) }
        assertThrows(IllegalArgumentException::class.java) { e.copy(validFrom = reference.plusSeconds(-900.0)!!) }
        assertThrows(IllegalArgumentException::class.java) { e.copy(validFrom = reference, validUntil = reference) }
    }
}
