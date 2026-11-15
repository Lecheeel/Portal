package com.system.location.service.core.privacy

/** Pure AppOps visibility rule shared by the hook and tests (P1-02). */
object AppOpsVisibilityPolicy {
    /** AppOpsManager.OP_MOCK_LOCATION is @hide; stable since Android 6. */
    const val OP_MOCK_LOCATION = 58
    const val FIRST_APP_UID = 10_000

    /**
     * The mode reported to third-party callers for a hidden mock_location op.
     *
     * This deliberately equals the system baseline rather than an invented sentinel: on AOSP,
     * `AppOpsManager` resolves `OP_MOCK_LOCATION` to `MODE_ERRORED` for packages that are not
     * the configured mock-location provider, which is also why
     * `LocationManagerService`'s `noteOp` throws `SecurityException` for them. Reporting
     * `MODE_ERRORED` therefore answers "this package is an ordinary non-mock app" — the same
     * answer the device gives for every regular package. Device baseline comparison remains a
     * required verification step (docs/anti-detection.md).
     */
    const val HIDDEN_MODE_BASELINE = "MODE_ERRORED"

    fun shouldHideMockLocation(op: Int, queriedUid: Int, callerUid: Int): Boolean =
        op == OP_MOCK_LOCATION && callerUid >= FIRST_APP_UID && queriedUid != callerUid
}
