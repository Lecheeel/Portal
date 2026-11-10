package com.system.location.service.core.runtime

import com.system.location.service.core.environment.EnvironmentConfig
import com.system.location.service.core.environment.CellMode
import com.system.location.service.core.environment.EnvironmentProfile
import com.system.location.service.core.environment.EnvironmentSourceMode
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
    val environmentSeed: Long = 0L,
    val environmentApCount: Int = 4,
    val cellMode: CellMode = CellMode.FULL_REPLAY,
    val environmentSourceMode: EnvironmentSourceMode = EnvironmentSourceMode.AUTO,
    val simReady: Boolean = true,
    val physicalNoise: Boolean = false,
    val physicalNoiseSeed: Long = 0L,
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val altitudeMeters: Double = 0.0,
    val speedMps: Double = 3.05,
    val accuracyMeters: Float = 25f,
    val bearingDeg: Double = 0.0,
    val hasBearings: Boolean = false,
) {
    init {
        require(configRevision >= 0L) { "configRevision must be non-negative" }
        require(environmentVersion >= 0L) { "environmentVersion must be non-negative" }
        require(environmentApCount in EnvironmentConfig.MIN_AP_PER_CELL..EnvironmentConfig.MAX_AP_PER_CELL) { "environmentApCount out of range" }
        require(latitude in -90.0..90.0 && longitude in -180.0..180.0) { "coordinate out of range" }
        require(altitudeMeters.isFinite()) { "altitudeMeters must be finite" }
        require(speedMps.isFinite() && speedMps >= 0.0) { "speedMps must be non-negative" }
        require(accuracyMeters.isFinite() && accuracyMeters > 0f) { "accuracyMeters must be positive" }
        require(bearingDeg.isFinite()) { "bearingDeg must be finite" }
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
