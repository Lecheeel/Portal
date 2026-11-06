package com.system.location.service.core

import com.system.location.service.core.environment.CellMode
import com.system.location.service.core.environment.CellType
import com.system.location.service.core.environment.EnvironmentConfig
import com.system.location.service.core.environment.ProceduralCellSource
import com.system.location.service.core.environment.SimProfile
import com.system.location.service.core.geo.Wgs84
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProceduralCellSourceTest {
    private val point = Wgs84(31.2304, 121.4737)
    private val sim = SimProfile(mcc = 460, mnc = 0, simReady = true)

    @Test
    fun fullReplayHasOneRegisteredLteAndNeighbours() {
        val source = ProceduralCellSource(EnvironmentConfig(cellMode = CellMode.FULL_REPLAY))
        val cells = source.cellsAt(point, seed = 1, sim = sim)
        val registered = cells.filter { it.registered }
        assertEquals(1, registered.size)
        assertEquals(CellType.LTE, registered.single().type)
        assertEquals(1, cells.count { it.type == CellType.NR })
        assertTrue(cells.size >= 4)
        cells.forEach {
            assertEquals(460, it.mcc)
            assertEquals(0, it.mnc)
            assertTrue(it.areaCode > 0)
            assertTrue(it.cellId > 0)
            assertTrue(it.rsrp in -156..-31)
        }
    }

    @Test
    fun autoWithoutSimProducesNoCells() {
        val source = ProceduralCellSource(EnvironmentConfig(cellMode = CellMode.AUTO))
        assertTrue(source.cellsAt(point, seed = 1, sim = SimProfile(simReady = false)).isEmpty())
    }

    @Test
    fun autoWithSimUsesFullReplay() {
        val source = ProceduralCellSource(EnvironmentConfig(cellMode = CellMode.AUTO))
        val cells = source.cellsAt(point, seed = 1, sim = sim)
        assertTrue(cells.any { it.registered })
    }

    @Test
    fun legacyCdmaProducesNothing() {
        val source = ProceduralCellSource(EnvironmentConfig(cellMode = CellMode.LEGACY_CDMA))
        assertTrue(source.cellsAt(point, seed = 1, sim = sim).isEmpty())
    }

    @Test
    fun samePointAndSeedIsDeterministic() {
        val source = ProceduralCellSource()
        assertEquals(source.cellsAt(point, 7, sim), source.cellsAt(point, 7, sim))
        assertNotEquals(source.cellsAt(point, 7, sim), source.cellsAt(point, 8, sim))
    }

    @Test
    fun neighboursDifferFromRegisteredCell() {
        val cells = ProceduralCellSource().cellsAt(point, 2, sim)
        val ids = cells.map { it.cellId }
        assertEquals(ids.size, ids.distinct().size)
        val pcIs = cells.mapNotNull { it.pci }
        assertEquals(pcIs.size, pcIs.distinct().size)
    }

    @Test
    fun simProfileIsValidated() {
        assertThrows { SimProfile(mcc = 0) }
        assertThrows { SimProfile(mnc = 1000) }
    }

    private fun assertThrows(block: () -> Unit) {
        try {
            block()
        } catch (_: IllegalArgumentException) {
            return
        }
        throw AssertionError("Expected IllegalArgumentException")
    }
}
