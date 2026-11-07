package com.system.location.service.core.privacy

/**
 * P6-02: hide the module's Xposed metadata from ordinary package queries while leaving the
 * system/LSPosed callers able to read it.
 */
object XposedMetadataVisibilityPolicy {
    const val MODULE_PACKAGE = "com.system.location.service"

    val HIDDEN_KEYS = setOf(
        "xposedmodule",
        "xposeddescription",
        "xposedminversion",
        "xposedscope",
        "xposedsharedprefs",
    )

    fun shouldHide(targetPackage: String, callerUid: Int, modulePackage: String = MODULE_PACKAGE): Boolean =
        targetPackage == modulePackage && callerUid != 0 && callerUid != 1000
}
