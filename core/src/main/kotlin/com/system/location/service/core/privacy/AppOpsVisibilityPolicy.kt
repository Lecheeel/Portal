package com.system.location.service.core.privacy

/** Pure AppOps visibility rule shared by the hook and tests (P1-02). */
object AppOpsVisibilityPolicy {
    /** AppOpsManager.OP_MOCK_LOCATION is @hide; stable since Android 6. */
    const val OP_MOCK_LOCATION = 58
    const val FIRST_APP_UID = 10_000

    fun shouldHideMockLocation(op: Int, queriedUid: Int, callerUid: Int): Boolean =
        op == OP_MOCK_LOCATION && callerUid >= FIRST_APP_UID && queriedUid != callerUid
}
