package com.system.location.service.detector.checks

import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import com.system.location.service.detector.model.Check
import com.system.location.service.detector.model.CheckContext
import com.system.location.service.detector.model.CheckIds
import com.system.location.service.detector.model.CheckResult
import java.io.File
import java.util.Locale

/**
 * C13 · PackageManager 中的 Xposed 元数据。
 *
 * 期望：目标包的 ApplicationInfo.metaData 里没有 `xposedmodule`/`xposedscope`/
 * `xposeddescription`/`xposedminversion`。LSPosed 管理器是否安装只作为 evidence，
 * 因为检测器自己能看到管理器不等于模块身份泄漏。
 *
 * 目标包不可见/不存在 → UNKNOWN，并提示检查目标包名与 manifest `<queries>`
 * （当前 manifest 只声明了 com.system.location.service 等包，属于共享改动范围）。
 */
class PackageIdentityCheck : Check {
    override val id = CheckIds.PACKAGE_IDENTITY
    override val title = "PackageManager Xposed 元数据"

    override fun run(context: CheckContext): CheckResult = try {
        val packageManager = context.context.packageManager
        val targetPackage = context.settings.targetPackage
        val evidence = mutableListOf<String>()

        val info = try {
            loadApplicationInfo(packageManager, targetPackage)
        } catch (error: PackageManager.NameNotFoundException) {
            addIdentityEvidence(evidence, "目标包 $targetPackage 不可见/未安装：${error.javaClass.simpleName}")
            return unknown(
                "目标包 $targetPackage 不存在或对 detector 不可见；请检查目标包名设置与 manifest " +
                    "<queries>/QUERY_ALL_PACKAGES 包可见性配置，无法判定 Xposed 元数据。",
                evidence,
            )
        } catch (error: Throwable) {
            addIdentityEvidence(
                evidence,
                "读取 $targetPackage 失败：${error.javaClass.simpleName}: ${error.message}",
            )
            return unknown("读取目标包失败，无法判定 Xposed 元数据。", evidence)
        }

        val version = runCatching { packageVersion(packageManager, targetPackage) }.getOrNull() ?: "?"
        addIdentityEvidence(evidence, "目标包=$targetPackage version=$version")

        val found = mutableListOf<Pair<String, String>>()
        val metaData = info.metaData
        if (metaData != null) {
            for (key in metaData.keySet()) {
                if (key.lowercase(Locale.ROOT) in XPOSED_META_KEYS) {
                    found += key to formatMetaValue(metaData.get(key))
                }
            }
        }
        if (found.isEmpty()) {
            addIdentityEvidence(
                evidence,
                "meta-data：未发现 ${XPOSED_META_KEYS.joinToString("/")}",
            )
        } else {
            found.take(6).forEach { (key, value) ->
                addIdentityEvidence(evidence, "meta-data $key=$value")
            }
        }

        for (managerPackage in LSPOSED_MANAGER_PACKAGES) {
            val version = runCatching { packageVersion(packageManager, managerPackage) }.getOrNull()
            if (version != null) {
                addIdentityEvidence(
                    evidence,
                    "LSPosed 管理器 $managerPackage version=$version（仅证据，不单独判 FAIL）",
                )
            } else {
                addIdentityEvidence(
                    evidence,
                    "LSPosed 管理器 $managerPackage 不可见/未安装（package visibility 可能影响，仅证据）",
                )
            }
        }

        if (found.isNotEmpty()) {
            val summary = found.joinToString("；") { "${it.first}=${it.second}" }.take(220)
            return fail(
                "目标包 $targetPackage 的 manifest 含 Xposed 元数据：$summary；" +
                    "符合当前 D13 legacy 元数据状态，P6 迁移 libxposed 后应清除。",
                evidence,
            )
        }
        return pass(
            "目标包 $targetPackage 存在且未发现 xposedmodule/xposedscope/xposeddescription/" +
                "xposedminversion 元数据；LSPosed 管理器仅作证据。",
            evidence,
        )
    } catch (error: Throwable) {
        unknown(
            "C13 检查异常：${error.javaClass.simpleName}: ${error.message}",
            listOf("异常：${error.javaClass.simpleName}"),
        )
    }
}

