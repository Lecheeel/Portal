package com.system.location.service.core.carrier

import com.system.location.service.core.environment.CellTower
import com.system.location.service.core.environment.CellType
import com.system.location.service.core.geo.Wgs84
import kotlin.math.floor

/**
 * Offline NR SA generator. LTE references supply only relative signal ordering and cell count;
 * their CI, TAC, PCI and EARFCN are never reinterpreted as NR fields. Stable geographic tiles and
 * SIM identity seed a separate synthetic gNB/cell model. This is modeled data, not a tower lookup.
 */
object CarrierNrCellSource {
    fun cellsAt(point: Wgs84, seed: Long, profile: SimCarrierProfile,
        references: List<CellTower> = emptyList()): List<CellTower> {
        require(profile.radio == CarrierRadio.NR)
        val candidates = references.filter { it.type == CellType.LTE }
            .sortedByDescending { it.rsrp }.take(5)
        val count = if (candidates.isEmpty()) 4 else (candidates.size + 1).coerceIn(2, 6)
        var base = seed
        listOf(floor(point.latitude * 200).toLong(), floor(point.longitude * 200).toLong(),
            profile.mcc.toLong(), profile.mnc.toLong(), profile.slot.toLong()).forEach {
            base = mix(base xor it)
        }
        val area = 1 + positive(base) % 16777214
        val pciStart = (positive(mix(base)) % 1008).toInt()
        return (0 until count).map { index ->
            val hash = mix(base + index * 104729L)
            // A fixed 28-bit gNB component and 8-bit local cell ID form a 36-bit NCI.
            val gnb = 1L + positive(hash) % 268435454L
            val nci = (gnb shl 8) or (index + 1).toLong()
            val referenceSignal = candidates.getOrNull(index)?.rsrp
            val signal = if (index == 0) (referenceSignal ?: -85).coerceIn(-100, -65)
                else minOf(referenceSignal ?: (-95 - index * 3), -90 - index * 2).coerceAtLeast(-130)
            CellTower(type = CellType.NR, mcc = profile.mcc, mnc = profile.mnc,
                areaCode = area.toInt(), cellId = nci,
                pci = (pciStart + index * 37) % 1008,
                arfcn = profile.nrArfcn, rsrp = signal, rsrq = -10 - index,
                rssnr = 22 - index * 3, registered = index == 0,
                mncDigits = profile.mncDigits, operatorName = profile.operatorName,
                bands = listOf(profile.nrBand))
        }
    }

    private fun positive(value: Long): Long = value and Long.MAX_VALUE
    private fun mix(value: Long): Long {
        var v = value + -7046029254386353131L
        v = (v xor (v ushr 30)) * -4658895280553007687L
        v = (v xor (v ushr 27)) * -7723592293110705685L
        return v xor (v ushr 31)
    }
}
