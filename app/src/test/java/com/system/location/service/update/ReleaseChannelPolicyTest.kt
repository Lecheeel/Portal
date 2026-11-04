package com.system.location.service.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test

class ReleaseChannelPolicyTest {
    private fun release(tag: String, prerelease: Boolean, publishedAt: Long = 1) =
        ReleaseChannelPolicy.Release(tag, prerelease, draft = false, publishedAt)

    @Test fun stableChannelNeverSelectsPreRelease() {
        val beta = release("1.9.0.r9.abcdef0", prerelease = true, publishedAt = 300)
        val stable = release("1.8.0.r8.1234567", prerelease = false, publishedAt = 200)
        assertEquals(stable, ReleaseChannelPolicy.select(UpdateChannel.STABLE, listOf(beta, stable)))
        assertNull(ReleaseChannelPolicy.select(UpdateChannel.STABLE, listOf(beta)))
    }

    @Test fun betaChannelSeesBetaAndPromotedStableAndSelectsHighestVersion() {
        val beta = release("1.9.0.r9.abcdef0", prerelease = true, publishedAt = 300)
        val stable = release("1.8.0.r8.1234567", prerelease = false, publishedAt = 200)
        assertEquals(beta, ReleaseChannelPolicy.select(UpdateChannel.BETA, listOf(stable, beta)))
        val promoted = release("1.9.0.r9.abcdef0", prerelease = false, publishedAt = 400)
        assertEquals(promoted, ReleaseChannelPolicy.select(UpdateChannel.BETA, listOf(beta, promoted)))
    }

    @Test fun draftsAndMalformedTagsAreNeverSelected() {
        assertEquals(null, ReleaseChannelPolicy.select(UpdateChannel.BETA, listOf(
            ReleaseChannelPolicy.Release("1.9.0.r9.abcdef0", true, draft = true, 300),
            release("not-a-version", true),
        )))
    }

    @Test fun betaVersionIsNewerThanCurrentStableButStableNeverTreatsBetaAsCandidate() {
        val beta = release("1.9.0.r9.abcdef0", true)
        val currentStable = "1.8.0.r8.1234567"
        assertTrue(ReleaseChannelPolicy.isCandidate(UpdateChannel.BETA, beta, currentStable))
        assertFalse(ReleaseChannelPolicy.isCandidate(UpdateChannel.STABLE, beta, currentStable))
    }

    @Test fun samePromotedBuildDoesNotReinstallWhenChangingChannel() {
        val promoted = release("1.9.0.r9.abcdef0", prerelease = false)
        assertFalse(ReleaseChannelPolicy.isCandidate(UpdateChannel.STABLE, promoted, "1.9.0.r9.abcdef0"))
    }

    @Test fun draftsAndMalformedTagsAreIgnored() {
        assertNull(ReleaseChannelPolicy.select(UpdateChannel.BETA, listOf(
            ReleaseChannelPolicy.Release("1.9.0.r9.abcdef0", true, draft = true, publishedAt = 300),
            release("latest", true),
        )))
    }

    @Test fun betaCanAdvanceFromStableWhileStableRejectsBeta() {
        val beta = release("1.9.0.r9.abcdef0", true)
        val currentStable = "1.8.0.r8.1234567"
        assertTrue(ReleaseChannelPolicy.isCandidate(UpdateChannel.BETA, beta, currentStable))
        assertFalse(ReleaseChannelPolicy.isCandidate(UpdateChannel.STABLE, beta, currentStable))
        assertTrue(ReleaseChannelPolicy.isCandidate(UpdateChannel.STABLE,
            release("1.10.0.r1.1234567", false), "1.9.0.r9.abcdef0"))
    }
}
