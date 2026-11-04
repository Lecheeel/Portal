package com.system.location.service.update

internal data class ReleaseVersion(
    val major: Int,
    val minor: Int,
    val patch: Int,
    val revision: Int,
    val hash: String,
    val prerelease: String?,
) {
    companion object {
        private val pattern = Regex("""^v?(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)(?:-([0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*?))?(?:\.r(0|[1-9][0-9]*)\.([0-9a-fA-F]+))?$""")

        fun parse(value: String): ReleaseVersion? {
            val clean = value.removeSuffix("-debug")
            val match = pattern.matchEntire(clean) ?: return null
            val (major, minor, patch, prerelease, revision, hash) = match.destructured
            if (Regex("""\.r[0-9]+\.[0-9a-fA-F]+$""").containsMatchIn(clean) && revision.isEmpty()) return null
            if (prerelease.split('.').any { it.length > 1 && it.all(Char::isDigit) && it.startsWith('0') }) return null
            if (revision.isNotEmpty() && revision.toIntOrNull() == null) return null
            return ReleaseVersion(
                major.toIntOrNull() ?: return null,
                minor.toIntOrNull() ?: return null,
                patch.toIntOrNull() ?: return null,
                revision.toIntOrNull() ?: 0,
                hash.lowercase(),
                prerelease.ifEmpty { null },
            )
        }

        fun isNewer(remote: String, local: String, publishedAt: Long = 0, localBuildTime: Long = 0): Boolean {
            val candidate = parse(remote) ?: return false
            val installed = parse(local) ?: return false
            val semantic = compareSemantic(candidate, installed)
            if (semantic != 0) return semantic > 0
            if (candidate == installed) return false
            if (candidate.prerelease != installed.prerelease) return candidate.prerelease != null
            if (candidate.hash.isNotEmpty() && candidate.hash == installed.hash) return false
            // Release revisions are useful only with timestamps: shallow checkouts can have lower counts.
            if (publishedAt > 0 && localBuildTime > 0) return publishedAt > localBuildTime
            return candidate.revision > installed.revision
        }

        fun compare(left: String, right: String): Int {
            val a = parse(left)
            val b = parse(right)
            if (a == null) return if (b == null) 0 else -1
            if (b == null) return 1
            val semantic = compareSemantic(a, b)
            if (semantic != 0) return semantic
            val preOrder = when {
                a.prerelease == null && b.prerelease == null -> 0
                a.prerelease == null -> 1
                b.prerelease == null -> -1
                else -> comparePrerelease(a.prerelease, b.prerelease)
            }
            return if (preOrder != 0) preOrder else a.revision.compareTo(b.revision)
        }

        private fun compareSemantic(a: ReleaseVersion, b: ReleaseVersion): Int {
            val base = compareValuesBy(a, b, { it.major }, { it.minor }, { it.patch })
            if (base != 0) return base
            if (a.prerelease == null) return if (b.prerelease == null) 0 else 1
            if (b.prerelease == null) return -1
            return comparePrerelease(a.prerelease, b.prerelease)
        }

        private fun comparePrerelease(a: String, b: String): Int {
            val left = a.split('.')
            val right = b.split('.')
            for (index in 0 until minOf(left.size, right.size)) {
                val l = left[index]
                val r = right[index]
                val ln = l.all(Char::isDigit)
                val rn = r.all(Char::isDigit)
                val order = when {
                    ln && rn -> l.length.compareTo(r.length).takeIf { it != 0 } ?: l.compareTo(r)
                    ln -> -1
                    rn -> 1
                    else -> l.compareTo(r)
                }
                if (order != 0) return order
            }
            return left.size.compareTo(right.size)
        }
    }
}
