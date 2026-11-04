package com.system.location.service.update

import kotlinx.serialization.json.*
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.time.Instant

/** Read-only, bounded GitHub transport. Redirects are validated before following them. */
internal fun interface ReleaseTransport {
    fun get(url: String): Response
    data class Response(val status: Int, val body: String)
}

internal object GitHubReleaseUrls {
    const val API = "https://api.github.com/repos/Lecheeel/Portal/releases"
    const val PAGE = "https://github.com/Lecheeel/Portal/releases"
    fun trusted(url: String, redirect: Boolean = false): Boolean = runCatching {
        val uri = URI(url)
        if (uri.scheme != "https" || uri.rawUserInfo != null || uri.port != -1 || uri.rawFragment != null || uri.normalize() != uri || uri.rawPath != uri.path) return false
        when (uri.host) {
            "api.github.com" -> uri.path == "/repos/Lecheeel/Portal/releases/latest" || uri.path == "/repos/Lecheeel/Portal/releases"
            "github.com" -> (uri.path == "/Lecheeel/Portal/releases" || uri.path.startsWith("/Lecheeel/Portal/releases/")) && uri.rawQuery == null
            "release-assets.githubusercontent.com", "objects.githubusercontent.com" -> redirect
            else -> false
        }
    }.getOrDefault(false)
}

internal class HttpsReleaseTransport : ReleaseTransport {
    override fun get(url: String): ReleaseTransport.Response {
        require(GitHubReleaseUrls.trusted(url)) { "不可信的 GitHub 地址" }
        var target = url
        repeat(4) { hop ->
            require(GitHubReleaseUrls.trusted(target, redirect = hop > 0)) { "不可信的 GitHub 重定向" }
            val connection = URI(target).toURL().openConnection() as HttpURLConnection
            try {
                connection.instanceFollowRedirects = false
                connection.connectTimeout = 5_000
                connection.readTimeout = 5_000
                connection.setRequestProperty("Accept", "application/vnd.github+json")
                connection.setRequestProperty("User-Agent", "Portal-Updater")
                val status = connection.responseCode
                if (status in listOf(301, 302, 303, 307, 308)) {
                    val location = connection.getHeaderField("Location") ?: error("GitHub 重定向缺少地址")
                    target = URI(target).resolve(location).toString()
                } else {
                    if (status != 200) return ReleaseTransport.Response(status, "")
                    require(connection.contentLengthLong <= MAX_BYTES) { "GitHub 响应过大" }
                    val bytes = connection.inputStream.use { stream ->
                        val out = java.io.ByteArrayOutputStream()
                        val buffer = ByteArray(8192)
                        val deadline = System.nanoTime() + 10_000_000_000L
                        while (true) {
                            check(System.nanoTime() < deadline) { "GitHub 响应超时" }
                            val count = stream.read(buffer)
                            if (count < 0) break
                            require(out.size() + count <= MAX_BYTES) { "GitHub 响应过大" }
                            out.write(buffer, 0, count)
                        }
                        out.toByteArray()
                    }
                    return ReleaseTransport.Response(status, bytes.toString(Charsets.UTF_8))
                }
            } finally {
                connection.disconnect()
            }
        }
        error("GitHub 重定向次数过多")
    }
    private companion object { const val MAX_BYTES = 2 * 1024 * 1024 }
}

/** Android-free seam; malformed present metadata always fails, never becomes legacy. */
internal class ReleaseFeed(private val transport: ReleaseTransport) {
    data class Asset(val name: String, val url: String)
    data class Release(
        val tag: String, val body: String, val htmlUrl: String,
        val prerelease: Boolean, val draft: Boolean, val publishedAt: Long, val assets: List<Asset>,
        val versionCode: Int? = null,
    ) {
        fun policy() = ReleaseChannelPolicy.Release(tag, prerelease, draft, publishedAt, versionCode)
        fun apk(abi: String) = assets.singleOrNull { it.name == "LocationService-v${tag.removePrefix("v")}-${abi}.apk" }
    }

