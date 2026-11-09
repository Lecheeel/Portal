package com.system.location.service.ui.home

import org.junit.Assert.*
import org.junit.Test

class HomeCameraPolicyTest {
    @Test fun `mock stale and simulated fixes cannot resolve initial camera`() {
        for (bad in listOf(
            Triple(0L, true, false), Triple(0L, false, true),
            Triple(120_001L, false, false), Triple(-1L, false, false),
        )) {
            val policy = HomeCameraPolicy()
            assertNull(policy.accept(31.2, 121.5, bad.first, 200_000, bad.second, bad.third))
            assertNotNull(policy.accept(31.2, 121.5, 0, 200_001, false, false))
        }
        val policy = HomeCameraPolicy()
        for ((lat, lon) in listOf(Double.NaN to 1.0, 91.0 to 1.0, 1.0 to 181.0, 0.0 to 0.0)) {
            assertNull(policy.accept(lat, lon, 0, 200_000, false, false))
        }
        assertNotNull(policy.accept(0.0, 12.0, 0, 200_001, false, false))
    }

    @Test fun `locate always recenters and restores zoom without following later updates`() {
        val policy = HomeCameraPolicy()
        policy.accept(31.2, 121.5, 0, 1_000, false, false)
        assertNull(policy.accept(31.3, 121.6, 0, 2_000, false, false))
        repeat(3) {
            val move = policy.locate(3_000)
            assertEquals(HomeCameraPolicy.Position(31.3, 121.6), move?.position)
            assertEquals(16.5f, move!!.zoom)
        }
        assertNull(policy.accept(31.4, 121.7, 0, 4_000, false, false))
    }

    @Test fun `locate waits for fresh fix when cache expired`() {
        val policy = HomeCameraPolicy()
        policy.accept(31.2, 121.5, 0, 1_000, false, false)
        assertNull(policy.locate(122_000))
        assertNotNull(policy.accept(31.3, 121.6, 0, 123_000, false, false))
    }

    @Test fun `explicit target interaction cancels pending automatic centering`() {
        val policy = HomeCameraPolicy()
        policy.userMovedCamera()
        assertNull(policy.accept(31.2, 121.5, 0, 1_000, false, false))
        assertNotNull(policy.locate(2_000))
    }

    @Test fun `first real fix centers at street zoom`() {
        val policy = HomeCameraPolicy()
        val move = policy.accept(31.2, 121.5, 0, 1_000, false, false)
        assertNotNull("The first real fix must move the camera", move)
        assertEquals(HomeCameraPolicy.Position(31.2, 121.5), move!!.position)
        assertEquals(16.5f, move.zoom)
    }
}
