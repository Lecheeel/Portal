package com.system.location.service.core.carrier

import com.system.location.service.core.environment.CellTower
import com.system.location.service.core.environment.CellType

/** Bind explicit captured cells to an identity; never convert an LTE channel/CI into invented NR. */
object CarrierCellIdentity {
    fun overlay(cells: List<CellTower>, profile: SimCarrierProfile): List<CellTower> {
        val type = if (profile.radio == CarrierRadio.NR) CellType.NR else CellType.LTE
        val matching = cells.filter { it.type == type }
        if (matching.isEmpty()) return emptyList()
        // One serving cell for this selected subscription. Keep captured serving choice when
        // available; otherwise the strongest eligible record is the modeled serving candidate.
        val serving = matching.indexOfFirst { it.registered }.takeIf { it >= 0 }
            ?: matching.indices.maxBy { matching[it].rsrp }
        return matching.mapIndexed { index, cell -> cell.copy(
            mcc = profile.mcc,
            mnc = profile.mnc,
            mncDigits = profile.mncDigits,
            operatorName = profile.operatorName,
            registered = index == serving,
        ) }
    }
}
