package com.system.location.service.update

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ReleaseTransportTest {
    private val tag = "v1.9.0.r10.abcdef0"
    private val apk = "LocationService-$tag-arm64.apk"
    private fun release(metadata: Boolean = true, version: String = tag): String = buildJsonObject {
        put("tag_name", version); put("html_url", "${GitHubReleaseUrls.PAGE}/tag/$version")
        put("prerelease", false); put("draft", false); put("body", "notes"); put("published_at", "2026-01-01T00:00:00Z")
        putJsonArray("assets") {
            for (name in listOf("LocationService-$version-arm64.apk") + if (metadata) listOf("verification.json") else emptyList()) add(buildJsonObject {
                put("name", name); put("browser_download_url", "${GitHubReleaseUrls.PAGE}/download/$version/$name")
            })
        }
    }.toString()
    private fun report(code: JsonElement = JsonPrimitive(200)) = buildJsonObject {
        put("version", tag.removePrefix("v")); put("versionCode", code); put("channel", "beta")
        put("certificateSha256", ReleaseFeed.CERTIFICATE)
        putJsonArray("artifacts") { add(buildJsonObject {
            put("file", apk); put("sha256", "a".repeat(64)); put("bytes", 1024)
            putJsonArray("abis") { add("arm64-v8a") }
        }) }
    }.toString()
    private fun failed(block: () -> Unit) { try { block(); fail("Expected failure") } catch (e: Exception) { } }

    @Test fun stableOnlyUsesLatestAndAcceptsPreservedBetaReportAfterPromotion() {
        val requests = mutableListOf<String>()
        val feed = ReleaseFeed(ReleaseTransport { url ->
            requests += url
            ReleaseTransport.Response(200, if (url.endsWith("latest")) release() else report())
        })
        val result = feed.load(UpdateChannel.STABLE, "arm64").single()
        assertEquals(200, result.versionCode)
        assertEquals(2, requests.size)
        assertTrue(requests.first().endsWith("/latest"))
    }
    @Test fun acceptsHistoricalVerificationWithoutChannelField() {
        val historical = JsonObject(Json.parseToJsonElement(report()).jsonObject.filterKeys { it != "channel" }).toString()
        val feed = ReleaseFeed(ReleaseTransport { url ->
            ReleaseTransport.Response(200, if (url.endsWith("latest")) release() else historical)
        })
        assertEquals(200, feed.load(UpdateChannel.STABLE, "arm64").single().versionCode)
    }
    @Test fun betaBoundsPagesAndKeepsLatestOutsideScan() {
        val requests = mutableListOf<String>()
        val feed = ReleaseFeed(ReleaseTransport { url ->
            requests += url
            ReleaseTransport.Response(200, when {
                url.endsWith("latest") -> release(false)
                else -> "[" + List(ReleaseFeed.PAGE_SIZE) { release(false, "v1.8.0.r1.1234567") }.joinToString(",") + "]"
            })
        })
        assertEquals(2, feed.load(UpdateChannel.BETA, "arm64").size)
        assertEquals(1 + ReleaseFeed.MAX_PAGES, requests.size)
    }
    @Test fun metadataMissingIsExplicitLegacyButMissingDownloadIsFailure() {
        val legacy = ReleaseFeed(ReleaseTransport { ReleaseTransport.Response(200, release(false)) })
        assertNull(legacy.load(UpdateChannel.STABLE, "arm64").single().versionCode)
        val broken = ReleaseFeed(ReleaseTransport { url -> if (url.endsWith("latest")) ReleaseTransport.Response(200, release()) else ReleaseTransport.Response(404, "") })
        failed { broken.load(UpdateChannel.STABLE, "arm64") }
    }
    @Test fun malformedMetadataNeverFallsBack() {
        for (invalid in listOf("{}", report(JsonPrimitive("200")), report(JsonPrimitive(0)), report(JsonPrimitive(2147483648L)), report().replace("abcdef0", "abcdef1"), report().replace(ReleaseFeed.CERTIFICATE, "0".repeat(64)), report().replace("arm64-v8a", "x86_64"))) {
            val feed = ReleaseFeed(ReleaseTransport { url -> ReleaseTransport.Response(200, if (url.endsWith("latest")) release() else invalid) })
            failed { feed.load(UpdateChannel.STABLE, "arm64") }
        }
    }
    @Test fun lowerAndEqualCodesNeverInstallEvenWithNewerNamesOrPublication() {
        for (code in listOf(99, 100)) assertFalse(ReleaseChannelPolicy.isInstallable(UpdateChannel.BETA,
            ReleaseChannelPolicy.Release(tag, false, false, Long.MAX_VALUE, code), "1.0.0", 100))
        assertTrue(ReleaseChannelPolicy.isInstallable(UpdateChannel.STABLE,
            ReleaseChannelPolicy.Release("1.8.0", false, false, 1, 101), "1.9.0", 100))
    }
    @Test fun legacyRequiresHigherSemanticBaseAndRejectsSameBuildAndChannelDowngrade() {
        assertFalse(ReleaseChannelPolicy.isInstallable(UpdateChannel.BETA,
            ReleaseChannelPolicy.Release(tag, false, false, 999), "1.9.0.r1.1234567", 100))
        assertFalse(ReleaseChannelPolicy.isInstallable(UpdateChannel.STABLE,
            ReleaseChannelPolicy.Release("1.8.0", false, false, 999), "1.9.0", 100))
        assertTrue(ReleaseChannelPolicy.isInstallable(UpdateChannel.STABLE,
            ReleaseChannelPolicy.Release("1.10.0", false, false, 999), "1.9.0", 100))
    }
    @Test fun rejectsUntrustedUrls() {
        for (url in listOf("http://github.com/Lecheeel/Portal/releases/latest", "https://github.com.evil/Lecheeel/Portal/releases/latest", "https://user@github.com/Lecheeel/Portal/releases/latest", "https://github.com:444/Lecheeel/Portal/releases/latest", "https://github.com/other/Portal/releases/latest", "https://evil.test/file", "https://objects.githubusercontent.com/file")) assertFalse(url, GitHubReleaseUrls.trusted(url))
        assertTrue(GitHubReleaseUrls.trusted("https://release-assets.githubusercontent.com/file?token=x", true))
    }
    @Test fun paginationFailureIsNotUpToDate() {
        val feed = ReleaseFeed(ReleaseTransport { url -> if (url.endsWith("latest")) ReleaseTransport.Response(200, release(false)) else ReleaseTransport.Response(403, "") })
        failed { feed.load(UpdateChannel.BETA, "arm64") }
    }
    @Test fun strictSemanticsAndHugeNumericPrereleases() {
        assertEquals("beta.2", ReleaseVersion.parse("1.2.3-beta.2.r10.abcdef0")?.prerelease)
        assertEquals(10, ReleaseVersion.parse("1.2.3-beta.2.r10.abcdef0")?.revision)
        for (value in listOf("01.2.3", "1.2.3-beta.01", "1.2.3-beta..1", "1.2.3.r999999999999.abc", "1.2.3-beta.r999999999999.abc")) assertNull(value, ReleaseVersion.parse(value))
        assertTrue(ReleaseVersion.compare("1.2.3-beta.100000000000000000000", "1.2.3-beta.99999999999999999999") > 0)
        assertEquals(0, ReleaseVersion.compare("1.2.3.r1.fffffff", "1.2.3.r1.aaaaaaa"))
    }
}
