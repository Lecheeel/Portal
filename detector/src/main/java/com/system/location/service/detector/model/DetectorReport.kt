package com.system.location.service.detector.model

import android.content.Context
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject

data class DeviceInfo(
    val manufacturer: String,
    val model: String,
    val android: String,
    val sdk: Int,
    val rom: String,
    val lsposed: String?,
    val abis: List<String>,
)

/** Read-only device identity used by the JSON report header. */
object DeviceInfoCollector {
    fun collect(context: Context): DeviceInfo {
        val packageManager = context.packageManager
        val lsposed = listOf("org.lsposed.manager", "io.github.lsposed.manager").firstNotNullOfOrNull { pkg ->
            runCatching {
                val info = packageManager.getPackageInfo(pkg, 0)
                "$pkg ${info.versionName ?: "unknown"}"
            }.getOrNull()
        }
        return DeviceInfo(
            manufacturer = Build.MANUFACTURER?.takeIf { it.isNotBlank() } ?: "unknown",
            model = Build.MODEL?.takeIf { it.isNotBlank() } ?: "unknown",
            android = Build.VERSION.RELEASE?.takeIf { it.isNotBlank() } ?: "unknown",
            sdk = Build.VERSION.SDK_INT,
            rom = "${Build.DISPLAY} (${Build.VERSION.INCREMENTAL})",
            lsposed = lsposed,
            abis = Build.SUPPORTED_ABIS?.toList() ?: emptyList(),
        )
    }
}

/**
 * Versioned detector report. [SCHEMA] must match docs/roadmap/06-validation.md section 2.3; new
 * fields are additive so older readers can ignore them.
 */
class DetectorReport(
    val generatedAtMillis: Long,
    val device: DeviceInfo,
    val checks: List<CheckResult>,
    /** Public callback observations for independent before/after distribution analysis. */
    val gnssEvidence: GnssEvidenceSnapshot? = null,
) {
    val passCount: Int get() = checks.count { it.status == CheckStatus.PASS }
    val failCount: Int get() = checks.count { it.status == CheckStatus.FAIL }
    val unknownCount: Int get() = checks.count { it.status == CheckStatus.UNKNOWN }

    fun toJson(): String {
        val root = JSONObject()
        root.put("schema", SCHEMA)
        root.put("generatedAtMillis", generatedAtMillis)
        root.put("device", JSONObject().apply {
            put("manufacturer", device.manufacturer)
            put("model", device.model)
            put("android", device.android)
            put("sdk", device.sdk)
            put("rom", device.rom)
            put("lsposed", device.lsposed ?: JSONObject.NULL)
            put("abis", JSONArray(device.abis))
        })
        root.put("summary", JSONObject().apply {
            put("pass", passCount)
            put("fail", failCount)
            put("unknown", unknownCount)
        })
        root.put("checks", JSONArray().apply {
            this@DetectorReport.checks.forEach { result ->
                put(JSONObject().apply {
                    put("id", result.id)
                    put("title", result.title)
                    put("status", result.status.name)
                    put("detail", result.detail)
                    put("blocking", result.blocking)
                    put("evidence", JSONArray(result.evidence))
                })
            }
        })
        gnssEvidence?.let { evidence ->
            root.put("gnssEvidence", JSONObject().apply {
                put("schema", 1)
                put("startedAtMillis", evidence.startedAtMillis)
                put("endedAtMillis", evidence.endedAtMillis)
                put("timeSource", "Android callback arrival UTC milliseconds")
                put("frames", JSONArray().apply {
                    evidence.frames.forEach { frame ->
                        put(JSONObject().apply {
                            put("capturedAtMillis", frame.capturedAtMillis)
                            put("satellites", JSONArray().apply {
                                frame.satellites.forEach { satellite ->
                                    put(JSONObject().apply {
                                        put("constellation", satellite.constellation)
                                        put("svid", satellite.svid)
                                        // Invalid native values must remain exportable as evidence.
                                        put("cn0DbHz", satellite.cn0DbHz.finiteJsonValue())
                                        put("elevationDegrees", satellite.elevationDegrees.finiteJsonValue())
                                        put("azimuthDegrees", satellite.azimuthDegrees.finiteJsonValue())
                                        put("usedInFix", satellite.usedInFix)
                                        put("hasEphemeris", satellite.hasEphemeris)
                                        put("hasAlmanac", satellite.hasAlmanac)
                                    })
                                }
                            })
                        })
                    }
                })
            })
        }
        return root.toString(2)
    }

    fun toText(): String = buildString {
        appendLine("Portal Detector · schema $SCHEMA")
        appendLine("device: ${device.manufacturer} ${device.model} · Android ${device.android} (SDK ${device.sdk})")
        appendLine("rom: ${device.rom}")
        appendLine("lsposed: ${device.lsposed ?: "absent"}")
        appendLine("checks: PASS=$passCount FAIL=$failCount UNKNOWN=$unknownCount")
        checks.forEach { result ->
            appendLine()
            appendLine("[${result.status}] ${result.id} ${result.title}${if (result.blocking) " (blocking)" else ""}")
            appendLine("  ${result.detail}")
            result.evidence.forEach { appendLine("  · $it") }
        }
    }

    companion object {
        const val SCHEMA = 1
    }
}

data class GnssEvidenceSnapshot(val startedAtMillis: Long, val endedAtMillis: Long,
    val frames: List<GnssFrameSnapshot>)

private fun Float.finiteJsonValue(): Any = if (isFinite()) toDouble() else toString()
