package com.system.location.service.hook.security

import android.os.Bundle
import com.system.location.service.core.config.SampleEnvelope
import com.system.location.service.core.geo.Wgs84
import com.system.location.service.core.location.LocationSample
import com.system.location.service.core.providers.ProviderKind

/**
 * Versioned primitive-only wire format, independent of class loaders and Parcelable models.
 *
 * v1 carries only the LocationSample fields. v2 adds optional provider/field/environment metadata;
 * readers accept v1 and v2, writers always emit v2. The app checks [isCompatible] instead of
 * pinning a single version so an old module can still feed the new controller until reboot.
 */
object SampleWire {
    const val MIN_SUPPORTED = 1
    const val VERSION = 2

    data class Metadata(
        val provider: ProviderKind? = null,
        val fieldsMask: Int = 0,
        val environmentVersion: Long = 0L,
        val configRevision: Long = 0L,
    )

    fun write(bundle: Bundle, sample: LocationSample, metadata: Metadata = Metadata()) = with(bundle) {
        putInt("sample_version", VERSION)
        putDouble("sample_lat", sample.latitude); putDouble("sample_lon", sample.longitude)
        putDouble("sample_altitude", sample.altitude); putFloat("sample_accuracy", sample.accuracy)
        putFloat("sample_speed", sample.speed); putFloat("sample_bearing", sample.bearing)
        putLong("sample_wall", sample.timeMillis); putLong("sample_elapsed", sample.elapsedNanos)
        putString("sample_provider", metadata.provider?.name ?: "")
        putInt("sample_fields_mask", metadata.fieldsMask)
        putLong("sample_environment_version", metadata.environmentVersion)
        putLong("sample_config_revision", metadata.configRevision)
    }

    fun read(bundle: Bundle): LocationSample? = readEnvelope(bundle)?.sample

    fun readEnvelope(bundle: Bundle): SampleEnvelope? = runCatching {
        val version = bundle.getInt("sample_version", 0)
        require(isCompatible(version))
        val sample = LocationSample(
            Wgs84(bundle.getDouble("sample_lat", Double.NaN), bundle.getDouble("sample_lon", Double.NaN)),
            bundle.getDouble("sample_altitude", Double.NaN),
            bundle.getFloat("sample_accuracy", Float.NaN),
            bundle.getFloat("sample_speed", Float.NaN),
            bundle.getFloat("sample_bearing", Float.NaN),
            bundle.getLong("sample_wall", -1),
            bundle.getLong("sample_elapsed", -1),
        )
        val provider = bundle.getString("sample_provider")
            ?.takeIf { it.isNotBlank() }
            ?.let { name -> ProviderKind.entries.firstOrNull { it.name == name } }
        SampleEnvelope(
            sample = sample,
            provider = provider,
            fieldsMask = bundle.getInt("sample_fields_mask", 0).coerceAtLeast(0),
            environmentVersion = bundle.getLong("sample_environment_version", 0L).coerceAtLeast(0L),
            configRevision = bundle.getLong("sample_config_revision", 0L).coerceAtLeast(0L),
        )
    }.getOrNull()

    fun isCompatible(version: Int): Boolean = version in MIN_SUPPORTED..VERSION
}
