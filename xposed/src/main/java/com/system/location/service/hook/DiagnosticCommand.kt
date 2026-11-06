package com.system.location.service.hook

import android.os.Bundle
import com.system.location.service.hook.scope.HookOwnership
import com.system.location.service.hook.scope.HookStatusRegistry
import com.system.location.service.hook.security.SampleWire
import com.system.location.service.hook.utils.FakeLoc
import java.util.ArrayList

/** P5-08: diagnostics command extracted from RemoteCommandHandler. */
object DiagnosticCommand {
    fun status(rely: Bundle, lastPublishMillis: Long): Boolean {
        rely.putInt("sample_version", SampleWire.VERSION)
        rely.putInt("sample_min_version", SampleWire.MIN_SUPPORTED)
        rely.putBoolean("is_start", FakeLoc.enable)
        rely.putLong("last_publish", lastPublishMillis)
        val statuses = HookStatusRegistry.snapshot()
        val scope = HookStatusRegistry.scope()
        val ownership = scope?.let { HookOwnership.audit(it, statuses) }
        rely.putInt("hook_installed", statuses.values.count { it.installed })
        rely.putInt("hook_matched", statuses.values.count { it.matched })
        rely.putInt("hook_failed", statuses.values.count { it.failed })
        rely.putInt("hook_skipped", statuses.values.count { it.skipped })
        rely.putString("hook_scope", scope?.name ?: "unknown")
        rely.putInt("hook_expected", ownership?.expected?.size ?: 0)
        rely.putInt("hook_missing", ownership?.missing?.size ?: 0)
        SampleCommand.publishStats()?.let { stats ->
            rely.putInt("publish_samples", stats.samples)
            rely.putLong("publish_p50_nanos", stats.p50Nanos)
            rely.putLong("publish_p95_nanos", stats.p95Nanos)
            rely.putLong("publish_max_nanos", stats.maxNanos)
            rely.putLong("publish_mean_nanos", stats.meanNanos)
        }
        rely.putStringArrayList("hook_missing_points", ArrayList(ownership?.missing.orEmpty()))
        rely.putStringArrayList("hook_details", ArrayList(statuses.entries.take(128).map { (point, status) ->
            "$point: supported=${status.supported}, installed=${status.installed}, matched=${status.matched}, " +
                "skipped=${status.skipped}, failed=${status.failed}; ${status.reason.orEmpty()}"
        }))
        return true
    }
}
