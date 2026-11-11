package com.system.location.service.core

import com.system.location.service.core.geo.Wgs84
import com.system.location.service.core.gnss.*
import org.junit.Assert.*
import org.junit.Test

class AutonomousGnssFrameStoreTest {
    private val point = Wgs84(31.0, 121.0)
    private fun model(mode: GnssSimulationMode = GnssSimulationMode.AUTO) = ReceiverAutonomousSkyModel(
        emptyList(), VirtualWalkerOrbitProvider(VirtualWalkerResource(revision = "test", referenceEpochMillis = 0,
            groups = listOf(VirtualWalkerGroup(Constellation.GPS, (1..24).toList(), 6, 1,
                26_560_000.0, 0.95))), 123), GnssSimulationPolicy(mode = mode))

    @Test fun autonomousStoreNeedsNoCaptureAndSharesFirstSamplePerRouteSecond() {
        val store = GnssFrameStore().apply { configureAutonomous(model()) }
        val first = store.current(62100, point, 10.0, 0, 2.0, 90.0, route = GnssFrameRoute.Global)!!
        assertSame(first, store.current(62900, Wgs84(32.0, 122.0), 20.0, 0, 3.0, 100.0,
            route = GnssFrameRoute.Global))
        val remote = store.current(62500, Wgs84(-31.0, -59.0), 10.0, 0, 2.0, 90.0,
            route = GnssFrameRoute.Scenario("remote"))!!
        assertNotEquals(first.sequence, remote.sequence)
        assertNotEquals(first.frame.satellites, remote.frame.satellites)
        val afterTwoDays = store.current(172862100, point, 10.0, 0, 2.0, 90.0)!!
        assertTrue(afterTwoDays.sequence > remote.sequence)
    }

    @Test fun monotonicReceiverBucketsPreventFrozenWallClockFromReusingSamePublicationForever() {
        val store = GnssFrameStore().apply { configureAutonomous(model()) }
        fun current(nanos: Long, boot: Int = 1) = store.current(62100, point, 10.0, 0, 2.0, 90.0,
            route = GnssFrameRoute.Global, elapsedRealtimeNanos = nanos, currentBootCount = boot)!!
        val first = current(1_100_000_000)
        assertSame(first, current(1_900_000_000))
        val second = current(2_100_000_000)
        assertTrue(second.sequence > first.sequence)
        assertTrue(current(2_100_000_000, boot = 2).sequence > second.sequence)
    }

    @Test fun captureAndAutonomousModesDoNotLeakCachedPublicationsAcrossReconfiguration() {
        val store = GnssFrameStore().apply { configureAutonomous(model()) }
        val first = store.current(62100, point, 10.0, 0, 0.0, 0.0)!!
        store.configure(null)
        assertNull(store.current(62100, point, 10.0, 0, 0.0, 0.0))
        store.configureAutonomous(model(GnssSimulationMode.OFF))
        assertNull(store.current(62100, point, 10.0, 0, 0.0, 0.0))
        store.configureAutonomous(model())
        assertTrue(store.current(62100, point, 10.0, 0, 0.0, 0.0)!!.sequence > first.sequence)
    }
}
