package com.system.location.service.core

import com.system.location.service.core.gnss.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

class GlonassBroadcastPropagatorTest {
    private val records = Json.parseToJsonElement(javaClass.getResource("/gnss/glonass-reference.json")!!.readText())
        .jsonObject.getValue("records").jsonArray.map { it.jsonObject }
    private fun JsonObject.vector(key: String) = getValue(key).jsonArray.map { it.jsonPrimitive.double }
    private fun List<Double>.xyz() = GlonassCartesianVector(this[0], this[1], this[2])
    private fun ephemeris(r: JsonObject = records.first()) = GlonassBroadcastStateVector(
        r.getValue("slot").jsonPrimitive.int,
        GlonassReferenceFrame.valueOf(r.getValue("referenceFrame").jsonPrimitive.content),
        r.vector("positionMeters").xyz(), r.vector("velocityMetersPerSecond").xyz(),
        r.vector("luniSolarAccelerationMetersPerSecondSquared").xyz())
    private fun GlonassOrbitalState.components() = listOf(positionMeters.x, positionMeters.y, positionMeters.z,
        velocityMetersPerSecond.x, velocityMetersPerSecond.y, velocityMetersPerSecond.z)

    @Test fun matchesIndependentCOnRealRinexInputsAndConvergesAgainstOneSecondGrid() {
        records.forEach { r ->
            val e = ephemeris(r)
            r.getValue("expected").jsonArray.map { it.jsonObject }.forEach { case ->
                val seconds = case.getValue("offsetSeconds").jsonPrimitive.double
                val result = GlonassBroadcastPropagator.propagate(e, seconds)
                assertNotNull(result.reason, result.state)
                val state = result.state!!
                assertEquals(e.referenceFrame, state.referenceFrame)
                val actual = state.components()
                val sameStep = case.vector("stateStep10")
                val finerStep = case.vector("stateStep1")
                actual.indices.forEach { i ->
                    assertEquals(sameStep[i], actual[i], if (i < 3) 1e-7 else 1e-10)
                    assertEquals(finerStep[i], actual[i], if (i < 3) 1e-6 else 1e-9)
                }
            }
        }
    }

    @Test fun correctedJ2TermsMatchIndependentPotentialGradientAndRotatingFrameIdentity() {
        val p = GlonassBroadcastPropagator
        val positions = listOf(doubleArrayOf(21e6, -14e6, 7e6), doubleArrayOf(-8e6, 9e6, 23e6),
            doubleArrayOf(0.0, 0.0, 25e6), doubleArrayOf(25e6, 0.0, 0.0))
        val zero = GlonassCartesianVector(0.0, 0.0, 0.0)
        // Positive gravitational potential U; a_grav = grad(U). No shared ODE implementation.
        fun potential(x: DoubleArray): Double {
            val r = hypot(hypot(x[0], x[1]), x[2])
            val legendre2 = (3 * (x[2] / r).pow(2) - 1) / 2
            return p.EARTH_MU / r * (1 - p.EARTH_J2 * (p.EARTH_RADIUS_METERS / r).pow(2) * legendre2)
        }
        positions.forEach { xyz ->
            val stationary = xyz + doubleArrayOf(0.0, 0.0, 0.0)
            val d = p.derivative(stationary, zero)!!
            for (axis in 0..2) {
                val plus = xyz.copyOf().also { it[axis] += 100.0 }
                val minus = xyz.copyOf().also { it[axis] -= 100.0 }
                val expectedGravity = (potential(plus) - potential(minus)) / 200
                val centrifugal = if (axis < 2) p.EARTH_ROTATION_RAD_PER_SECOND.pow(2) * xyz[axis] else 0.0
                assertEquals(expectedGravity, d[axis + 3] - centrifugal, 1e-9)
            }
            val v = doubleArrayOf(-1700.0, 2200.0, 700.0)
            val moving = p.derivative(xyz + v, zero)!!
            assertEquals(2 * p.EARTH_ROTATION_RAD_PER_SECOND * v[1], moving[3] - d[3], 1e-15)
            assertEquals(-2 * p.EARTH_ROTATION_RAD_PER_SECOND * v[0], moving[4] - d[4], 1e-15)
            assertEquals(d[5], moving[5], 0.0)
            val acc = p.derivative(stationary, GlonassCartesianVector(1e-6, -2e-6, 3e-6))!!
            assertEquals(1e-6, acc[3] - d[3], 1e-15)
            assertEquals(-2e-6, acc[4] - d[4], 1e-15)
            assertEquals(3e-6, acc[5] - d[5], 1e-15)
        }
    }

    @Test fun referenceIsExactAndFrameTagNeverPerformsAnUnstatedDatumTransformation() {
        val e = ephemeris()
        val origin = GlonassBroadcastPropagator.propagate(e, 0.0).state!!
        assertEquals(e.positionMeters, origin.positionMeters)
        assertEquals(e.velocityMetersPerSecond, origin.velocityMetersPerSecond)
        val tagged = GlonassBroadcastPropagator.propagate(e.copy(referenceFrame = GlonassReferenceFrame.PZ_90_11), 0.0).state!!
        assertEquals(origin.components(), tagged.components())
        assertEquals(GlonassReferenceFrame.PZ_90_11, tagged.referenceFrame)
    }

    @Test fun rejectsStaleNonfiniteOrEarthIntersectingArcsWithoutModuloRevival() {
        val e = ephemeris()
        listOf(-604800.0, -86400.0, -900.0, 900.0, 86400.0, 604800.0,
            Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY).forEach {
            assertNull(GlonassBroadcastPropagator.propagate(e, it).state)
        }
        assertNotNull(GlonassBroadcastPropagator.propagate(e, -899.999).state)
        assertNotNull(GlonassBroadcastPropagator.propagate(e, 899.999).state)
        val collision = e.copy(positionMeters = GlonassCartesianVector(GlonassBroadcastPropagator.EARTH_RADIUS_METERS + 1, 0.0, 0.0),
            velocityMetersPerSecond = GlonassCartesianVector(-1000.0, 0.0, 0.0))
        assertNull(GlonassBroadcastPropagator.propagate(collision, 1.0).state)
    }
}
