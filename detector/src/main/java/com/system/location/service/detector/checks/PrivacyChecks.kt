package com.system.location.service.detector.checks

import android.app.AppOpsManager
import android.content.ContentResolver
import android.os.Process
import android.provider.Settings
import com.system.location.service.detector.model.Check
import com.system.location.service.detector.model.CheckContext
import com.system.location.service.detector.model.CheckIds
import com.system.location.service.detector.model.CheckResult

/**
 * C02 · AppOps mock_location。
 *
 * 期望：目标包（默认 Portal）的 `OP_MOCK_LOCATION` 不是 MODE_ALLOWED；同时记录 detector 自身
 * 的 AppOps 状态作为对照。`checkOpNoThrow` 失败时回退 `unsafeCheckOpNoThrow`；跨包查询不可用
 * 时返回 UNKNOWN，而不是把“查不到”当成安全。
 */
class AppOpsCheck : Check {
    override val id = CheckIds.APP_OPS
    override val title = "AppOps mock_location"
    override val blocking = true

    override fun run(context: CheckContext): CheckResult = try {
        evaluate(context)
    } catch (error: Exception) {
        unknown(
            "C02 检查异常：${describeError(error)}",
            limitedEvidence(listOf("unexpected=${error.javaClass.name}")),
        )
    }

    @Suppress("DEPRECATION")
    private fun evaluate(context: CheckContext): CheckResult {
        val appContext = context.context
        val targetPackage = context.settings.targetPackage
        val appOps = appContext.getSystemService(AppOpsManager::class.java)
            ?: return unknown(
                "AppOpsManager 不可用，无法读取 OP_MOCK_LOCATION。",
                limitedEvidence(listOf("getSystemService(AppOpsManager) = null")),
            )

        val targetInfo = runCatching {
            appContext.packageManager.getApplicationInfo(targetPackage, 0)
        }
        val targetUid = targetInfo.getOrNull()?.uid
        if (targetUid == null) {
            val reason = targetInfo.exceptionOrNull()?.let(::describeError) ?: "ApplicationInfo=null"
            return unknown(
                "目标包 $targetPackage 未安装或对 detector 不可见（$reason），无法取得 UID。",
                limitedEvidence(
                    listOf(
                        "target=$targetPackage",
                        "getApplicationInfo($targetPackage, 0) 失败：$reason",
                        "提示：自定义目标包需在 detector 的 <queries> 中声明可见性",
                    )
                ),
            )
        }

        val lines = mutableListOf<String>()
        lines += "target=$targetPackage uid=$targetUid"
        val targetProbes = MOCK_LOCATION_OPS.map { (label, op) ->
            queryMockLocationOp(appOps, op, label, targetUid, targetPackage)
        }
        targetProbes.mapTo(lines) { describeProbe("target", it) }

        val selfUid = Process.myUid()
        val selfPackage = appContext.packageName
        lines += "self=$selfPackage uid=$selfUid"
        val selfProbes = MOCK_LOCATION_OPS.map { (label, op) ->
            queryMockLocationOp(appOps, op, label, selfUid, selfPackage)
        }
        selfProbes.mapTo(lines) { describeProbe("self", it) }

        val readableTarget = targetProbes.filter { it.mode != null }
        if (readableTarget.isEmpty()) {
            return unknown(
                "无法查询目标包 $targetPackage 的 OP_MOCK_LOCATION（所有查询方式均失败）。",
                limitedEvidence(lines),
            )
        }

        val firstTarget = readableTarget.first()
        val targetSummary = firstTarget.mode?.let { "${firstTarget.label}=${modeLabel(it)}" } ?: "未知"
        val selfSummary = selfProbes.firstNotNullOfOrNull { probe ->
            probe.mode?.let { "${probe.label}=${modeLabel(it)}" }
        } ?: "查询失败"
        val allowed = readableTarget.any { it.mode == AppOpsManager.MODE_ALLOWED }

        lines += "verdict=${if (allowed) "FAIL" else "PASS"} target_mode=$targetSummary self_mode=$selfSummary"

        return if (allowed) {
            fail(
                "目标包 $targetPackage 的 OP_MOCK_LOCATION=$targetSummary，第三方应用可通过该 AppOps 判定目标包被允许模拟定位。",
                limitedEvidence(lines),
            )
        } else {
            pass(
                "目标包 $targetPackage 的 OP_MOCK_LOCATION 不可见（$targetSummary）；detector 自身为 $selfSummary。",
                limitedEvidence(lines),
            )
        }
    }
}

/**
 * C03 · Settings mock_location 与开发者选项。
 *
 * 期望：`Settings.Secure.getString("mock_location")` 与
 * `Settings.Global.DEVELOPMENT_SETTINGS_ENABLED` 对普通调用方不可见或为关闭状态。
 * 只做读取，不尝试 WRITE_SECURE_SETTINGS，也不调用 adb。
 */
class SettingsCheck : Check {
    override val id = CheckIds.SETTINGS
    override val title = "Settings mock_location / 开发者选项"
    override val blocking = true

    override fun run(context: CheckContext): CheckResult = try {
        evaluate(context)
    } catch (error: Exception) {
        unknown(
            "C03 检查异常：${describeError(error)}",
            limitedEvidence(listOf("unexpected=${error.javaClass.name}")),
        )
    }

