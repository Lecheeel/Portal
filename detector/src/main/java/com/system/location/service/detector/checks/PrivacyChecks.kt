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

        // 枚举面（工作包 9 第三批修复的对应验证）：持有 PACKAGE_USAGE_STATS 的检测器可以走
        // getOpsForPackage/getUidOps 拿到完整 op 列表。detector 无该权限时记 UNKNOWN 证据，
        // 不伪造 PASS；能读到且含 mock_location 条目即泄漏。
        val enumProbes = listOf("getOpsForPackage", "getUidOps").map {
            enumSurfaceProbe(appOps, it, targetUid, targetPackage)
        }
        enumProbes.mapTo(lines) { describeEnumProbe(it) }

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
        val enumLeaks = enumProbes.filter { it.mockEntryFound == true }
        val allowed = readableTarget.any { it.mode == AppOpsManager.MODE_ALLOWED } ||
            enumLeaks.isNotEmpty()

        lines += "verdict=${if (allowed) "FAIL" else "PASS"} target_mode=$targetSummary " +
            "self_mode=$selfSummary enum_leak=${if (enumLeaks.isNotEmpty()) enumLeaks.joinToString("/") { it.label } else "无"}"

        return if (allowed) {
            fail(
                buildString {
                    append("目标包 $targetPackage 的 OP_MOCK_LOCATION=$targetSummary")
                    if (enumLeaks.isNotEmpty()) {
                        append("；枚举面(${enumLeaks.joinToString("/") { it.label }})仍含 mock_location 条目")
                    }
                    append("，第三方应用可通过该 AppOps 判定目标包被允许模拟定位。")
                },
                limitedEvidence(lines),
            )
        } else {
            pass(
                "目标包 $targetPackage 的 OP_MOCK_LOCATION 不可见（$targetSummary）；detector 自身为 $selfSummary；" +
                    "枚举面无泄漏或未授权无法验证。",
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
        // query 路径探针（工作包 9）：getString 走 provider.call；直接 query provider 是独立入口，
        // 响应的列集合/行数也是指纹（旧实现曾伪造单列 cursor）。
        val mockLocationViaQuery = readSecureViaQuery(resolver, "mock_location")

        val mockOn = mockLocation.value == "1" || mockLocationViaQuery.value == "1"
        val developerOn = developerOptions.value == "1"
        val legacyOn = legacyAllowMock.value == "1"

        val lines = mutableListOf(
            mockLocation.describe(),
            mockLocationViaQuery.describe(),
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
                mockLocationViaQuery.isReadable &&
                mockLocation.isClosed && developerOptions.isClosed && mockLocationViaQuery.isClosed -> pass(
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
    val note: String? = null,
) {
    val isReadable: Boolean get() = error == null
    val isClosed: Boolean get() = value == null || value == "0"

    fun displayValue(): String = value ?: "null（未设置）"

    fun describe(): String = buildString {
        append(if (isReadable) "$label=${displayValue()}" else "$label 读取异常：$error")
        if (note != null) append("（$note）")
    }
}

private fun readSecure(resolver: ContentResolver, key: String, label: String): SettingsRead =
    try {
        SettingsRead(label, Settings.Secure.getString(resolver, key), null)
    } catch (error: Exception) {
        SettingsRead(label, null, describeError(error))
    }

/**
 * 直接 query settings provider（`content://settings/secure`）读取一个 key。响应保留
 * provider 原生 cursor 的列集合与行数，作为与 getString(call 路径) 对齐的独立入口证据。
 */
private fun readSecureViaQuery(resolver: ContentResolver, key: String): SettingsRead =
    try {
        val cursor = resolver.query(
            Settings.Secure.CONTENT_URI,
            null,
            "name=?",
            arrayOf(key),
            null,
        )
        if (cursor == null) {
            SettingsRead("query[secure/$key]", null, null, note="cursor=null（key 未设置）")
        } else {
            cursor.use { c ->
                if (!c.moveToFirst()) {
                    SettingsRead("query[secure/$key]", null, null, note="空结果（key 未设置）")
                } else {
                    val columns = c.columnNames
                    val valueIndex = columns.indexOfFirst { it.equals("value", ignoreCase = true) }
                    SettingsRead(
                        "query[secure/$key]",
                        if (valueIndex >= 0) c.getString(valueIndex) else null,
                        null,
                        note = "列=${columns.joinToString(",")}",
                    )
                }
            }
        }
    } catch (error: Exception) {
        SettingsRead("query[secure/$key]", null, describeError(error))
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

private const val MOCK_OP_CODE = 58 // AppOpsManager.OP_MOCK_LOCATION，@hide 常量，自 API 6 起稳定

private data class EnumProbe(
    val label: String,
    val note: String?,
    /** null = 无法验证（未授权/异常）；true = 发现 mock_location 条目（泄漏）；false = 无条目 */
    val mockEntryFound: Boolean?,
    val entryCount: Int?,
)

private fun describeEnumProbe(probe: EnumProbe): String = buildString {
    append("enum[").append(probe.label).append("] ")
    append(probe.entryCount?.let { "entries=$it" } ?: "entries=未知")
    append(" mock_entry=").append(
        when (probe.mockEntryFound) {
            true -> "存在（泄漏）"
            false -> "无"
            null -> "无法验证"
        },
    )
    if (probe.note != null) append(" (").append(probe.note).append(')')
}

/**
 * 通过反射调用 `AppOpsManager.getOpsForPackage/getUidOps`（方法为公开 API，但返回的
 * `PackageOps.getOps()` 元素类型 `Op` 是 @hide，因此条目类型与 op 字段同样走反射）。
 * 未持有 PACKAGE_USAGE_STATS 的调用方会收到 SecurityException，调用方据此记 UNKNOWN。
 */
private fun enumSurfaceProbe(
    appOps: AppOpsManager,
    surface: String,
    targetUid: Int,
    targetPackage: String,
): EnumProbe = runCatching {
    val list: List<*> = when (surface) {
        "getOpsForPackage" ->
            appOps.javaClass.getMethod("getOpsForPackage", Integer.TYPE, String::class.java)
                .invoke(appOps, targetUid, targetPackage) as? List<*>
                    ?: return EnumProbe(surface, "返回 null", false, 0)
        "getUidOps" ->
            appOps.javaClass.getMethod("getUidOps", Integer.TYPE)
                .invoke(appOps, targetUid) as? List<*>
                    ?: return EnumProbe(surface, "返回 null", false, 0)
        else -> return EnumProbe(surface, "未知枚举面", null, null)
    }
    var mockFound = false
    for (entry in list) {
        val ops = entry?.javaClass?.getMethod("getOps")?.invoke(entry) as? List<*> ?: continue
        for (opEntry in ops) {
            val op = opEntry?.javaClass?.getField("op")?.getInt(opEntry) ?: continue
            if (op == MOCK_OP_CODE) mockFound = true
        }
    }
    EnumProbe(surface, null, mockFound, list.size)
}.getOrElse { EnumProbe(surface, describeError(it), null, null) }

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
