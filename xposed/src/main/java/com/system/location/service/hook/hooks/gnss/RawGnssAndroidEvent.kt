package com.system.location.service.hook.hooks.gnss

import android.location.GnssClock
import android.location.GnssMeasurement
import android.location.GnssMeasurementsEvent
import com.system.location.service.core.gnss.Constellation
import com.system.location.service.core.gnss.GnssMeasurementBatch
import com.system.location.service.core.gnss.GnssMeasurementObservation
import com.system.location.service.core.gnss.GpsL1RawMeasurementSynthesizer
import com.system.location.service.hook.runtime.Reflection
import com.system.location.service.hook.utils.Logger

/**
 * Builds the hidden Android raw-observation objects from the same batch that the independent
 * solver test validates. Every field is set through the framework's own setters so their presence
 * flags stay coherent; a failed required field rejects the whole event rather than exposing a
 * half-populated observation.
 */
internal object RawGnssAndroidEvent {
    fun create(batch: GnssMeasurementBatch): GnssMeasurementsEvent? = try {
        val clock = clock(batch) ?: return null
        val measurements = batch.measurements.mapNotNull(::measurement)
        if (measurements.size < GpsL1RawMeasurementSynthesizer.MIN_MEASUREMENTS) {
            Logger.warn("Synthetic raw event incomplete: measurements=${measurements.size}")
            return null
        }
        event(clock, measurements)
    } catch (failure: Throwable) {
        Logger.error("Synthetic raw event creation failed", failure)
        null
    }

    private fun clock(batch: GnssMeasurementBatch): GnssClock? = try {
        val clock = Reflection.newInstance(GnssClock::class.java) as GnssClock
        require(set(clock, "setTimeNanos", batch.timeNanos))
        require(set(clock, "setHardwareClockDiscontinuityCount", batch.hardwareClockDiscontinuityCount))
        batch.fullBiasNanos?.let { require(set(clock, "setFullBiasNanos", it)) }
        batch.biasNanos?.let { set(clock, "setBiasNanos", it) }
        batch.timeUncertaintyNanos?.let { set(clock, "setTimeUncertaintyNanos", it) }
        batch.biasUncertaintyNanos?.let { set(clock, "setBiasUncertaintyNanos", it) }
        batch.driftNanosPerSecond?.let { set(clock, "setDriftNanosPerSecond", it) }
        batch.driftUncertaintyNanosPerSecond?.let { set(clock, "setDriftUncertaintyNanosPerSecond", it) }
        batch.elapsedRealtimeNanos?.let { require(set(clock, "setElapsedRealtimeNanos", it)) }
        batch.elapsedRealtimeUncertaintyNanos?.let { set(clock, "setElapsedRealtimeUncertaintyNanos", it) }
        batch.leapSecond?.let { set(clock, "setLeapSecond", it) }
        batch.referenceConstellationTypeForIsb?.let {
            set(clock, "setReferenceConstellationTypeForIsb", it)
        }
        batch.referenceCarrierHzForIsb?.let {
            set(clock, "setReferenceCarrierFrequencyHzForIsb", it)
        }
        batch.referenceCodeTypeForIsb?.let { set(clock, "setReferenceCodeTypeForIsb", it) }
        clock
    } catch (failure: Throwable) {
        Logger.error("Synthetic raw clock conversion failed", failure)
        null
    }

    private fun measurement(observation: GnssMeasurementObservation): GnssMeasurement? = try {
        val measurement = Reflection.newInstance(GnssMeasurement::class.java) as GnssMeasurement
        require(set(measurement, "setSvid", observation.svid))
        require(set(measurement, "setConstellationType", constellationType(observation.constellation)))
        require(set(measurement, "setTimeOffsetNanos", observation.timeOffsetNanos))
        require(set(measurement, "setState", observation.state))
        require(set(measurement, "setReceivedSvTimeNanos", observation.receivedSvTimeNanos))
        require(set(measurement, "setReceivedSvTimeUncertaintyNanos",
            observation.receivedSvTimeUncertaintyNanos))
        require(set(measurement, "setCn0DbHz", observation.cn0DbHz))
        require(set(measurement, "setPseudorangeRateMetersPerSecond",
            observation.pseudorangeRateMetersPerSecond))
        require(set(measurement, "setPseudorangeRateUncertaintyMetersPerSecond",
            observation.pseudorangeRateUncertaintyMetersPerSecond))
        require(set(measurement, "setAccumulatedDeltaRangeState",
            observation.accumulatedDeltaRangeState))
        require(set(measurement, "setAccumulatedDeltaRangeMeters",
            observation.accumulatedDeltaRangeMeters))
        require(set(measurement, "setAccumulatedDeltaRangeUncertaintyMeters",
            observation.accumulatedDeltaRangeUncertaintyMeters))
        observation.carrierHz?.let { set(measurement, "setCarrierFrequencyHz", it.toFloat()) }
        observation.codeType?.let { set(measurement, "setCodeType", it) }
        measurement
    } catch (failure: Throwable) {
        Logger.error("Synthetic raw measurement conversion failed", failure)
        null
    }

    private fun constellationType(constellation: Constellation): Int = when (constellation) {
        Constellation.GPS -> 1
        Constellation.SBAS -> 2
        Constellation.GLONASS -> 3
        Constellation.BEIDOU -> 5
        Constellation.GALILEO -> 6
        Constellation.QZSS -> 4
        Constellation.IRNSS -> 7
    }

    private fun set(target: Any, method: String, value: Any): Boolean = try {
        Reflection.callMethod(target, method, value)
        true
    } catch (failure: Throwable) {
        Logger.error("Synthetic raw setter $method failed", failure)
        false
    }

    private fun event(clock: GnssClock, measurements: List<GnssMeasurement>): GnssMeasurementsEvent? {
        val type = GnssMeasurementsEvent::class.java
        val constructors = listOf(
            arrayOf<Class<*>>(
                Int::class.javaPrimitiveType!!,
                GnssClock::class.java,
                List::class.java,
                List::class.java,
                Boolean::class.javaPrimitiveType!!,
            ),
            arrayOf<Class<*>>(GnssClock::class.java, Collection::class.java),
            arrayOf<Class<*>>(GnssClock::class.java, List::class.java),
        )
        for (parameters in constructors) {
            try {
                val args: Array<Any?> = when (parameters.size) {
                    5 -> arrayOf(1, clock, measurements, emptyList<Any>(), true)
                    2 -> arrayOf(clock, measurements)
                    else -> error("unsupported GnssMeasurementsEvent signature")
                }
                return type.getDeclaredConstructor(*parameters).apply { isAccessible = true }
                    .newInstance(*args) as GnssMeasurementsEvent
            } catch (failure: Throwable) {
                Logger.error("Synthetic raw event ctor ${
                    parameters.joinToString(",") { it.simpleName }
                } failed", failure)
            }
        }
        // Last resort for an OEM that hides the constructor but keeps the AOSP field names.
        return try {
            val instance = Reflection.newInstance(type) as GnssMeasurementsEvent
            setField(instance, "mFlag", 1)
            setField(instance, "mClock", clock)
            setField(instance, "mMeasurements", measurements)
            setField(instance, "mGnssAgcs", emptyList<Any>())
            setField(instance, "mIsFullTracking", true)
            instance
        } catch (failure: Throwable) {
            Logger.error("Synthetic raw event field fallback failed", failure)
            null
        }
    }

    private fun setField(target: Any, name: String, value: Any) {
        val field = Reflection.findFieldIfExists(target.javaClass, name)
            ?: error("missing $name")
        field.set(target, value)
    }
}
