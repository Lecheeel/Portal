package com.system.location.service.hook.security

import android.os.Bundle
import com.system.location.service.core.geo.Wgs84
import com.system.location.service.core.location.LocationSample

/** Versioned primitive-only wire format, independent of class loaders and Parcelable models. */
object SampleWire {
    const val VERSION = 1
    fun write(bundle: Bundle, sample: LocationSample) = with(bundle) {
        putInt("sample_version", VERSION)
        putDouble("sample_lat", sample.latitude); putDouble("sample_lon", sample.longitude)
        putDouble("sample_altitude", sample.altitude); putFloat("sample_accuracy", sample.accuracy)
        putFloat("sample_speed", sample.speed); putFloat("sample_bearing", sample.bearing)
        putLong("sample_wall", sample.timeMillis); putLong("sample_elapsed", sample.elapsedNanos)
    }
    fun read(bundle: Bundle): LocationSample? = runCatching {
        require(bundle.getInt("sample_version") == VERSION)
        LocationSample(Wgs84(bundle.getDouble("sample_lat", Double.NaN), bundle.getDouble("sample_lon", Double.NaN)),
            bundle.getDouble("sample_altitude", Double.NaN), bundle.getFloat("sample_accuracy", Float.NaN),
            bundle.getFloat("sample_speed", Float.NaN), bundle.getFloat("sample_bearing", Float.NaN),
            bundle.getLong("sample_wall", -1), bundle.getLong("sample_elapsed", -1))
    }.getOrNull()
}
