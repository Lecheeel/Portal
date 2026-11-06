package com.system.location.service.hook.hooks.telephony

import android.telephony.CellIdentityLte
import android.telephony.CellInfo
import android.telephony.CellInfoLte
import android.telephony.CellSignalStrengthLte
import de.robv.android.xposed.XposedHelpers
import com.system.location.service.core.environment.CellMode
import com.system.location.service.core.environment.CellType
import com.system.location.service.core.environment.EnvironmentConfig
import com.system.location.service.core.environment.ProceduralCellSource
import com.system.location.service.core.environment.SimProfile
import com.system.location.service.core.geo.Wgs84
import com.system.location.service.hook.utils.BinderUtils
import com.system.location.service.hook.utils.FakeLoc
import com.system.location.service.hook.utils.Logger
import com.system.location.service.hook.utils.afterHook

/**
 * P2-05: replace third-party getAllCellInfo with a deterministic LTE profile. Construction is
 * best-effort because CellIdentityLte/CellInfoLte are hidden; on a missing constructor or field the
 * original list is left untouched rather than returning an empty/broken result.
 */
object CellInfoSynthesis {
    fun hook(cPhoneInterfaceManager: Class<*>) {
        val method = cPhoneInterfaceManager.declaredMethods.find { it.name == "getAllCellInfo" } ?: return
        val hook = afterHook {
            if (!FakeLoc.enable || BinderUtils.isSystemAppsCall()) return@afterHook
            val cells = runCatching { build() }.getOrDefault(emptyList())
            if (cells.isNotEmpty()) {
                result = cells
                if (FakeLoc.enableDebugLog) Logger.debug("getAllCellInfo: ${cells.size} synthetic LTE cells")
            }
        }
        if (com.system.location.service.hook.scope.HookInstaller.hookMethod(method, hook) == null) {
            Logger.error("Hook PhoneInterfaceManager.getAllCellInfo failed")
        }
    }

    private fun build(): List<CellInfo> {
        val captured = FakeLoc.replayEnvironment?.cells
            ?.filter { it.type == CellType.LTE }
            ?.takeIf { it.isNotEmpty() }
        val towers = captured ?: ProceduralCellSource(EnvironmentConfig(cellMode = CellMode.FULL_REPLAY)).cellsAt(
            Wgs84(FakeLoc.latitude, FakeLoc.longitude),
            FakeLoc.environmentSeed,
            SimProfile(mcc = 460, mnc = 0, simReady = true),
        ).filter { it.type == CellType.LTE }
        return towers.mapNotNull { tower ->
            runCatching {
                val identity = XposedHelpers.newInstance(
                    CellIdentityLte::class.java,
                    tower.mcc, tower.mnc, tower.cellId.toInt(), tower.pci ?: 0, tower.areaCode, tower.arfcn ?: 0,
                ) as CellIdentityLte
                val signal = XposedHelpers.newInstance(CellSignalStrengthLte::class.java) as CellSignalStrengthLte
                XposedHelpers.setIntField(signal, "mRsrp", tower.rsrp)
                XposedHelpers.setIntField(signal, "mRsrq", tower.rsrq ?: -10)
                XposedHelpers.setIntField(signal, "mRssnr", tower.rssnr ?: 5)
                XposedHelpers.setIntField(signal, "mRssi", tower.rsrp - 30)
                XposedHelpers.setIntField(signal, "mLevel", 3)
                val cell = XposedHelpers.newInstance(CellInfoLte::class.java) as CellInfoLte
                XposedHelpers.setObjectField(cell, "mCellIdentityLte", identity)
                XposedHelpers.setObjectField(cell, "mCellSignalStrengthLte", signal)
                XposedHelpers.callMethod(cell, "setRegistered", tower.registered)
                XposedHelpers.callMethod(cell, "setTimeStamp", System.nanoTime())
                cell
            }.getOrNull()
        }
    }
}
