package com.system.location.service.core

import com.system.location.service.core.privacy.AppOpsVisibilityPolicy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppOpsVisibilityPolicyTest {
    private val otherOp = 1
    private val thirdPartyUid = 10_500
    private val targetUid = 10_100

    @Test fun hidesCrossPackageThirdPartyQueries() {
        assertTrue(AppOpsVisibilityPolicy.shouldHideMockLocation(
            AppOpsVisibilityPolicy.OP_MOCK_LOCATION, targetUid, thirdPartyUid,
        ))
    }

    @Test fun keepsSystemSelfAndOtherOps() {
        assertFalse(AppOpsVisibilityPolicy.shouldHideMockLocation(
            AppOpsVisibilityPolicy.OP_MOCK_LOCATION, targetUid, 1000,
        ))
        assertFalse(AppOpsVisibilityPolicy.shouldHideMockLocation(
            AppOpsVisibilityPolicy.OP_MOCK_LOCATION, targetUid, targetUid,
        ))
        assertFalse(AppOpsVisibilityPolicy.shouldHideMockLocation(otherOp, targetUid, thirdPartyUid))
    }
}