/**
 * C14 · root / 进程痕迹。
 *
 * 只读固定路径：`/proc/self/status`、`/proc/self/maps` 和 5 个常见 su 路径，
 * 不遍历文件系统。结果是 detector 自身进程的环境证据，不代表 target app 进程；
 * target app 当前未注入。
 *
 * TracerPid>0、maps 命中 lspd/dobby/locationext/xposed/magisk/可疑路径、su 可执行、
 * `Build.TAGS` 含 test-keys 或 `Build.TYPE` 为 eng/userdebug，均判 FAIL。
 * 没有任何痕迹但 /proc 读不到 → UNKNOWN。
 */
class RootTraceCheck : Check {
    override val id = CheckIds.ROOT_TRACE
    override val title = "root / 进程痕迹"

    override fun run(context: CheckContext): CheckResult = try {
        val evidence = mutableListOf<String>()
        val failures = mutableListOf<String>()
        addRootEvidence(
            evidence,
            "范围：detector 自身进程（${context.context.packageName}），不是 target app 进程；" +
                "target app 当前未注入",
        )

        var statusReadable = false
        var tracerPid: Int? = null
        val statusLines = runCatching {
            File("/proc/self/status").useLines { it.toList() }
        }.getOrNull()
        if (statusLines != null) {
            statusReadable = true
            tracerPid = statusLines.firstOrNull { it.startsWith("TracerPid:") }
                ?.substringAfter(':')?.trim()?.toIntOrNull()
            addRootEvidence(evidence, "TracerPid=${tracerPid ?: "解析失败"}（/proc/self/status）")
            if ((tracerPid ?: 0) > 0) failures += "TracerPid=$tracerPid，进程被 ptrace"
        } else {
            addRootEvidence(evidence, "/proc/self/status 不可读")
        }

        var mapsReadable = false
        val hits = linkedMapOf<String, String>()
        runCatching {
            File("/proc/self/maps").useLines { lines ->
                mapsReadable = true
                var inspected = 0
                for (line in lines) {
                    inspected++
                    if (inspected > ROOT_MAX_MAPS_LINES) break
                    val lower = line.lowercase(Locale.ROOT)
                    for (marker in ROOT_TRACE_MARKERS) {
                        if (hits.size >= 8) break
                        if (marker !in hits && lower.contains(marker)) {
                            hits[marker] = line.trim().take(180)
                        }
                    }
                    if (hits.size >= 8) break
                }
            }
        }.onFailure {
            addRootEvidence(evidence, "/proc/self/maps 读取失败：${it.javaClass.simpleName}")
        }
        if (mapsReadable) {
            if (hits.isEmpty()) {
                addRootEvidence(evidence, "maps：未命中 ${ROOT_TRACE_MARKERS.joinToString("/")}")
            } else {
                addRootEvidence(evidence, "maps 命中：${hits.keys.joinToString(", ")}")
                hits.entries.take(6).forEach { (marker, line) ->
                    addRootEvidence(evidence, "  [$marker] $line")
                }
                failures += "maps 含 ${hits.keys.joinToString(", ")}"
            }
        } else {
            addRootEvidence(evidence, "/proc/self/maps 不可读")
        }

        val suPresent = mutableListOf<String>()
        val suExecutable = mutableListOf<String>()
        for (path in ROOT_SU_PATHS) {
            val file = File(path)
            val exists = runCatching { file.exists() }.getOrDefault(false)
            val executable = exists && runCatching { file.canExecute() }.getOrDefault(false)
            if (exists) suPresent += path
            if (executable) suExecutable += path
        }
        if (suExecutable.isEmpty()) {
            addRootEvidence(
                evidence,
                "su 路径：无可执行文件（存在但不可执行：${suPresent.joinToString(", ").ifBlank { "无" }}）",
            )
        } else {
            addRootEvidence(evidence, "su 可执行：${suExecutable.joinToString(", ")}")
            failures += "su 可执行：${suExecutable.joinToString(", ")}"
        }

        val tags = Build.TAGS.orEmpty()
        val type = Build.TYPE.orEmpty()
        val testKeys = tags.contains("test-keys", ignoreCase = true)
        val debugType = type.contains("eng", ignoreCase = true) ||
            type.contains("userdebug", ignoreCase = true)
        addRootEvidence(
            evidence,
            "Build.TAGS=${tags.ifBlank { "<空>" }}，Build.TYPE=${type.ifBlank { "<空>" }}",
        )
        if (testKeys) failures += "Build.TAGS 含 test-keys"
        if (debugType) failures += "Build.TYPE=${type.ifBlank { "<空>" }}（eng/userdebug）"

        if (failures.isNotEmpty()) {
            return fail(
                "detector 自身进程发现 ${failures.size} 项环境痕迹：" +
                    "${failures.joinToString("；").take(220)}。" +
                    "注意：这是 detector 自身进程证据，不是 target app 进程。",
                evidence,
            )
        }
        if (!statusReadable || tracerPid == null || !mapsReadable) {
            return unknown(
                "无法完整读取 /proc/self/status 或 /proc/self/maps，不能确认无痕迹。" +
                    "注意：这是 detector 自身进程证据，不是 target app 进程。",
                evidence,
            )
        }
        return pass(
            "detector 自身进程未发现 TracerPid、maps 注入标记、可执行 su 或 test-keys/userdebug 构建痕迹。" +
                "注意：不代表 target app 进程。",
            evidence,
        )
    } catch (error: Throwable) {
        unknown(
            "C14 检查异常：${error.javaClass.simpleName}: ${error.message}",
            listOf("异常：${error.javaClass.simpleName}"),
        )
    }
}

