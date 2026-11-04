package com.system.location.service.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseVersionTest {
    @Test
    fun semanticVersionTakesPriorityOverRevisionCounts() {
        assertTrue(ReleaseVersion.isNewer("1.0.5.r1.abcdef0", "1.0.4.r99.1234567"))
        assertFalse(ReleaseVersion.isNewer("1.0.4.r99.1234567", "1.0.5.r1.abcdef0", 200, 100))
    }

    @Test
    fun numericVersionComparisonHandlesMultipleDigits() {
        assertTrue(ReleaseVersion.isNewer("v1.10.0", "1.9.99"))
        assertFalse(ReleaseVersion.isNewer("1.9.99", "1.10.0"))
        assertTrue(ReleaseVersion.isNewer("2.0.0", "1.99.99"))
    }

    @Test
    fun sameCommitDoesNotPromptAfterPublishingOrWithDifferentCheckoutDepth() {
        assertFalse(ReleaseVersion.isNewer("1.0.5.r1.ABCDEF0", "1.0.5.r20.abcdef0-debug", 200, 100))
    }

    @Test
    fun publicationTimeHandlesShallowActionsCheckouts() {
        assertTrue(ReleaseVersion.isNewer("1.0.5.r1.abcdef0", "1.0.5.r20.1234567", 200, 100))
        assertFalse(ReleaseVersion.isNewer("1.0.5.r1.abcdef0", "1.0.5.r20.1234567", 100, 200))
    }

    @Test
    fun fallsBackToRevisionAndHashWhenNoPublicationTimeIsAvailable() {
        assertTrue(ReleaseVersion.isNewer("1.0.5.r2.abcdef0", "1.0.5.r1.1234567"))
        assertFalse(ReleaseVersion.isNewer("1.0.5.r1.abcdef0", "1.0.5.r1.1234567"))
        assertFalse(ReleaseVersion.isNewer("1.0.5.r1.abcdef0", "1.0.5.r2.1234567"))
    }

    @Test
    fun shallowCheckoutAndSameCommitDoNotUseHashOrRevisionAsVersionOrder() {
        assertFalse(ReleaseVersion.isNewer("1.0.5.r1.abcdef0", "1.0.5.r20.1234567"))
        assertTrue(ReleaseVersion.isNewer("1.0.5.r20.1234567", "1.0.5.r1.abcdef0"))
        assertFalse(ReleaseVersion.isNewer("1.0.5.r20.abcdef0", "1.0.5.r1.abcdef0"))
    }

    @Test
    fun prereleaseOrderingTreatsStableAsPromotionOfSameBinary() {
        assertTrue(ReleaseVersion.isNewer("1.2.0-beta.2", "1.2.0-beta.1"))
        assertTrue(ReleaseVersion.isNewer("1.2.0", "1.2.0-rc.1"))
        assertFalse(ReleaseVersion.isNewer("1.2.0", "1.2.0"))
    }

    @Test
    fun malformedOrEqualVersionsDoNotPrompt() {
        assertFalse(ReleaseVersion.isNewer("latest", "1.0.5"))
        assertFalse(ReleaseVersion.isNewer("1.0.5", "unknown"))
        assertFalse(ReleaseVersion.isNewer("1.0.5", "1.0.5"))
        assertFalse(ReleaseVersion.isNewer("1.0.5", "1.0.5", 200, 100))
        assertFalse(ReleaseVersion.isNewer("999999999999.0.0", "1.0.5"))
    }

    @Test fun betaPrereleasesSortBelowStableAndAboveEarlierBeta() {
        assertTrue(ReleaseVersion.compare("1.8.1-beta.2.r12.abcdef0", "1.8.1-beta.1.r11.1234567") > 0)
        assertTrue(ReleaseVersion.compare("1.8.1", "1.8.1-beta.2.r12.abcdef0") > 0)
        assertTrue(ReleaseVersion.compare("1.8.2-canary.1.r1.1234567", "1.8.1") > 0)
    }

    @Test fun prereleaseIdentifiersUseNumericOrdering() {
        assertTrue(ReleaseVersion.compare("1.8.1-beta.10.r10.abcdef0", "1.8.1-beta.2.r2.1234567") > 0)
        assertTrue(ReleaseVersion.compare("1.8.1-rc.1.r1.abcdef0", "1.8.1-beta.9.r9.1234567") > 0)
    }
}
