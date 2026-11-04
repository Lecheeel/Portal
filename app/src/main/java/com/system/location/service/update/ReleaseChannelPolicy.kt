package com.system.location.service.update

internal enum class UpdateChannel { STABLE, BETA }

/** Uses existing tag/versionCode conventions; channel is release metadata, never a version suffix. */
internal object ReleaseChannelPolicy {
    data class Release(val tag: String, val prerelease: Boolean, val draft: Boolean, val publishedAt: Long, val versionCode: Int? = null)

    fun accepts(channel: UpdateChannel, release: Release): Boolean =
        !release.draft && (channel == UpdateChannel.BETA || !release.prerelease) &&
            ReleaseVersion.parse(release.tag)?.let { it.prerelease == null || release.prerelease } == true

    /** Missing metadata is legacy: only a strictly higher semantic version is defensible. */
    fun isInstallable(channel: UpdateChannel, release: Release, installedVersion: String, installedCode: Int): Boolean {
        if (!accepts(channel, release)) return false
        val remote = ReleaseVersion.parse(release.tag) ?: return false
        val local = ReleaseVersion.parse(installedVersion) ?: return false
        return release.versionCode?.let { it > installedCode }
            ?: (compareValuesBy(remote, local, { it.major }, { it.minor }, { it.patch }) > 0)
    }

    fun select(channel: UpdateChannel, releases: List<Release>): Release? = releases
        .asSequence()
        .filter { accepts(channel, it) }
        .sortedWith { left, right ->
            val codeOrder = when {
                left.versionCode != null && right.versionCode != null -> right.versionCode.compareTo(left.versionCode)
                left.versionCode != null -> -1
                right.versionCode != null -> 1
                else -> 0
            }
            if (codeOrder != 0) return@sortedWith codeOrder
            val versionOrder = ReleaseVersion.compare(left.tag, right.tag)
            if (versionOrder != 0) -versionOrder else right.publishedAt.compareTo(left.publishedAt)
        }
        .firstOrNull()

    fun isCandidate(channel: UpdateChannel, release: Release, installedVersion: String): Boolean {
        if (!accepts(channel, release)) return false
        return ReleaseVersion.isNewer(release.tag, installedVersion, release.publishedAt, 0)
    }
}
