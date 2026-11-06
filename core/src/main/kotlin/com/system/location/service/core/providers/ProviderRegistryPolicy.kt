package com.system.location.service.core.providers

/** Public provider-registry filtering policy shared by the hook and tests (P1-04). */
object ProviderRegistryPolicy {
    private val HIDDEN_MARKERS = listOf("fused_ext", "test", "mock", "portal")

    fun isHidden(name: String): Boolean {
        val lower = name.lowercase()
        return HIDDEN_MARKERS.any { lower.contains(it) }
    }
}
