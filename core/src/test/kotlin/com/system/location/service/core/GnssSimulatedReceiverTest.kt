package com.system.location.service.core

import com.system.location.service.core.geo.Wgs84
import com.system.location.service.core.gnss.*
import org.junit.Assert.*
import org.junit.Test

class GnssSimulatedReceiverTest {
    private val request = GnssSkyRequest(1_800_000_000_000, Wgs84(0.0, 0.0), 0.0)
    private val orbit = GnssOrbitPosition(Constellation.GPS, 1, EcefPosition(26_560_000.0, 0.0, 0.0),
        GnssOrbitProvenance("fixture", "1", "test", 1000, GnssOrbitSourceKind.ALMANAC), false, true)
    private val receiver = GnssSimulatedReceiver(123)
    private val virtualOrbit = orbit.copy(provenance = orbit.provenance.copy(kind = GnssOrbitSourceKind.VIRTUAL))
    private fun navigation(from: Long = request.atMillis, until: Long = request.atMillis + 1000) =
        GnssSimulatedNavigation(virtualOrbit.constellation, virtualOrbit.svid, virtualOrbit.provenance,
            from, until, GnssVirtualClock(from)) { at ->
            EcefPosition(orbit.position.xMeters, (at.atMillis - request.atMillis).toDouble(), 0.0)
        }

    @Test fun strongAlmanacSignalDoesNotInventEphemerisOrUsage() {
        val signal = receiver.observe(orbit, request, GnssSimulationEnvironment.OPEN_SKY)!!
        assertTrue(signal.tracked)
        assertTrue(signal.satellite.cn0DbHz > 35f)
        assertFalse(signal.satellite.usedInFix)
        assertFalse(signal.satellite.hasEphemeris)
        assertTrue(signal.satellite.hasAlmanac)
        assertFalse(signal.simulatedNavigation)
        assertEquals(0f, signal.satellite.azimuthDeg, 0f)
        assertEquals(90f, signal.satellite.elevationDeg, 0f)
    }

    @Test fun fdmaCarrierComesFromTheSelectedOrbitSignalAndIsUnknownWithoutAChannel() {
        val glonass = orbit.copy(constellation = Constellation.GLONASS, hasEphemeris = true)
        assertNull(receiver.observe(glonass, request, GnssSimulationEnvironment.OPEN_SKY)!!.satellite.carrierHz)
        val frequency = 1_602_000_000f + -7 * 562_500f
        assertEquals(frequency, receiver.observe(glonass.copy(carrierHz = frequency), request,
            GnssSimulationEnvironment.OPEN_SKY)!!.satellite.carrierHz)
        assertThrows(IllegalArgumentException::class.java) { glonass.copy(carrierHz = Float.NaN) }
    }

    @Test fun navigationKnowledgeAndSignalAreIndependentRequirements() {
        val synthetic = receiver.observe(virtualOrbit, request, GnssSimulationEnvironment.OPEN_SKY, navigation())!!
        assertTrue(synthetic.satellite.usedInFix)
        assertTrue(synthetic.simulatedNavigation)
        val indoors = receiver.observe(virtualOrbit, request, GnssSimulationEnvironment.INDOOR, navigation())!!
        assertEquals(synthetic.satellite.elevationDeg, indoors.satellite.elevationDeg, 0f)
        assertTrue(indoors.satellite.hasEphemeris)
        assertFalse(indoors.tracked)
        assertFalse(indoors.satellite.usedInFix)
        val physical = receiver.observe(orbit.copy(hasEphemeris = true), request, GnssSimulationEnvironment.OPEN_SKY)!!
        assertTrue(physical.satellite.usedInFix)
        assertFalse(physical.simulatedNavigation)
    }

    @Test fun rejectsWrongEpochNavigationAndNeverLiftsBelowHorizonOrbit() {
        assertThrows(IllegalArgumentException::class.java) {
            receiver.observe(virtualOrbit, request, GnssSimulationEnvironment.OPEN_SKY,
                navigation(request.atMillis + 1))
        }
        assertThrows(IllegalArgumentException::class.java) {
            receiver.observe(orbit, request, GnssSimulationEnvironment.OPEN_SKY, navigation())
        }
        val model = navigation()
        val next = request.copy(atMillis = request.atMillis + 1000)
        val position = requireNotNull(model.positionAt(next))
        assertNotEquals(virtualOrbit.position, position)
        assertTrue(receiver.observe(virtualOrbit.copy(position = position), next,
            GnssSimulationEnvironment.OPEN_SKY, model)!!.satellite.usedInFix)
        assertNull(model.positionAt(next.copy(atMillis = next.atMillis + 1)))
        assertThrows(IllegalArgumentException::class.java) {
            receiver.observe(virtualOrbit, next, GnssSimulationEnvironment.OPEN_SKY, model)
        }
        assertNull(receiver.observe(orbit.copy(position = EcefPosition(-26_560_000.0, 0.0, 0.0)),
            request, GnssSimulationEnvironment.OPEN_SKY))
    }

    @Test fun signalChangesContinuouslyAndDoesNotDependOnMutableRandomState() {
        val before = receiver.observe(orbit, request, GnssSimulationEnvironment.OPEN_SKY)!!
        val after = receiver.observe(orbit, request.copy(atMillis = request.atMillis + 1000),
            GnssSimulationEnvironment.OPEN_SKY)!!
        assertTrue(kotlin.math.abs(before.satellite.cn0DbHz - after.satellite.cn0DbHz) < 0.2f)
        assertEquals(before, receiver.observe(orbit, request, GnssSimulationEnvironment.OPEN_SKY))
        assertEquals(before, GnssSimulatedReceiver(123).observe(orbit, request, GnssSimulationEnvironment.OPEN_SKY))
    }
}
