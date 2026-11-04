package com.system.location.service.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import com.system.location.service.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext

/** GitHub Release 检查更新；已验证的 Android versionCode 是安装顺序依据。 */
object UpdateChecker {

    private const val TAG = "UpdateChecker"
    private const val RELEASES_API = "https://api.github.com/repos/Lecheeel/Portal/releases"
    private const val RELEASES_PAGE = "https://github.com/Lecheeel/Portal/releases"

    data class UpdateInfo(
        val tagName: String,
        val versionName: String,   // tag 去掉 v 前缀
        val body: String,
        val apkUrl: String?,       // 与当前 ABI 匹配的 APK 直链（浏览器可下载）
        val htmlUrl: String,
        val commitCount: Int,
        val commitHash: String,
        val publishedAt: Long = 0,
        val prerelease: Boolean = false,
        val draft: Boolean = false,
    )

    sealed class Result {
        data class UpdateAvailable(val info: UpdateInfo) : Result()
        data object UpToDate : Result()
        data class Error(val message: String) : Result()
    }

    internal suspend fun check(channel: UpdateChannel = currentChannel()): Result = withContext(Dispatchers.IO) {
        try {
            val abi = when (Build.SUPPORTED_ABIS.firstOrNull()) {
                "arm64-v8a" -> "arm64"
                "x86_64" -> "x86_64"
                else -> return@withContext Result.Error("当前设备 ABI 不受支持")
            }
            val releases = ReleaseFeed(HttpsReleaseTransport()).load(channel, abi)
            if (releases.isEmpty()) return@withContext Result.Error("所选通道中没有可用的 APK")
            val candidates = releases.filter {
                ReleaseChannelPolicy.isInstallable(channel, it.policy(), BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE)
            }
            // Verified Android codes are authoritative; equal-code promotion never prompts again.
            val selected = candidates.filter { it.versionCode != null }.maxByOrNull { it.versionCode!! }
                ?: ReleaseChannelPolicy.select(channel, candidates.map { it.policy() })?.let { policy ->
                    candidates.first { it.tag == policy.tag }
                }
            if (selected == null) return@withContext Result.UpToDate
            val parsed = ReleaseVersion.parse(selected.tag) ?: return@withContext Result.Error("Release 版本无效")
            Result.UpdateAvailable(UpdateInfo(
                tagName = selected.tag,
                versionName = selected.tag.removePrefix("v"),
                body = selected.body,
                // Legacy metadata cannot prove installability. Open its Release page for manual review.
                apkUrl = if (selected.versionCode == null) null else selected.apk(abi)?.url,
                htmlUrl = selected.htmlUrl,
                commitCount = parsed.revision,
                commitHash = parsed.hash,
                publishedAt = selected.publishedAt,
                prerelease = selected.prerelease,
                draft = selected.draft,
            ))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "check failed", e)
            Result.Error(e.message ?: "网络请求失败")
        }
    }

    private fun applicationContext(): Context = com.system.location.service.LocationServiceApp.appContext

    internal fun currentChannel() = UpdateChannelSettings.from(applicationContext()).get()

    internal fun setChannel(channel: UpdateChannel) = UpdateChannelSettings.from(applicationContext()).set(channel)

    fun parseLocalVersion(): UpdateInfo {
        val v = BuildConfig.VERSION_NAME  // 1.0.4.r<count>.<hash>
        val parts = v.split(".")
        val rIndex = parts.indexOfFirst { it.startsWith("r") && it.drop(1).toIntOrNull() != null }
        val commitCount = if (rIndex >= 0) parts[rIndex].drop(1).toInt() else 0
        val commitHash = BuildConfig.GIT_REVISION
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
        openDownload(context, info.apkUrl ?: info.htmlUrl)
    }

    fun openDownload(context: Context, url: String) {
        runCatching {
            require(GitHubReleaseUrls.trusted(url)) { "不可信的下载地址" }
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }.onFailure {
            Log.e(TAG, "open download failed", it)
        }
    }
}
