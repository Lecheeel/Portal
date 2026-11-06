package com.system.location.service.detector.checks

import android.location.LocationManager
import android.location.provider.ProviderProperties
import com.system.location.service.detector.model.Check
import com.system.location.service.detector.model.CheckContext
import com.system.location.service.detector.model.CheckIds
import com.system.location.service.detector.model.CheckResult

/**
 * C04 · Provider 列表与属性。
 *
 * 期望：`allProviders` / `getProviders(true)` / `getProviderProperties` / `isProviderEnabled`
 * 不暴露 `fused_ext` 或 Portal 的 test provider，且四处结果一致。
 * 只读公开 API；属性读取受系统权限限制时给出 UNKNOWN 而不是伪造 PASS。
 */
class ProviderListCheck : Check {
    override val id = CheckIds.PROVIDER_LIST
    override val title = "Provider 列表与属性"
    override val blocking = true

    override fun run(context: CheckContext): CheckResult = try {
        evaluate(context)
    } catch (error: Exception) {
        unknown(
            "C04 检查异常：${describeError(error)}",
            limitedEvidence(listOf("unexpected=${error.javaClass.name}")),
        )
    }

    private fun evaluate(context: CheckContext): CheckResult {
        val manager = context.locationManager
            ?: return unknown(
                "LocationManager 不可用，无法枚举 Provider。",
                limitedEvidence(listOf("context.getSystemService(LocationManager) = null")),
            )

        val allProviders = try {
            manager.allProviders.distinct().sorted()
        } catch (error: Exception) {
            return unknown(
                "无法读取 LocationManager.allProviders：${describeError(error)}",
                limitedEvidence(listOf("allProviders 异常：${describeError(error)}")),
            )
        }

        val enabledResult = runCatching { manager.getProviders(true).distinct().sorted() }
        val enabledProviders = enabledResult.getOrNull()

        if (allProviders.isEmpty()) {
            return fail(
                "LocationManager.allProviders 为空；Android 至少应包含 passive Provider，列表明显异常。",
                limitedEvidence(
                    listOf(
                        "allProviders=[]（空列表）",
                        "getProviders(true)=${enabledProviders?.joinToString(prefix = "[", postfix = "]") ?: "读取失败"}",
                    )
                ),
            )
        }

        val allSet = allProviders.toSet()
        val suspicious = linkedSetOf<String>()
        allProviders.filterTo(suspicious) { isSuspiciousProvider(it) }
        enabledProviders?.filterTo(suspicious) { isSuspiciousProvider(it) }
        val extraEnabled = enabledProviders?.filterNot { it in allSet }.orEmpty()
        val propertyReads = allProviders.map { provider -> readProperties(manager, provider) }

        val probeName = "__detector_missing_provider__"
        var probeReportedEnabled = false
        var probeThrew = false
        val probeLine = try {
            val enabled = manager.isProviderEnabled(probeName)
            probeReportedEnabled = enabled
            "probe isProviderEnabled(\"$probeName\")=$enabled（预期 false）"
        } catch (error: Exception) {
            probeThrew = true
            "probe isProviderEnabled(\"$probeName\") 异常：${describeError(error)}"
        }

        val failures = mutableListOf<String>()
        if (suspicious.isNotEmpty()) {
            failures += "暴露可疑 Provider：${suspicious.joinToString("、")}"
        }
        if (extraEnabled.isNotEmpty()) {
            failures += "getProviders(true) 含 allProviders 之外的项目：${extraEnabled.joinToString("、")}"
        }
        val propertyFailures = propertyReads.filter { it.hardFailure }
        if (propertyFailures.isNotEmpty()) {
            failures += "Provider 属性异常：${propertyFailures.joinToString("、") { "${it.provider}(${it.error})" }}"
        }
        if (probeReportedEnabled) {
            failures += "isProviderEnabled 对不存在的 Provider 返回 true"
        }

        val keyLines = mutableListOf(
            "allProviders(${allProviders.size})=${allProviders.joinToString(prefix = "[", postfix = "]")}",
            "getProviders(true)=" + (
                enabledProviders?.let { "(${it.size})${it.joinToString(prefix = "[", postfix = "]")}" }
                    ?: "读取失败：${enabledResult.exceptionOrNull()?.let(::describeError)}"
                ),
        )
        if (suspicious.isNotEmpty()) keyLines += "suspicious=${suspicious.joinToString(prefix = "[", postfix = "]")}"
        if (extraEnabled.isNotEmpty()) {
            keyLines += "enabled_not_in_allProviders=${extraEnabled.joinToString(prefix = "[", postfix = "]")}"
        }
        keyLines += probeLine
        val evidenceLines = keyLines + propertyReads.map { it.describe() }

        val inconclusiveReasons = mutableListOf<String>()
        if (enabledProviders == null) inconclusiveReasons += "getProviders(true) 读取失败"
        if (probeThrew) inconclusiveReasons += "isProviderEnabled 对不存在的 Provider 抛异常"
        propertyReads.filter { it.inconclusive }.forEach {
            inconclusiveReasons += "${it.provider} 属性读取受权限限制"
        }

        return when {
            failures.isNotEmpty() -> fail(
                "Provider 检查发现异常：${failures.joinToString("；")}。",
                limitedEvidence(evidenceLines),
            )

            inconclusiveReasons.isNotEmpty() -> unknown(
                "Provider 列表可读，但部分项目无法验证：${inconclusiveReasons.joinToString("；")}。",
                limitedEvidence(evidenceLines),
            )

            else -> pass(
                "Provider 列表正常：allProviders=${allProviders.joinToString(",")}；"
                    + "enabled=${enabledProviders?.joinToString(",") ?: "不可读"}；"
                    + "属性齐全，未发现 fused_ext/test/mock/portal Provider。",
                limitedEvidence(evidenceLines),
            )
        }
    }

