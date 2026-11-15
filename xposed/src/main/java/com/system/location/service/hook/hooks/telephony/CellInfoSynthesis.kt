package com.system.location.service.hook.hooks.telephony

import android.telephony.CellIdentityLte
import android.telephony.CellIdentityNr
import android.telephony.CellIdentityCdma
import android.telephony.CellIdentityGsm
import android.telephony.CellIdentityWcdma
import android.telephony.CellInfo
import android.telephony.CellInfoCdma
import android.telephony.CellInfoGsm
import android.telephony.CellInfoLte
import android.telephony.CellInfoNr
import android.telephony.CellInfoWcdma
import android.telephony.CellSignalStrengthCdma
import android.telephony.CellSignalStrengthGsm
import android.telephony.CellSignalStrengthLte
import android.telephony.CellSignalStrengthNr
import android.telephony.CellSignalStrengthWcdma
import android.os.SystemClock
import com.system.location.service.hook.runtime.Reflection
import com.system.location.service.core.environment.CellMode
import com.system.location.service.core.environment.CellType
import com.system.location.service.core.environment.CdmaTower
import com.system.location.service.core.environment.EnvironmentProfile
import com.system.location.service.core.environment.EnvironmentReplayPolicy
import com.system.location.service.core.environment.EnvironmentSourceMode
import com.system.location.service.core.geo.Wgs84
import com.system.location.service.core.routing.RoutingDecision
import com.system.location.service.core.runtime.RuntimeSnapshot
import com.system.location.service.hook.utils.BinderUtils
import com.system.location.service.hook.utils.FakeLoc
import com.system.location.service.hook.utils.Logger
import io.github.libxposed.api.XposedInterface.Hooker
import java.util.concurrent.ConcurrentHashMap

/** Replaces protected getAllCellInfo results with modeled records when construction succeeds. */
object CellInfoSynthesis {
    private val reportedUnavailable = ConcurrentHashMap.newKeySet<String>()

    internal fun resultForCaller(packageName: String, uid: Int): CellInfoSynthesisResult {
        val state = FakeLoc.runtimeSnapshots.current()
        if (!state.configuration.enabled) return CellInfoSynthesisResult.PassThrough
        val route = com.system.location.service.hook.hooks.ListenerRouting.decisionFor(packageName, uid, state.routing)
        if (route == RoutingDecision.Deny) return CellInfoSynthesisResult.PassThrough
        if (state.cellMode == CellMode.NO_SIM_WIFI_ONLY) return CellInfoSynthesisResult.Unavailable
        val point = pointForRoute(route, state) ?: return CellInfoSynthesisResult.Unavailable
        val replay = capturedProfile(state, point)
        val hasCapturedCells = !replay?.cells.isNullOrEmpty() || !replay?.cdmaCells.isNullOrEmpty()
        if (!state.simReady && !hasCapturedCells) return CellInfoSynthesisResult.Unavailable
        val cells = runCatching { build(state.environmentSourceMode, replay) }.getOrDefault(emptyList())
        return cells.takeIf { it.isNotEmpty() }
            ?.let(CellInfoSynthesisResult::Synthetic)
            ?: CellInfoSynthesisResult.Unavailable
    }