@Suppress("DEPRECATION")
private fun loadApplicationInfo(packageManager: PackageManager, packageName: String): ApplicationInfo =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        packageManager.getApplicationInfo(
            packageName,
            PackageManager.ApplicationInfoFlags.of(PackageManager.GET_META_DATA.toLong()),
        )
    } else {
        packageManager.getApplicationInfo(packageName, PackageManager.GET_META_DATA)
    }

@Suppress("DEPRECATION")
private fun packageVersion(packageManager: PackageManager, packageName: String): String? {
    val info: PackageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0L))
    } else {
        packageManager.getPackageInfo(packageName, 0)
    }
    return info.versionName ?: info.longVersionCode.toString()
}

private fun formatMetaValue(value: Any?): String = when (value) {
    null -> "null"
    is String -> value
    is Int, is Long, is Boolean, is Float, is Double -> value.toString()
    is Array<*> -> value.joinToString(",") { it?.toString() ?: "" }
    is IntArray -> value.joinToString(",")
    is LongArray -> value.joinToString(",")
    is FloatArray -> value.joinToString(",")
    is DoubleArray -> value.joinToString(",")
    else -> value.toString()
}.replace('\n', ' ').take(200)

private fun addIdentityEvidence(evidence: MutableList<String>, line: String) {
    if (evidence.size < IDENTITY_MAX_EVIDENCE_LINES) {
        evidence += line.take(IDENTITY_MAX_EVIDENCE_CHARS)
    }
}

private fun addRootEvidence(evidence: MutableList<String>, line: String) {
    if (evidence.size < ROOT_MAX_EVIDENCE_LINES) {
        evidence += line.take(ROOT_MAX_EVIDENCE_CHARS)
    }
}

private val XPOSED_META_KEYS = setOf(
    "xposedmodule",
    "xposedscope",
    "xposeddescription",
    "xposedminversion",
)

private val LSPOSED_MANAGER_PACKAGES = listOf(
    "org.lsposed.manager",
    "io.github.lsposed.manager",
    "de.robv.android.xposed.installer",
)

private val ROOT_TRACE_MARKERS = listOf(
    "lspd",
    "dobby",
    "locationext",
    "xposed",
    "magisk",
    "/data/adb",
    "/data/local/tmp",
    "/data/local/ext-lib",
    "zygisk",
    "riru",
    "apatch",
    "/su/bin/",
)

private val ROOT_SU_PATHS = listOf(
    "/system/bin/su",
    "/system/xbin/su",
    "/sbin/su",
    "/su/bin/su",
    "/debug_ramdisk/su",
)

private const val ROOT_MAX_MAPS_LINES = 20_000
private const val ROOT_MAX_EVIDENCE_LINES = 20
private const val ROOT_MAX_EVIDENCE_CHARS = 299
private const val IDENTITY_MAX_EVIDENCE_LINES = 20
private const val IDENTITY_MAX_EVIDENCE_CHARS = 299
