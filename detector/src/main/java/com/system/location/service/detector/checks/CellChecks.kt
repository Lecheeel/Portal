package com.system.location.service.detector.checks

import android.annotation.SuppressLint
import android.Manifest
import android.content.pm.PackageManager
import android.telephony.CellIdentityNr
import android.telephony.CellInfo
import android.telephony.CellInfoCdma
import android.telephony.CellInfoGsm
import android.telephony.CellInfoLte
import android.telephony.CellInfoNr
import android.telephony.CellInfoWcdma
import android.telephony.SignalStrength
import android.telephony.TelephonyManager
import com.system.location.service.detector.model.Check
import com.system.location.service.detector.model.CheckContext
import com.system.location.service.detector.model.CheckIds
import com.system.location.service.detector.model.CheckResult

/**
 * C09 · 蜂窝/SIM/运营商/信号。
 *
 * 期望：CellInfo、SIM operator、network operator、phone type、signal、service state 自洽；
 * 不能出现 CDMA phone type + GSM/LTE SIM、或 SIM 可用但小区为空。
 */
@SuppressLint("MissingPermission")
class CellCheck : Check {
    override val id = CheckIds.CELL
    override val title = "蜂窝/SIM/运营商"
    override val blocking = true

    override fun run(context: CheckContext): CheckResult {
        val evidence = mutableListOf<String>()
        val telephony = context.context.getSystemService(TelephonyManager::class.java)
        if (telephony == null) {
            evidence += "TelephonyManager 不可用"
            return unknown("设备未提供 TelephonyManager，无法读取蜂窝信息。", evidence)
        }

        val securityFailures = mutableListOf<String>()
        val readIssues = mutableListOf<String>()

        fun <T> read(label: String, block: () -> T?): T? = try {
            block()
        } catch (error: SecurityException) {
            securityFailures += label
            null
        } catch (error: Exception) {
            readIssues += "$label=${error.javaClass.simpleName}"
            null
        }

        val phoneType = read("phoneType") { telephony.phoneType }
        val simState = read("simState") { telephony.simState }
        val simOperator = read("simOperator") { telephony.simOperator }.orEmpty().trim()
        val simOperatorName = read("simOperatorName") { telephony.simOperatorName }.orEmpty().trim()
        val networkOperator = read("networkOperator") { telephony.networkOperator }.orEmpty().trim()
        val networkOperatorName = read("networkOperatorName") { telephony.networkOperatorName }.orEmpty().trim()
        val dataNetworkType = read("dataNetworkType") { telephony.dataNetworkType }
        val voiceNetworkType = read("voiceNetworkType") { telephony.voiceNetworkType }
        val signalStrength = read("signalStrength") { telephony.signalStrength }

        var cells = context.snapshot.cellInfo
        var cellSource = "snapshot"
        var cellsUnreadable = false
        if (cells.isEmpty()) {
            val live = read("allCellInfo") { telephony.allCellInfo }
            when {
                live != null -> {
                    cells = live
                    cellSource = "实时查询"
                }
                securityFailures.contains("allCellInfo") -> {
                    cellsUnreadable = true
                    cellSource = "实时查询失败（权限）"
                }
                readIssues.any { it.startsWith("allCellInfo=") } -> {
                    cellsUnreadable = true
                    cellSource = "实时查询失败"
                }
                else -> {
                    cellsUnreadable = true
                    cellSource = "实时查询返回 null"
                }
            }
        }

        val simPresent = simState == TelephonyManager.SIM_STATE_READY || simOperator.isNotEmpty()
        val emptyCells = cells.count { isEmptyCell(it) }
        val validCells = cells.size - emptyCells
        val registeredCells = cells.count { it.isRegistered }
        val types = cells.map(::cellType)
        val hasCdma = types.any { it == "CDMA" }
        val hasLteOrNr = types.any { it == "LTE" || it == "NR" }
        val hasGsmFamily = types.any { it == "GSM" || it == "WCDMA" }
        val hasService = hasCellularService(networkOperator, dataNetworkType, voiceNetworkType)

        evidence += "phoneType=${phoneTypeName(phoneType)}"
        evidence += "simState=${simStateName(simState)}"
        evidence += "SIM：operator=${orEmptyMarker(simOperator)} name=${nameMarker(simOperatorName)}"
        evidence += "网络：operator=${orEmptyMarker(networkOperator)} name=${nameMarker(networkOperatorName)}"
        evidence += "网络类型：data=${networkTypeName(dataNetworkType)} voice=${networkTypeName(voiceNetworkType)}"
        evidence += "CellInfo：${cells.size} 个（来源：$cellSource），registered=$registeredCells"
        evidence += "类型直方图：${histogram(cells)}"
        evidence += "有效小区=$validCells，空标识小区=$emptyCells"
        evidence += "signal：${signalSummary(signalStrength)}"
        evidence += "权限：READ_PHONE_STATE=${permissionState(context, Manifest.permission.READ_PHONE_STATE)} " +
            "ACCESS_FINE_LOCATION=${permissionState(context, Manifest.permission.ACCESS_FINE_LOCATION)}"
        if (securityFailures.isNotEmpty()) evidence += "SecurityException：${securityFailures.joinToString()}"
        if (readIssues.isNotEmpty()) evidence += "读取失败：${readIssues.joinToString()}"

        val failures = mutableListOf<String>()
        if (simPresent) {
            if (!cellsUnreadable && validCells == 0 && hasService) {
                failures += "有 SIM 但 CellInfo 为空或仅空标识（Portal 当前空 CDMA 小区）"
            }
            if (phoneType == TelephonyManager.PHONE_TYPE_CDMA) {
                if (dataNetworkType != null && dataNetworkType in GSM_FAMILY_NETWORK_TYPES) {
                    failures += "phoneType=CDMA 但 dataNetworkType=${networkTypeName(dataNetworkType)}，与 GSM/LTE/NR 矛盾"
                }
                if (isClearlyGsmSimOperator(simOperator)) {
                    failures += "phoneType=CDMA 但 simOperator=$simOperator 明显是 GSM/LTE 运营商"
                }
            }
            if (phoneType == TelephonyManager.PHONE_TYPE_GSM && hasCdma) {
                failures += "phoneType=GSM 但 CellInfo 含 CDMA 小区"
            }
            if (phoneType == TelephonyManager.PHONE_TYPE_CDMA && hasGsmFamily) {
                failures += "phoneType=CDMA 但 CellInfo 含 GSM/WCDMA/TD-SCDMA 小区"
            }
            if (hasCdma && hasLteOrNr) {
                failures += "CellInfo 同时含 CDMA 与 LTE/NR 小区，自相矛盾"
            }
            if (validCells > 0) {
                val missing = buildList {
                    if (networkOperator.isEmpty()) add("networkOperator")
                    if (simOperator.isEmpty()) add("simOperator")
                }
                if (missing.isNotEmpty()) {
                    failures += "有 $validCells 个有效小区但 ${missing.joinToString("/")} 为空，信息不完整"
                }
            }
        }

        val coreReadIssue = readIssues.any { issue ->
            CORE_READ_LABELS.any { label -> issue.startsWith("$label=") }
        }

        if (failures.isNotEmpty()) {
            return fail(failures.joinToString("；"), evidence.clipped())
        }
        if (securityFailures.isNotEmpty()) {
            return unknown(
                "读取 Telephony 被 SecurityException 拒绝（${securityFailures.joinToString()}）；" +
                    "请检查 READ_PHONE_STATE 与 ACCESS_FINE_LOCATION。",
                evidence.clipped(),
            )
        }
        if (cellsUnreadable || coreReadIssue) {
            return unknown(
                "读取 Telephony 失败（${readIssues.joinToString().ifEmpty { cellSource }}），无法完成蜂窝一致性判定。",
                evidence.clipped(),
            )
        }
        if (!simPresent) {
            return unknown(
                "未检测到可用 SIM（simState=${simStateName(simState)}，simOperator=${orEmptyMarker(simOperator)}）；" +
                    "无 SIM 设备不应据此判 FAIL。",
                evidence.clipped(),
            )
        }
        if (validCells == 0) {
            return unknown(
                "有 SIM 但无小区且无服务迹象（networkOperator=${orEmptyMarker(networkOperator)}），无法判定空小区。",
                evidence.clipped(),
            )
        }
        return pass("CellInfo、SIM/运营商、phoneType 与网络类型自洽", evidence.clipped())
    }