    fun hook(cPhoneInterfaceManager: Class<*>) {
        val method = cPhoneInterfaceManager.declaredMethods.find { it.name == "getAllCellInfo" } ?: return
        val hook = Hooker { chain ->
            val state = FakeLoc.runtimeSnapshots.current()
            // Run the framework path first to preserve its permission and exception semantics.
            val original = chain.proceed()
            if (!state.configuration.enabled || BinderUtils.isSystemAppsCall()) return@Hooker original
            val route = com.system.location.service.hook.hooks.ListenerRouting
                .decisionFor(chain.args.firstOrNull() as? String, BinderUtils.getCallerUid(), state.routing)
            if (route == RoutingDecision.Deny) return@Hooker original
            if (original !is List<*>) return@Hooker unavailable(original, "unexpected_framework_result")
            if (state.cellMode == CellMode.NO_SIM_WIFI_ONLY) {
                return@Hooker unavailable(original, "sim_or_cell_mode_unavailable")
            }
            val point = pointForRoute(route, state)
                ?: return@Hooker unavailable(original, "caller_route_denied")
            val replay = capturedProfile(state, point)
            val hasCapturedCells = !replay?.cells.isNullOrEmpty() || !replay?.cdmaCells.isNullOrEmpty()
            if (!state.simReady && !hasCapturedCells) {
                return@Hooker unavailable(original, "virtual_sim_not_ready")
            }
            val cells = runCatching { build(state.environmentSourceMode, replay) }.getOrDefault(emptyList())
            if (cells.isEmpty()) return@Hooker unavailable(original, "target_cell_profile_unavailable")
            if (FakeLoc.enableDebugLog) Logger.debug("getAllCellInfo: ${cells.size} synthetic cells")
            selectCellInfoResult(protectedCall = true, frameworkResult = original, synthetic = cells)
        }
        com.system.location.service.hook.scope.HookInstaller.hookMethod(method, hook)
    }

    private fun unavailable(original: Any?, reason: String): Any? {
        if (reportedUnavailable.add(reason)) {
            Logger.warn("getAllCellInfo: simulation unavailable ($reason); framework result suppressed")
        }
        return selectCellInfoResult<CellInfo>(protectedCall = true, frameworkResult = original, synthetic = emptyList())
    }

    internal fun profileForRoute(state: RuntimeSnapshot, route: RoutingDecision): EnvironmentProfile? {
        val point = pointForRoute(route, state) ?: return null
        return capturedProfile(state, point)
    }

    private fun capturedProfile(state: RuntimeSnapshot, point: Wgs84) =
        if (state.environmentSourceMode == com.system.location.service.core.environment.EnvironmentSourceMode.PROCEDURAL) {
            null
        } else {
            state.environment?.takeIf { EnvironmentReplayPolicy.isApplicable(it, point) }
        }

    private fun pointForRoute(route: RoutingDecision, state: RuntimeSnapshot): Wgs84? = when (route) {
        RoutingDecision.Deny -> null
        is RoutingDecision.Point -> route.point
        is RoutingDecision.Scenario -> FakeLoc.scenarioSamples
            .latest(route.scenarioId, SystemClock.elapsedRealtimeNanos())?.sample
            ?.let { Wgs84(it.latitude, it.longitude) }
            ?: Wgs84(state.latitude, state.longitude)
        RoutingDecision.Global -> Wgs84(state.latitude, state.longitude)
    }

    private fun build(
        sourceMode: EnvironmentSourceMode,
        replay: com.system.location.service.core.environment.EnvironmentProfile?,
    ): List<CellInfo> {
        val records = selectExplicitCellRecords(sourceMode, replay) ?: return emptyList()
        val towers = records.cells
        val standardCells = buildAllOrNothing(towers) { tower ->
            when (tower.type) {
                CellType.GSM -> buildGsm(tower)
                CellType.WCDMA -> buildWcdma(tower)
                CellType.LTE -> buildLte(tower)
                CellType.NR -> buildNr(tower)
            }
        }
        if (towers.isNotEmpty() && standardCells.isEmpty()) return emptyList()
        val cdmaTowers = records.cdmaCells
        val cdmaCells = buildAllOrNothing(cdmaTowers) { tower -> buildCdma(tower) }
        if (cdmaTowers.isNotEmpty() && cdmaCells.isEmpty()) return emptyList()
        return standardCells + cdmaCells
    }