    fun load(channel: UpdateChannel, abi: String): List<Release> {
        val latest = transport.get("${GitHubReleaseUrls.API}/latest")
        val stable = when (latest.status) {
            200 -> parseRelease(Json.parseToJsonElement(latest.body).jsonObject).also {
                require(!it.prerelease && !it.draft) { "稳定版接口返回了非稳定版本" }
            }
            404 -> null
            else -> error("GitHub 返回 ${latest.status}")
        }
        val releases = mutableListOf<Release>()
        if (stable != null) releases += stable
        if (channel == UpdateChannel.BETA) {
            for (page in 1..MAX_PAGES) {
                val response = transport.get("${GitHubReleaseUrls.API}?per_page=$PAGE_SIZE&page=$page")
                check(response.status == 200) { "GitHub 返回 ${response.status}（Beta 第 $page 页）" }
                val entries = Json.parseToJsonElement(response.body).jsonArray
                require(entries.size <= PAGE_SIZE) { "GitHub 分页响应过大" }
                releases += entries.map { parseRelease(it.jsonObject) }
                if (entries.size < PAGE_SIZE) break
            }
        }
        return releases.distinctBy { it.tag }.filter {
            ReleaseChannelPolicy.accepts(channel, it.policy()) && it.apk(abi) != null
        }.map { release ->
            val metadata = release.assets.filter { it.name == "verification.json" }
            require(metadata.size <= 1) { "重复的 verification.json" }
            if (metadata.isEmpty()) release else {
                val response = transport.get(metadata.single().url)
                check(response.status == 200) { "verification.json 下载失败：${response.status}" }
                release.copy(versionCode = verificationCode(response.body, release, abi))
            }
        }
    }

    private fun parseRelease(json: JsonObject): Release {
        val tag = json.string("tag_name")
        val html = json.string("html_url")
        require(GitHubReleaseUrls.trusted(html) && URI(html).path == "/Lecheeel/Portal/releases/tag/$tag") { "无效的 Release 页面" }
        val assets = json.getValue("assets").jsonArray.map { value ->
            val asset = value.jsonObject
            val name = asset.string("name")
            val url = asset.string("browser_download_url")
            require(GitHubReleaseUrls.trusted(url) && URI(url).path == "/Lecheeel/Portal/releases/download/$tag/$name") { "无效的 Release 资源地址" }
            Asset(name, url)
        }
        require(assets.map { it.name }.distinct().size == assets.size) { "重复的 Release 资源" }
        return Release(tag, json["body"]?.let { if (it == JsonNull) "" else it.jsonPrimitive.content }.orEmpty(), html,
            json.boolean("prerelease"), json.boolean("draft"),
            json["published_at"]?.let { if (it == JsonNull) 0 else Instant.parse(it.jsonPrimitive.content).epochSecond } ?: 0, assets)
    }

    internal fun verificationCode(text: String, release: Release, abi: String): Int {
        val report = Json.parseToJsonElement(text).jsonObject
        require(report.string("version") == release.tag.removePrefix("v")) { "verification.json 版本不匹配" }
        // Channel is historical: promotion deliberately preserves the exact Beta report and APK.
        report["channel"]?.let { require(it.jsonPrimitive.isString && it.jsonPrimitive.content in setOf("stable", "beta")) { "verification.json 通道无效" } }
        require(report.string("certificateSha256").lowercase() == CERTIFICATE) { "verification.json 签名证书不匹配" }
        val code = report.getValue("versionCode").jsonPrimitive
        require(!code.isString && Regex("[1-9][0-9]*").matches(code.content)) { "verification.json versionCode 无效" }
        val versionCode = code.content.toIntOrNull() ?: error("verification.json versionCode 越界")
        val artifacts = report.getValue("artifacts").jsonArray.map { it.jsonObject }
        require(artifacts.map { it.string("file") }.distinct().size == artifacts.size) { "verification.json 包含重复资源" }
        val artifact = artifacts.single { it.string("file") == release.apk(abi)?.name }
        require(Regex("[0-9a-fA-F]{64}").matches(artifact.string("sha256"))) { "verification.json SHA-256 无效" }
        val bytes = artifact.getValue("bytes").jsonPrimitive
        require(!bytes.isString && bytes.longOrNull?.let { it > 0 } == true) { "verification.json 文件大小无效" }
        val expectedAbi = when (abi) { "arm64" -> "arm64-v8a"; "x86_64" -> "x86_64"; else -> error("不支持的 ABI") }
        require(artifact.getValue("abis").jsonArray.map { it.jsonPrimitive.content } == listOf(expectedAbi)) { "verification.json ABI 不匹配" }
        return versionCode
    }

    private fun JsonObject.string(key: String): String = getValue(key).jsonPrimitive.let {
        require(it.isString) { "$key 必须为字符串" }; it.content
    }
    private fun JsonObject.boolean(key: String): Boolean = getValue(key).jsonPrimitive.let {
        require(!it.isString) { "$key 必须为布尔值" }; it.boolean
    }
    companion object {
        const val MAX_PAGES = 3
        const val PAGE_SIZE = 30
        const val CERTIFICATE = "fa4547514e795b2daca48bd05e9a5f9348e62531d791d746e0ffe93bb90247a3"
    }
}