    /** 只有 CDMA 仍可能部署的 MCC 才视为“不明显的 GSM 运营商”；其余 MCC 视为 GSM/LTE。 */
    private fun isClearlyGsmSimOperator(operatorCode: String): Boolean {
        val digits = operatorCode.trim()
        if (digits.length !in 5..6 || digits.any { !it.isDigit() }) return false
        val mcc = digits.substring(0, 3).toIntOrNull() ?: return false
        val mnc = digits.substring(3).toIntOrNull() ?: return false
        if (mcc == CHINA_MCC) return mnc !in CHINA_CDMA_MNCS
        return mcc !in CDMA_CAPABLE_MCCS
    }

    private fun isEmptyCell(cell: CellInfo): Boolean = when (cell) {
        is CellInfoCdma -> {
            val identity = cell.cellIdentity
            identity.basestationId == CellInfo.UNAVAILABLE &&
                identity.systemId == CellInfo.UNAVAILABLE &&
                identity.networkId == CellInfo.UNAVAILABLE
        }
        is CellInfoGsm -> {
            val identity = cell.cellIdentity
            identity.lac == CellInfo.UNAVAILABLE && identity.cid == CellInfo.UNAVAILABLE
        }
        is CellInfoWcdma -> {
            val identity = cell.cellIdentity
            identity.lac == CellInfo.UNAVAILABLE && identity.cid == CellInfo.UNAVAILABLE
        }
        is CellInfoLte -> {
            val identity = cell.cellIdentity
            identity.ci == CellInfo.UNAVAILABLE && identity.tac == CellInfo.UNAVAILABLE
        }
        is CellInfoNr -> {
            val identity = cell.cellIdentity as? CellIdentityNr
            identity != null && (
                identity.nci == Long.MAX_VALUE ||
                    identity.nci == CellInfo.UNAVAILABLE.toLong() ||
                    (identity.tac == CellInfo.UNAVAILABLE && identity.pci == CellInfo.UNAVAILABLE)
                )
        }
        else -> false
    }

