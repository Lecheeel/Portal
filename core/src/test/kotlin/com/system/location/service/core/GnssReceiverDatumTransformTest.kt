package com.system.location.service.core

import com.system.location.service.core.geo.Wgs84
import com.system.location.service.core.gnss.*
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

class GnssReceiverDatumTransformTest {
    private fun identity() = GnssReceiverDatumTransform("synthetic datum evidence", 7, 100L, 200L,
        listOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0), listOf(0.0, 0.0, 0.0), 1.0,
        6378137.0, 298.257223563)

    @Test fun identityRoundTripRecoversEllipsoidNormalAtRoutesAndHeights() {
        for (latitude in listOf(-89.99, -45.0, 0.0, 31.0, 45.0, 89.99))
            for (longitude in listOf(-180.0, -120.0, 0.0, 121.0, 180.0))
                for (height in listOf(-500.0, 0.0, 1000.0, 100_000.0)) {
                    val point = Wgs84(latitude, longitude)
                    val result = identity().receiver(point, height, 100L, 7)!!
                    assertEquals(GnssObserverGeometry.ecef(point, height), result.position)
                    assertEquals(latitude, result.orientation.latitudeDegrees, 1e-10)
                    assertEquals(longitude, result.orientation.longitudeDegrees, 1e-10)
                }
    }

    @Test fun similarityUsesDeclaredRotationDirectionScaleAndTranslation() {
        // Active +90 degrees about z: source x becomes target y. The additional translation
        // moves the target to (3, 4, 0)*a, so its analytic ellipsoid normal is (3/5, 4/5, 0).
        val a = 6378137.0
        val transform = identity().copy(rotation = listOf(0.0, -1.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0, 1.0),
            scale = 2.0, translationMeters = listOf(3 * a, 2 * a, 0.0))
        val receiver = transform.receiver(Wgs84(0.0, 0.0), 0.0, 100L, 7)!!
        assertEquals(EcefPosition(3 * a, 4 * a, 0.0), receiver.position)
        assertEquals(0.0, receiver.orientation.latitudeDegrees, 1e-12)
        assertEquals(Math.toDegrees(atan2(4.0, 3.0)), receiver.orientation.longitudeDegrees, 1e-12)
        assertEquals(transform, Json.decodeFromString<GnssReceiverDatumTransform>(Json.encodeToString(transform)))
    }

    @Test fun targetEllipsoidNormalComesFromTransformedPositionInsteadOfSourceAngles() {
        val transform = identity().copy(translationMeters = listOf(1000.0, -2000.0, 3000.0),
            targetSemiMajorAxisMeters = 6_400_000.0, targetInverseFlattening = 250.0)
        val receiver = transform.receiver(Wgs84(31.0, 121.0), 100.0, 100L, 7)!!
        val phi = Math.toRadians(receiver.orientation.latitudeDegrees)
        val lambda = Math.toRadians(receiver.orientation.longitudeDegrees)
        val a = transform.targetSemiMajorAxisMeters
        val f = 1.0 / transform.targetInverseFlattening
        val e2 = f * (2.0 - f)
        val n = a / sqrt(1.0 - e2 * sin(phi).pow(2))
        val p = hypot(receiver.position.xMeters, receiver.position.yMeters)
        val h = p / cos(phi) - n
        // Independent forward ellipsoid equations must reconstruct the transformed ECEF.
        assertEquals(receiver.position.xMeters, (n + h) * cos(phi) * cos(lambda), 1e-7)
        assertEquals(receiver.position.yMeters, (n + h) * cos(phi) * sin(lambda), 1e-7)
        assertEquals(receiver.position.zMeters, (n * (1.0 - e2) + h) * sin(phi), 1e-7)
        assertNotEquals(31.0, receiver.orientation.latitudeDegrees, 1e-4)
    }

    @Test fun missingExpiredForeignBootAmbiguousAndInvalidEvidenceCannotResolveAReceiver() {
        val datum = identity()
        val point = Wgs84(0.0, 0.0)
        for (elapsed in listOf(null, 99L, 200L)) assertNull(datum.receiver(point, 0.0, elapsed, 7))
        for (boot in listOf(null, 6)) assertNull(datum.receiver(point, 0.0, 100L, boot))
        assertNull(datum.receiver(point, Double.NaN, 100L, 7))
        assertNull(datum.receiver(point, -6378137.0, 100L, 7))
        assertThrows(IllegalArgumentException::class.java) { datum.copy(scale = 0.0) }
        assertThrows(IllegalArgumentException::class.java) { datum.copy(rotation = listOf(-1.0) + datum.rotation.drop(1)) }
        assertThrows(IllegalArgumentException::class.java) { datum.copy(rotation = datum.rotation.map { it * 2 }) }
        assertThrows(IllegalArgumentException::class.java) { datum.copy(validUntilElapsedNanos = 100L) }
    }
}
