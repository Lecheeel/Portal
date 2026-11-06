package com.system.location.service.core.environment

import com.system.location.service.core.geo.Wgs84
import kotlin.math.cos
import kotlin.math.floor

data class SimProfile(
    val mcc: Int = 460,
    val mnc: Int = 0,
    val simReady: Boolean = false,
    val operatorName: String? = null,
) {
    init {
        require(mcc in 1..999) { "mcc out of range: $mcc" }
        require(mnc in 0..999) { "mnc out of range: $mnc" }
    }
}

/**
 * Deterministic cell profile (P2-05, ADR-005).
 *
 * FULL_REPLAY returns one registered LTE cell plus LTE neighbours and one NR cell, all under the
 * supplied MCC/MNC. NO_SIM_WIFI_ONLY returns no cells (the device profile has no SIM). LEGACY_CDMA
 * is deprecated and currently yields no cells; the hook keeps its old path behind a feature flag.
 */
class ProceduralCellSource(
    private val config: EnvironmentConfig = EnvironmentConfig(),
) {
    fun cellsAt(point: Wgs84, seed: Long, sim: SimProfile): List<CellTower> {
        val mode = CellModePolicy.resolve(config.cellMode, sim.simReady)
        return when (mode) {
            CellMode.NO_SIM_WIFI_ONLY, CellMode.LEGACY_CDMA -> emptyList()
            CellMode.AUTO, CellMode.FULL_REPLAY -> replayCells(point, seed, sim)
        }
    }

    private fun replayCells(point: Wgs84, seed: Long, sim: SimProfile): List<CellTower> {
        val latStep = 0.005
        val lonStep = latStep / cos(Math.toRadians(point.latitude)).coerceAtLeast(0.2)
        val latIndex = floor(point.latitude / latStep).toLong()
        val lonIndex = floor(point.longitude / lonStep).toLong()
        val random = SplitMix64(cellSeed(seed, latIndex, lonIndex))
        val areaCode = 1 + random.nextInt(65_534)
        val cells = mutableListOf<CellTower>()
        val registered = CellTower(
            type = CellType.LTE,
            mcc = sim.mcc,
            mnc = sim.mnc,
            areaCode = areaCode,
            cellId = 1L + random.nextInt(268_435_454),
            pci = random.nextInt(1_008),
            arfcn = 1_200 + random.nextInt(300),
            rsrp = -78 - random.nextInt(24),
            rsrq = -8 - random.nextInt(8),
            rssnr = 8 + random.nextInt(12),
            registered = true,
        )
        cells += registered
        repeat(NEIGHBOURS) { index ->
            cells += CellTower(
                type = CellType.LTE,
                mcc = sim.mcc,
                mnc = sim.mnc,
                areaCode = areaCode,
                cellId = 1L + random.nextInt(268_435_454),
                pci = random.nextInt(1_008),
                arfcn = registered.arfcn!! + (index + 1) * 2,
                rsrp = -88 - random.nextInt(20),
                rsrq = -10 - random.nextInt(8),
                rssnr = 2 + random.nextInt(10),
                registered = false,
            )
        }
        cells += CellTower(
            type = CellType.NR,
            mcc = sim.mcc,
            mnc = sim.mnc,
            areaCode = areaCode,
            cellId = 1L + random.nextInt(1_000_000),
            pci = random.nextInt(1_008),
            arfcn = 620_000 + random.nextInt(10_000),
            rsrp = -92 - random.nextInt(20),
            rsrq = -12 - random.nextInt(6),
            rssnr = 4 + random.nextInt(10),
            registered = false,
        )
        return cells
    }

    private fun cellSeed(seed: Long, latIndex: Long, lonIndex: Long): Long {
        var hash = seed
        hash = hash * 31 + latIndex
        hash = hash * 31 + lonIndex
        hash = hash xor (hash ushr 33)
        return hash * -0x61c8864680b583ebL
    }

    private class SplitMix64(seed: Long) {
        private var state = seed

        fun nextLong(): Long {
            state += -0x61c8864680b583ebL
            var z = state
            z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L
            z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L
            return z xor (z ushr 31)
        }

        fun nextInt(bound: Int): Int = ((nextLong() ushr 1) % bound).toInt()
    }

    private companion object {
        const val NEIGHBOURS = 3
    }
}