    private fun hasCellularService(
        networkOperator: String,
        dataNetworkType: Int?,
        voiceNetworkType: Int?,
    ): Boolean {
        if (networkOperator.isNotEmpty()) return true
        val data = dataNetworkType ?: TelephonyManager.NETWORK_TYPE_UNKNOWN
        val voice = voiceNetworkType ?: TelephonyManager.NETWORK_TYPE_UNKNOWN
        val dataOnline = data != TelephonyManager.NETWORK_TYPE_UNKNOWN && data != TelephonyManager.NETWORK_TYPE_IWLAN
        val voiceOnline = voice != TelephonyManager.NETWORK_TYPE_UNKNOWN && voice != TelephonyManager.NETWORK_TYPE_IWLAN
        return dataOnline || voiceOnline
    }

    private fun histogram(cells: List<CellInfo>): String {
        if (cells.isEmpty()) return "无"
        val counts = cells.groupingBy(::cellType).eachCount().toSortedMap()
        return counts.entries.joinToString(",") { "${it.key}=${it.value}" }
    }

    /** CellInfo subclasses are public; the numeric TYPE_* constants are hidden system API. */
    private fun cellType(cell: CellInfo): String = when (cell) {
        is CellInfoCdma -> "CDMA"
        is CellInfoGsm -> "GSM"
        is CellInfoWcdma -> "WCDMA"
        is CellInfoLte -> "LTE"
        is CellInfoNr -> "NR"
        else -> "OTHER"
    }

    private fun signalSummary(signal: SignalStrength?): String {
        if (signal == null) return "<null>"
        val level = runCatching { signal.level }.getOrNull()
        val strengths = runCatching {
            signal.cellSignalStrengths.joinToString(",") { strength ->
                val name = strength.javaClass.simpleName.removePrefix("CellSignalStrength")
                "$name:${strength.level}"
            }
        }.getOrNull().orEmpty()
        return if (strengths.isEmpty()) "level=${level ?: "?"}" else "level=${level ?: "?"} [$strengths]"
    }

    private fun phoneTypeName(type: Int?): String = when (type) {
        null -> "<读取失败>"
        TelephonyManager.PHONE_TYPE_NONE -> "0(NONE)"
        TelephonyManager.PHONE_TYPE_GSM -> "1(GSM)"
        TelephonyManager.PHONE_TYPE_CDMA -> "2(CDMA)"
        TelephonyManager.PHONE_TYPE_SIP -> "3(SIP)"
        else -> "$type"
    }

    private fun simStateName(state: Int?): String = when (state) {
        null -> "<读取失败>"
        TelephonyManager.SIM_STATE_UNKNOWN -> "0(UNKNOWN)"
        TelephonyManager.SIM_STATE_ABSENT -> "1(ABSENT)"
        TelephonyManager.SIM_STATE_PIN_REQUIRED -> "2(PIN_REQUIRED)"
        TelephonyManager.SIM_STATE_PUK_REQUIRED -> "3(PUK_REQUIRED)"
        TelephonyManager.SIM_STATE_NETWORK_LOCKED -> "4(NETWORK_LOCKED)"
        TelephonyManager.SIM_STATE_READY -> "5(READY)"
        TelephonyManager.SIM_STATE_NOT_READY -> "6(NOT_READY)"
        TelephonyManager.SIM_STATE_PERM_DISABLED -> "7(PERM_DISABLED)"
        TelephonyManager.SIM_STATE_CARD_IO_ERROR -> "8(CARD_IO_ERROR)"
        TelephonyManager.SIM_STATE_CARD_RESTRICTED -> "9(CARD_RESTRICTED)"
        else -> "$state"
    }