    private fun buildGsm(tower: com.system.location.service.core.environment.CellTower): CellInfo? =
        runCatching {
            val mncDigits = tower.mncDigits ?: return null
            val identity = Reflection.newInstance(CellIdentityGsm::class.java) as CellIdentityGsm
            Reflection.setIntField(identity, "mLac", tower.areaCode)
            Reflection.setIntField(identity, "mCid", tower.cellId.toInt())
            Reflection.setIntField(identity, "mArfcn", tower.arfcn ?: Int.MAX_VALUE)
            Reflection.setIntField(identity, "mBsic", tower.pci ?: Int.MAX_VALUE)
            Reflection.setObjectField(identity, "mMccStr", tower.mcc.toString().padStart(3, '0'))
            Reflection.setObjectField(identity, "mMncStr", tower.mnc.toString().padStart(mncDigits, '0'))
            val signal = Reflection.newInstance(CellSignalStrengthGsm::class.java) as CellSignalStrengthGsm
            Reflection.setIntField(signal, "mRssi", tower.rsrp)
            Reflection.setIntField(signal, "mBitErrorRate", Int.MAX_VALUE)
            Reflection.setIntField(signal, "mTimingAdvance", Int.MAX_VALUE)
            Reflection.setIntField(signal, "mLevel", gsmLevel(tower.rsrp))
            val cell = Reflection.newInstance(CellInfoGsm::class.java) as CellInfoGsm
            Reflection.setObjectField(cell, "mCellIdentityGsm", identity)
            Reflection.setObjectField(cell, "mCellSignalStrengthGsm", signal)
            Reflection.callMethod(cell, "setRegistered", tower.registered)
            Reflection.callMethod(cell, "setTimeStamp", SystemClock.elapsedRealtimeNanos())
            cell
        }.getOrNull()

    private fun buildWcdma(tower: com.system.location.service.core.environment.CellTower): CellInfo? =
        runCatching {
            val mncDigits = tower.mncDigits ?: return null
            val identity = Reflection.newInstance(CellIdentityWcdma::class.java) as CellIdentityWcdma
            Reflection.setIntField(identity, "mLac", tower.areaCode)
            Reflection.setIntField(identity, "mCid", tower.cellId.toInt())
            Reflection.setIntField(identity, "mPsc", tower.pci ?: Int.MAX_VALUE)
            Reflection.setIntField(identity, "mUarfcn", tower.arfcn ?: Int.MAX_VALUE)
            Reflection.setObjectField(identity, "mMccStr", tower.mcc.toString().padStart(3, '0'))
            Reflection.setObjectField(identity, "mMncStr", tower.mnc.toString().padStart(mncDigits, '0'))
            val signal = Reflection.newInstance(CellSignalStrengthWcdma::class.java) as CellSignalStrengthWcdma
            Reflection.setIntField(signal, "mRssi", Int.MAX_VALUE)
            Reflection.setIntField(signal, "mBitErrorRate", Int.MAX_VALUE)
            Reflection.setIntField(signal, "mRscp", tower.rsrp)
            Reflection.setIntField(signal, "mEcNo", tower.rssnr ?: Int.MAX_VALUE)
            Reflection.setIntField(signal, "mLevel", wcdmaLevel(tower.rsrp))
            val cell = Reflection.newInstance(CellInfoWcdma::class.java) as CellInfoWcdma
            Reflection.setObjectField(cell, "mCellIdentityWcdma", identity)
            Reflection.setObjectField(cell, "mCellSignalStrengthWcdma", signal)
            Reflection.callMethod(cell, "setRegistered", tower.registered)
            Reflection.callMethod(cell, "setTimeStamp", SystemClock.elapsedRealtimeNanos())
            cell
        }.getOrNull()

