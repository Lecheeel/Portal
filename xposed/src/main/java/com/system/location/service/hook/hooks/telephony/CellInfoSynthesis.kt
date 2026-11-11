package com.system.location.service.hook.hooks.telephony

import android.telephony.CellIdentityLte
import android.telephony.CellIdentityNr
import android.telephony.CellInfo
import android.telephony.CellInfoLte
import android.telephony.CellInfoNr
import android.telephony.CellSignalStrengthLte
import android.telephony.CellSignalStrengthNr
import android.os.SystemClock
import com.system.location.service.hook.runtime.Reflection
import com.system.location.service.core.environment.CellMode
import com.system.location.service.core.environment.CellType
import com.system.location.service.core.environment.EnvironmentConfig
import com.system.location.service.core.environment.ProceduralCellSource
import com.system.location.service.core.environment.SimProfile
import com.system.location.service.core.geo.Wgs84
import com.system.location.service.hook.utils.BinderUtils
import com.system.location.service.hook.utils.FakeLoc
import com.system.location.service.hook.utils.Logger
import io.github.libxposed.api.XposedInterface.Hooker

/**
 * P2-05: replace third-party getAllCellInfo with a deterministic LTE profile. Construction is
 * best-effort because CellIdentityLte/CellInfoLte are hidden; on a missing constructor or field the
 * original list is left untouched rather than returning an empty/broken result.
 */
object CellInfoSynthesis {
    fun hook(cPhoneInterfaceManager: Class<*>) {
        val method = cPhoneInterfaceManager.declaredMethods.find { it.name == "getAllCellInfo" } ?: return
        val hook = Hooker { chain ->
            // The framework path owns the caller permission and the real radio/no-cell state.
            val original = chain.proceed()
            if (!FakeLoc.enable || BinderUtils.isSystemAppsCall()) return@Hooker original
            if (original !is List<*> || original.isEmpty()) return@Hooker original
            if (!FakeLoc.simReady || FakeLoc.cellMode == CellMode.NO_SIM_WIFI_ONLY) {
                return@Hooker emptyList<CellInfo>()
            }
            // Never invent a China Mobile SIM on a device whose own modem reports no SIM.
            val sim = TelephonyFacade.sim()
            if (!sim.ready) return@Hooker original
            val point = com.system.location.service.hook.hooks.ListenerRouting.fixedPointFor(chain.args.firstOrNull() as? String)
            val cells = runCatching { build(point, sim) }.getOrDefault(emptyList())
            if (cells.isNotEmpty()) {
                if (FakeLoc.enableDebugLog) Logger.debug("getAllCellInfo: ${cells.size} synthetic LTE cells")
                cells
            } else {
                original
            }
        }
        com.system.location.service.hook.scope.HookInstaller.hookMethod(method, hook)
    }

    private fun build(
        fixedPoint: com.system.location.service.core.geo.Wgs84? = null,
        sim: TelephonyFacade.Sim,
    ): List<CellInfo> {
        val captured = if (FakeLoc.environmentSourceMode == com.system.location.service.core.environment.EnvironmentSourceMode.PROCEDURAL) {
            null
        } else {
            FakeLoc.replayEnvironment?.cells?.takeIf { it.isNotEmpty() }
        }
        val towers = captured ?: ProceduralCellSource(EnvironmentConfig(cellMode = FakeLoc.cellMode)).cellsAt(
            fixedPoint ?: Wgs84(FakeLoc.latitude, FakeLoc.longitude),
            FakeLoc.environmentSeed,
            SimProfile(
                mcc = sim.mcc,
                mnc = sim.mnc,
                simReady = true,
                operatorName = sim.name.takeIf { it.isNotBlank() },
            ),
        )
        return towers.mapNotNull { tower ->
            when (tower.type) {
                CellType.LTE -> buildLte(tower)
                CellType.NR -> buildNr(tower)
                else -> null
            }
        }
    }

    private fun buildLte(tower: com.system.location.service.core.environment.CellTower): CellInfo? =
        runCatching {
            val identity = Reflection.newInstance(
                CellIdentityLte::class.java,
                tower.mcc, tower.mnc, tower.cellId.toInt(), tower.pci ?: 0, tower.areaCode, tower.arfcn ?: 0,
            ) as CellIdentityLte
            val signal = Reflection.newInstance(CellSignalStrengthLte::class.java) as CellSignalStrengthLte
            Reflection.setIntField(signal, "mRsrp", tower.rsrp)
            Reflection.setIntField(signal, "mRsrq", tower.rsrq ?: -10)
            Reflection.setIntField(signal, "mRssnr", tower.rssnr ?: 5)
            Reflection.setIntField(signal, "mRssi", (tower.rsrp + 25).coerceIn(-113, -51))
            Reflection.setIntField(signal, "mLevel", lteLevel(tower.rsrp))
            val cell = Reflection.newInstance(CellInfoLte::class.java) as CellInfoLte
            Reflection.setObjectField(cell, "mCellIdentityLte", identity)
            Reflection.setObjectField(cell, "mCellSignalStrengthLte", signal)
            Reflection.callMethod(cell, "setRegistered", tower.registered)
            Reflection.callMethod(cell, "setTimeStamp", SystemClock.elapsedRealtimeNanos())
            cell
        }.getOrNull()

    private fun buildNr(tower: com.system.location.service.core.environment.CellTower): CellInfo? =
        runCatching {
            val identity = Reflection.newInstance(
                CellIdentityNr::class.java,
                tower.mcc, tower.mnc, tower.cellId, tower.pci ?: 0, tower.areaCode, tower.arfcn ?: 0,
            ) as CellIdentityNr
            val signal = Reflection.newInstance(CellSignalStrengthNr::class.java) as CellSignalStrengthNr
            Reflection.setIntField(signal, "mSsRsrp", tower.rsrp)
            Reflection.setIntField(signal, "mSsRsrq", tower.rsrq ?: -12)
            Reflection.setIntField(signal, "mSsSinr", tower.rssnr ?: 2)
            Reflection.setIntField(signal, "mLevel", nrLevel(tower.rsrp))
            val cell = Reflection.newInstance(CellInfoNr::class.java) as CellInfoNr
            Reflection.setObjectField(cell, "mCellIdentity", identity)
            Reflection.setObjectField(cell, "mCellSignalStrength", signal)
            Reflection.callMethod(cell, "setRegistered", tower.registered)
            Reflection.callMethod(cell, "setTimeStamp", SystemClock.elapsedRealtimeNanos())
            cell
        }.getOrNull()

    /** 3GPP TS 36.133 RSRP thresholds, so level cannot contradict the reported RSRP. */
    private fun lteLevel(rsrp: Int): Int = when {
        rsrp >= -85 -> 4
        rsrp >= -95 -> 3
        rsrp >= -105 -> 2
        rsrp >= -115 -> 1
        else -> 0
    }

    private fun nrLevel(rsrp: Int): Int = when {
        rsrp >= -80 -> 4
        rsrp >= -90 -> 3
        rsrp >= -100 -> 2
        rsrp >= -110 -> 1
        else -> 0
    }
}
