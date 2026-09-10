package com.system.location.service.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import com.system.location.service.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * GitHub Release 检查更新
 *
 * 版本约定：Release tag 形如 v1.0.4.r7.af74379，
 * 与 BuildConfig.VERSION_NAME (1.0.4.r<count>.<hash>) 同构。
 * 比对规则：先比 .r<count>（commit 数），相等时比 commit hash 是否一致。
 */
object UpdateChecker {

    private const val TAG = "UpdateChecker"
    private const val RELEASES_LATEST =
        "https://api.github.com/repos/Lecheeel/Portal/releases/latest"
    private const val RELEASES_PAGE = "https://github.com/Lecheeel/Portal/releases"

    data class UpdateInfo(
        val tagName: String,
        val versionName: String,   // tag 去掉 v 前缀
        val body: String,
        val apkUrl: String?,       // 与当前 ABI 匹配的 APK 直链（浏览器可下载）
        val htmlUrl: String,
        val commitCount: Int,
        val commitHash: String,
    )

    sealed class Result {
        data class UpdateAvailable(val info: UpdateInfo) : Result()
        data object UpToDate : Result()
        data class Error(val message: String) : Result()
    }

    suspend fun check(): Result = withContext(Dispatchers.IO) {
        try {
            val conn = URL(RELEASES_LATEST).openConnection() as HttpURLConnection
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            conn.setRequestProperty("User-Agent", "LocationService-App")
            try {
                if (conn.responseCode != 200) {
                    return@withContext Result.Error("GitHub 返回 ${conn.responseCode}")
                }
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                parse(JSONObject(body))?.let { info ->
                    if (isNewer(info)) Result.UpdateAvailable(info) else Result.UpToDate
                } ?: Result.Error("Release 资产里没有可用的 APK")
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            Log.e(TAG, "check failed", e)
            Result.Error(e.message ?: "网络请求失败")
        }
    }

    private fun parse(json: JSONObject): UpdateInfo? {
        val tag = json.optString("tag_name").removePrefix("v")
        // v1.0.4.r7.af74379 / LocationService-v1.0.4.r7.af74379-arm64.apk
        val abi = when (Build.SUPPORTED_ABIS.firstOrNull()) {
            "arm64-v8a" -> "arm64"
            "x86_64", "x86" -> "x86_64"
            else -> "arm64"
        }
        val apkUrl = json.optJSONArray("assets")
            ?.let { assets ->
                (0 until assets.length())
                    .map { assets.getJSONObject(it) }
                    .firstOrNull { it.optString("name").endsWith("-$abi.apk") }
                    ?.optString("browser_download_url")
            }
        if (tag.isBlank()) return null
        val parts = tag.split(".")
        // 1.0.4.r7.af74379 → commitCount=7, hash=af74379
        val rIndex = parts.indexOfFirst { it.startsWith("r") && it.drop(1).toIntOrNull() != null }
        val commitCount = if (rIndex >= 0) parts[rIndex].drop(1).toInt() else 0
        val commitHash = parts.lastOrNull() ?: ""
        return UpdateInfo(
            tagName = json.optString("tag_name"),
            versionName = tag,
            body = json.optString("body"),
            apkUrl = apkUrl,
            htmlUrl = json.optString("html_url").ifBlank { RELEASES_PAGE },
            commitCount = commitCount,
            commitHash = commitHash,
        )
    }

    private fun isNewer(info: UpdateInfo): Boolean {
        val local = parseLocalVersion()
        if (info.commitCount != local.commitCount) {
            return info.commitCount > local.commitCount
        }
        // commit 数相同：commit hash 不同也提示（同一仓库不同构建）
        return info.commitHash.isNotBlank() &&
                !info.commitHash.equals(local.commitHash, ignoreCase = true)
    }

    fun parseLocalVersion(): UpdateInfo {
        val v = BuildConfig.VERSION_NAME  // 1.0.4.r<count>.<hash>
        val parts = v.split(".")
        val rIndex = parts.indexOfFirst { it.startsWith("r") && it.drop(1).toIntOrNull() != null }
        val commitCount = if (rIndex >= 0) parts[rIndex].drop(1).toInt() else 0
        val commitHash = parts.lastOrNull() ?: ""
        return UpdateInfo(
            tagName = "v$v",
            versionName = v,
            body = "",
            apkUrl = null,
            htmlUrl = RELEASES_PAGE,
            commitCount = commitCount,
            commitHash = commitHash,
        )
    }

    fun openDownload(context: Context, info: UpdateInfo) {
        val url = info.apkUrl ?: info.htmlUrl
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }.onFailure {
            Log.e(TAG, "open download failed", it)
        }
    }
}