    private fun evaluate(context: CheckContext): CheckResult {
        val resolver = context.context.contentResolver
        val mockLocation = readSecure(resolver, "mock_location", "Settings.Secure[mock_location]")
        val developerOptions = readGlobalInt(resolver, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED)
        // 旧版开关（Android 4.2 前）仅作补充证据，不参与判定。
        val legacyAllowMock = readSecure(resolver, "allow_mock_location", "Settings.Secure[allow_mock_location]（旧 key）")

        val mockOn = mockLocation.value == "1"
        val developerOn = developerOptions.value == "1"
        val legacyOn = legacyAllowMock.value == "1"

        val lines = mutableListOf(
            mockLocation.describe(),
            developerOptions.describe(),
            legacyAllowMock.describe(),
            "verdict: mock_location_on=$mockOn developer_options_on=$developerOn legacy_on=$legacyOn（legacy 仅记录）",
        )

        return when {
            mockOn || developerOn -> fail(
                buildString {
                    append("检测到模拟定位相关设置已开启：")
                    append(
                        buildList {
                            if (mockOn) add("Settings.Secure.mock_location=1")
                            if (developerOn) add("开发者选项=1")
                        }.joinToString("、")
                    )
                    append("。")
                    if (legacyOn) append("旧 key allow_mock_location 也为 1（仅记录）。")
                },
                limitedEvidence(lines),
            )

            mockLocation.isReadable && developerOptions.isReadable &&
                mockLocation.isClosed && developerOptions.isClosed -> pass(
                "模拟定位设置不可见或已关闭：mock_location=${mockLocation.displayValue()}，开发者选项=${developerOptions.displayValue()}。",
                limitedEvidence(lines),
            )

            else -> unknown(
                "无法确认 Settings 状态：${unreadableLabels(mockLocation, developerOptions)}"
                    + "（普通应用读取受限 Settings 会得到 SecurityException，无法据此证明已关闭）。",
                limitedEvidence(lines),
            )
        }
    }
}

private data class SettingsRead(
    val label: String,
    val value: String?,
    val error: String?,
) {
    val isReadable: Boolean get() = error == null
    val isClosed: Boolean get() = value == null || value == "0"

    fun displayValue(): String = value ?: "null（未设置）"

    fun describe(): String =
        if (isReadable) "$label=${displayValue()}" else "$label 读取异常：$error"
}

private fun readSecure(resolver: ContentResolver, key: String, label: String): SettingsRead =
    try {
        SettingsRead(label, Settings.Secure.getString(resolver, key), null)
    } catch (error: Exception) {
        SettingsRead(label, null, describeError(error))
    }

private fun readGlobalInt(resolver: ContentResolver, key: String): SettingsRead =
    try {
        SettingsRead("Settings.Global[$key]", Settings.Global.getInt(resolver, key, 0).toString(), null)
    } catch (error: Exception) {
        SettingsRead("Settings.Global[$key]", null, describeError(error))
    }

private fun unreadableLabels(vararg reads: SettingsRead): String =
    reads.filterNot { it.isReadable }.joinToString("、") { "${it.label}（${it.error}）" }

private data class OpProbe(
    val label: String,
    val op: String,
    val method: String?,
    val mode: Int?,
    val note: String?,
)

private val MOCK_LOCATION_OPS = listOf(
    "OPSTR_MOCK_LOCATION" to AppOpsManager.OPSTR_MOCK_LOCATION,
    "legacy" to "android:mock_location",
)

@Suppress("DEPRECATION")
private fun queryMockLocationOp(
    appOps: AppOpsManager,
    op: String,
    label: String,
    uid: Int,
    packageName: String,
): OpProbe {
    try {
        val mode = appOps.checkOpNoThrow(op, uid, packageName)
        return OpProbe(label, op, "checkOpNoThrow", mode, null)
    } catch (error: Exception) {
        val primary = describeError(error)
        return try {
            val mode = appOps.unsafeCheckOpNoThrow(op, uid, packageName)
            OpProbe(label, op, "unsafeCheckOpNoThrow", mode, "checkOpNoThrow $primary，已回退")
        } catch (fallback: Exception) {
            OpProbe(
                label,
                op,
                null,
                null,
                "checkOpNoThrow=$primary; unsafeCheckOpNoThrow=${describeError(fallback)}",
            )
        }
    }
}

private fun describeProbe(role: String, probe: OpProbe): String = buildString {
    append(role).append(" op[").append(probe.label).append('=').append(probe.op).append("] ")
    val mode = probe.mode
    if (mode != null) {
        append(probe.method).append('=').append(modeLabel(mode))
        if (probe.note != null) append(" (").append(probe.note).append(')')
    } else {
        append("查询失败：").append(probe.note)
    }
}

private fun modeLabel(mode: Int): String {
    val name = when (mode) {
        AppOpsManager.MODE_ALLOWED -> "MODE_ALLOWED"
        AppOpsManager.MODE_IGNORED -> "MODE_IGNORED"
        AppOpsManager.MODE_ERRORED -> "MODE_ERRORED"
        AppOpsManager.MODE_DEFAULT -> "MODE_DEFAULT"
        AppOpsManager.MODE_FOREGROUND -> "MODE_FOREGROUND"
        else -> "MODE_UNKNOWN"
    }
    return "$name($mode)"
}

private fun describeError(error: Throwable): String {
    val message = error.message
        ?.replace('\n', ' ')
        ?.replace('\r', ' ')
        ?.trim()
        ?.take(120)
    return error.javaClass.simpleName + if (!message.isNullOrEmpty()) ": $message" else ""
}

private const val MAX_EVIDENCE_LINES = 20

private fun limitedEvidence(lines: List<String>): List<String> {
    val normalized = lines.map(::oneLine)
    if (normalized.size <= MAX_EVIDENCE_LINES) return normalized
    return normalized.take(MAX_EVIDENCE_LINES - 1) + "…（已截断，共 ${normalized.size} 行）"
}

private fun oneLine(text: String): String {
    val flattened = text.replace('\n', ' ').replace('\r', ' ')
    return if (flattened.length <= 299) flattened else flattened.take(296) + "..."
}
