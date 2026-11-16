package com.system.location.service.hook

import android.os.Bundle
import com.system.location.service.core.runtime.PreparedRuntimeConfiguration
import com.system.location.service.core.runtime.RuntimeSnapshot
import com.system.location.service.hook.hooks.telephony.CarrierConfigGlobalController
import com.system.location.service.hook.utils.FakeLoc

/** Authenticated configuration is prepared completely before one atomic publication. */
object ConfigCommand {
    @Suppress("DEPRECATION")
    fun apply(rely: Bundle): Boolean {
        val published = try {
            val values = PreparedRuntimeConfiguration.keys.filter(rely::containsKey).associateWith { rely.get(it) }
            publish(values)
        } catch (_: Exception) { return false }
        if (published.configuration.enabled) LocationTicker.start() else {
            SampleCommand.clearLease()
            LocationTicker.stop()
            NativeCommand.disable()
        }
        return true
    }

    internal fun publish(values: Map<String, Any?>): RuntimeSnapshot {
        val store = FakeLoc.runtimeSnapshots
        val prepared = PreparedRuntimeConfiguration.prepare(values, store.current().configuration.gnssSource)
        val published = FakeLoc.configureRuntime(prepared)
        runCatching { CarrierConfigGlobalController.onConfigurationChanged(published) }
            .onFailure { com.system.location.service.hook.utils.Logger.warn("Global carrier identity failed: ${it.message}") }
        return published
    }
}
