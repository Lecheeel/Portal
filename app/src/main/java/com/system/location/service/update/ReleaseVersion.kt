package com.system.location.service.update

internal data class ReleaseVersion(
    val major: Int,
    val minor: Int,
    val patch: Int,
    val revision: Int,
    val hash: String,
) {
    companion object {
        private val pattern = Regex("^v?(\\d+)\\.(\\d+)\\.(\\d+)(?:\\.r(\\d+)\\.([0-9a-fA-F]+))?(?:-debug)?$")

        fun parse(value: String): ReleaseVersion? {
            val match = pattern.matchEntire(value) ?: return null
            val (major, minor, patch, revision, hash) = match.destructured
            return ReleaseVersion(
                major.toIntOrNull() ?: return null,
                minor.toIntOrNull() ?: return null,
                patch.toIntOrNull() ?: return null,
                revision.toIntOrNull() ?: 0,
                hash.lowercase(),
            )
        }

        fun isNewer(remote: String, local: String, publishedAt: Long = 0, localBuildTime: Long = 0): Boolean {
            val candidate = parse(remote) ?: return false
            val installed = parse(local) ?: return false
            val baseComparison = compareValuesBy(candidate, installed, { it.major }, { it.minor }, { it.patch })
            if (baseComparison != 0) return baseComparison > 0
            if (candidate == installed) return false
            if (candidate.hash.isNotEmpty() && candidate.hash == installed.hash) return false
            // Actions uses a shallow checkout, so its .r count may be smaller than a local build's.
            if (publishedAt > 0 && localBuildTime > 0) return publishedAt > localBuildTime
            if (candidate.revision != installed.revision) return candidate.revision > installed.revision
            return candidate.hash.isNotEmpty() && candidate.hash != installed.hash
        }
    }
}
