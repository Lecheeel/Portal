package com.system.location.service.core.runtime

import com.system.location.service.core.environment.EnvironmentProfile
import com.system.location.service.core.gnss.GnssModelMode
import com.system.location.service.core.location.LocationSample
import com.system.location.service.core.providers.ProviderKind
import com.system.location.service.core.routing.RoutingConfig
import java.util.concurrent.atomic.AtomicReference
import kotlinx.serialization.Serializable

/** P5-07: immutable hand-off between config/sample/environment producers and hook readers. */
@Serializable
data class RuntimeSnapshot(
    val configRevision: Long = 0L,
    val environmentVersion: Long = 0L,
    val sample: LocationSample? = null,
    val environment: EnvironmentProfile? = null,
    val provider: ProviderKind? = null,
    val routing: RoutingConfig? = null,
    val gnssModel: GnssModelMode = GnssModelMode.ANALYTIC,
) {
    init {
        require(configRevision >= 0L) { "configRevision must be non-negative" }
        require(environmentVersion >= 0L) { "environmentVersion must be non-negative" }
    }

    companion object {
        val EMPTY = RuntimeSnapshot()
    }
}

/**
 * Atomic snapshot holder. Readers never observe a half-updated sample/config/environment; the
 * mutable [com.system.location.service.hook.utils.FakeLoc] can be migrated to this incrementally.
 */
class RuntimeSnapshotStore(initial: RuntimeSnapshot = RuntimeSnapshot.EMPTY) {
    private val ref = AtomicReference(initial)

    fun current(): RuntimeSnapshot = ref.get()

    fun set(snapshot: RuntimeSnapshot) {
        ref.set(snapshot)
    }

    fun update(transform: (RuntimeSnapshot) -> RuntimeSnapshot): RuntimeSnapshot =
        ref.updateAndGet(transform)

    fun publishSample(
        sample: LocationSample,
        provider: ProviderKind? = null,
        configRevision: Long = current().configRevision,
    ): RuntimeSnapshot = update {
        it.copy(sample = sample, provider = provider, configRevision = configRevision)
    }

    fun clearSample(): RuntimeSnapshot = update { it.copy(sample = null, provider = null) }
}
