package com.system.location.service.hook.scope

enum class HookScope { SYSTEM_SERVER, PHONE_PROCESS, FUSED_PROCESS, VENDOR_LOCATION_PROCESS, TARGET_APP }

data class ProcessIdentity(val packageName: String, val processName: String, val uid: Int,
    val sdk: Int, val manufacturer: String)

object HookScopeResolver {
    fun resolve(identity: ProcessIdentity, targets: Set<String> = emptySet()): HookScope? = with(identity) {
        if (uid < 0 || sdk < 31) return null
        when {
            packageName == "android" && processName in setOf("android", "system_server") && uid == 1000 -> HookScope.SYSTEM_SERVER
            packageName == "com.android.phone" && processName == packageName -> HookScope.PHONE_PROCESS
            packageName == "com.android.location.fused" && processName == packageName -> HookScope.FUSED_PROCESS
            packageName in setOf("com.xiaomi.location.fused", "com.oplus.location") && processName == packageName -> HookScope.VENDOR_LOCATION_PROCESS
            packageName in targets && processName == packageName && uid % 100000 >= 10000 -> HookScope.TARGET_APP
            else -> null
        }
    }
}