    private fun networkTypeName(type: Int?): String =
        if (type == null) "<读取失败>" else "$type(${networkTypeLabel(type)})"

    private fun networkTypeLabel(type: Int): String = when (type) {
        TelephonyManager.NETWORK_TYPE_GPRS -> "GPRS"
        TelephonyManager.NETWORK_TYPE_EDGE -> "EDGE"
        TelephonyManager.NETWORK_TYPE_UMTS -> "UMTS"
        TelephonyManager.NETWORK_TYPE_CDMA -> "CDMA"
        TelephonyManager.NETWORK_TYPE_EVDO_0 -> "EVDO_0"
        TelephonyManager.NETWORK_TYPE_EVDO_A -> "EVDO_A"
        TelephonyManager.NETWORK_TYPE_1xRTT -> "1xRTT"
        TelephonyManager.NETWORK_TYPE_HSDPA -> "HSDPA"
        TelephonyManager.NETWORK_TYPE_HSUPA -> "HSUPA"
        TelephonyManager.NETWORK_TYPE_HSPA -> "HSPA"
        TelephonyManager.NETWORK_TYPE_IDEN -> "IDEN"
        TelephonyManager.NETWORK_TYPE_EVDO_B -> "EVDO_B"
        TelephonyManager.NETWORK_TYPE_LTE -> "LTE"
        TelephonyManager.NETWORK_TYPE_EHRPD -> "EHRPD"
        TelephonyManager.NETWORK_TYPE_HSPAP -> "HSPAP"
        TelephonyManager.NETWORK_TYPE_GSM -> "GSM"
        TelephonyManager.NETWORK_TYPE_TD_SCDMA -> "TD_SCDMA"
        TelephonyManager.NETWORK_TYPE_IWLAN -> "IWLAN"
        19 -> "LTE_CA" // NETWORK_TYPE_LTE_CA is hidden system API.
        TelephonyManager.NETWORK_TYPE_NR -> "NR"
        TelephonyManager.NETWORK_TYPE_UNKNOWN -> "UNKNOWN"
        else -> "TYPE_$type"
    }

    private fun permissionState(context: CheckContext, permission: String): String =
        if (context.context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED) {
            "granted"
        } else {
            "denied"
        }

    private fun orEmptyMarker(value: String): String = value.ifEmpty { "<空>" }

    private fun nameMarker(value: String): String = value.ifEmpty { "<空>" }.clip(48)

    private fun String.clip(max: Int): String = replace('\n', ' ').take(max)

    private fun List<String>.clipped(): List<String> =
        take(MAX_EVIDENCE_LINES).map { it.clip(MAX_EVIDENCE_CHARS) }

    private companion object {
        const val CHINA_MCC = 460
        const val MAX_EVIDENCE_LINES = 20
        const val MAX_EVIDENCE_CHARS = 280

        val CHINA_CDMA_MNCS = setOf(3, 5, 11)

        val CDMA_CAPABLE_MCCS = setOf(
            302, // 加拿大
            310, 311, 312, 313, 314, 315, 316, // 美国
            334, // 墨西哥
            440, 441, // 日本
            450, // 韩国
            455, // 澳门
        )

        val GSM_FAMILY_NETWORK_TYPES = setOf(
            TelephonyManager.NETWORK_TYPE_GSM,
            TelephonyManager.NETWORK_TYPE_UMTS,
            TelephonyManager.NETWORK_TYPE_HSDPA,
            TelephonyManager.NETWORK_TYPE_HSUPA,
            TelephonyManager.NETWORK_TYPE_HSPA,
            TelephonyManager.NETWORK_TYPE_HSPAP,
            TelephonyManager.NETWORK_TYPE_LTE,
            TelephonyManager.NETWORK_TYPE_NR,
        )

        val CORE_READ_LABELS = setOf(
            "phoneType",
            "simState",
            "simOperator",
            "networkOperator",
            "dataNetworkType",
            "voiceNetworkType",
            "allCellInfo",
        )
    }
}