    private fun readProperties(manager: LocationManager, provider: String): PropertyRead =
        try {
            val properties = manager.getProviderProperties(provider)
            if (properties == null) {
                PropertyRead(
                    provider = provider,
                    summary = null,
                    error = "getProviderProperties 返回 null（属性未知）",
                    hardFailure = true,
                    inconclusive = false,
                )
            } else {
                PropertyRead(provider, summarize(properties), null, hardFailure = false, inconclusive = false)
            }
        } catch (error: Exception) {
            val security = error is SecurityException
            PropertyRead(
                provider = provider,
                summary = null,
                error = describeError(error),
                hardFailure = !security,
                inconclusive = security,
            )
        }
}

private data class PropertyRead(
    val provider: String,
    val summary: String?,
    val error: String?,
    val hardFailure: Boolean,
    val inconclusive: Boolean,
) {
    fun describe(): String =
        if (summary != null) "$provider: $summary" else "$provider: 属性不可用（$error）"
}

private fun isSuspiciousProvider(name: String): Boolean {
    val lower = name.lowercase()
    return lower.contains("fused_ext") ||
        lower.contains("test") ||
        lower.contains("mock") ||
        lower.contains("portal")
}

private fun summarize(properties: ProviderProperties): String = buildString {
    append("power=").append(powerUsageLabel(properties.powerUsage))
    append(" accuracy=").append(accuracyLabel(properties.accuracy))
    append(" alt=").append(properties.hasAltitudeSupport())
    append(" bearing=").append(properties.hasBearingSupport())
    append(" speed=").append(properties.hasSpeedSupport())
    append(" sat=").append(properties.hasSatelliteRequirement())
    append(" cell=").append(properties.hasCellRequirement())
    append(" net=").append(properties.hasNetworkRequirement())
    append(" cost=").append(properties.hasMonetaryCost())
}

private fun powerUsageLabel(value: Int): String = when (value) {
    ProviderProperties.POWER_USAGE_LOW -> "LOW(1)"
    ProviderProperties.POWER_USAGE_MEDIUM -> "MEDIUM(2)"
    ProviderProperties.POWER_USAGE_HIGH -> "HIGH(3)"
    else -> "UNKNOWN($value)"
}

private fun accuracyLabel(value: Int): String = when (value) {
    ProviderProperties.ACCURACY_FINE -> "FINE(1)"
    ProviderProperties.ACCURACY_COARSE -> "COARSE(2)"
    else -> "UNKNOWN($value)"
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
