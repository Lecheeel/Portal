package com.system.location.service.hook

import android.os.Bundle
import com.system.location.service.core.runtime.PreparedRuntimeConfiguration
import com.system.location.service.core.runtime.RuntimeSnapshot
import com.system.location.service.hook.utils.FakeLoc

/** Authenticated configuration is prepared completely before one atomic publication. */
object ConfigCommand {
    @Suppress("DEPRECATION")
    fun apply(rely: Bundle): Boolean {
        val published = try {
            val values = PreparedRuntimeConfiguration.keys.filter(rely::containsKey).associateWith { rely.get(it) }
            publish(values)
        } catch (_: Exception) { return false }
        if (published.configuration.enabled) LocationTicker.start()
        return true
    }

    internal fun publish(values: Map<String, Any?>): RuntimeSnapshot {
        val store = FakeLoc.runtimeSnapshots
        val prepared = PreparedRuntimeConfiguration.prepare(values, store.current().configuration.gnssSource)
        return store.update(prepared::applyTo)
    }
}