    private fun buildCdma(tower: CdmaTower): CellInfo? = runCatching {
        if (tower.cdmaDbm == null && tower.evdoDbm == null) return null
        val identity = Reflection.newInstance(
            CellIdentityCdma::class.java,
            tower.networkId,
            tower.systemId,
            tower.baseStationId,
            tower.latitudeQuarterSeconds ?: Int.MAX_VALUE,
            tower.longitudeQuarterSeconds ?: Int.MAX_VALUE,
            null,
            null,
        ) as CellIdentityCdma
        val signal = Reflection.newInstance(
            CellSignalStrengthCdma::class.java,
            tower.cdmaDbm ?: Int.MAX_VALUE,
            tower.cdmaEcio ?: Int.MAX_VALUE,
            tower.evdoDbm ?: Int.MAX_VALUE,
            tower.evdoEcio ?: Int.MAX_VALUE,
            tower.evdoSnr ?: Int.MAX_VALUE,
        ) as CellSignalStrengthCdma
        val cell = Reflection.newInstance(CellInfoCdma::class.java) as CellInfoCdma
        Reflection.setObjectField(cell, "mCellIdentity", identity)
        Reflection.setObjectField(cell, "mCellSignalStrength", signal)
        Reflection.callMethod(cell, "setRegistered", tower.registered)
        Reflection.callMethod(cell, "setTimeStamp", SystemClock.elapsedRealtimeNanos())
        Reflection.callMethod(cell, "setCellConnectionStatus", if (tower.registered) 1 else 0)
        cell
    }.getOrNull()

    private fun buildLte(tower: com.system.location.service.core.environment.CellTower): CellInfo? =
        runCatching {
            val mncDigits = tower.mncDigits ?: return null
            val identity = Reflection.newInstance(
                CellIdentityLte::class.java,
                tower.mcc.toString().padStart(3, '0'), tower.mnc.toString().padStart(mncDigits, '0'),
                tower.cellId.toInt(), tower.pci ?: 0, tower.areaCode, tower.arfcn ?: 0,
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
            val mncDigits = tower.mncDigits ?: return null
            val identity = Reflection.newInstance(
                CellIdentityNr::class.java,
                tower.mcc.toString().padStart(3, '0'), tower.mnc.toString().padStart(mncDigits, '0'),
                tower.cellId, tower.pci ?: 0, tower.areaCode, tower.arfcn ?: 0,
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
    private fun gsmLevel(rssi: Int): Int = when {
        rssi >= -89 -> 4
        rssi >= -97 -> 3
        rssi >= -103 -> 2
        rssi >= -109 -> 1
        else -> 0
    }

    private fun wcdmaLevel(rscp: Int): Int = when {
        rscp >= -75 -> 4
        rscp >= -85 -> 3
        rscp >= -95 -> 2
        rscp >= -105 -> 1
        else -> 0
    }

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

internal data class ExplicitCellRecords(
    val cells: List<com.system.location.service.core.environment.CellTower>,
    val cdmaCells: List<CdmaTower>,
)

internal sealed interface CellInfoSynthesisResult {
    data object PassThrough : CellInfoSynthesisResult
    data object Unavailable : CellInfoSynthesisResult
    data class Synthetic(val cells: List<CellInfo>) : CellInfoSynthesisResult
}

internal fun selectExplicitCellRecords(
    sourceMode: EnvironmentSourceMode,
    profile: EnvironmentProfile?,
): ExplicitCellRecords? {
    if (sourceMode == EnvironmentSourceMode.PROCEDURAL || profile == null) return null
    if (profile.cells.isEmpty() && profile.cdmaCells.isEmpty()) return null
    return ExplicitCellRecords(profile.cells, profile.cdmaCells)
}

internal fun <T> selectCellInfoResult(
    protectedCall: Boolean,
    frameworkResult: Any?,
    synthetic: List<T>,
): Any? = if (protectedCall) synthetic else frameworkResult

internal fun <I, O : Any> buildAllOrNothing(inputs: List<I>, build: (I) -> O?): List<O> {
    if (inputs.isEmpty()) return emptyList()
    val results = inputs.map(build)
    return if (results.all { it != null }) results.filterNotNull() else emptyList()
}
