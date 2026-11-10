package com.system.location.service

import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.system.location.service.core.geo.Wgs84
import com.system.location.service.core.gnss.*
import com.system.location.service.gnss.GnssCaptureRepository
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GnssCaptureRepositoryDeviceTest {
    @Test fun rejectedWindowIsArchivedWithoutReplacingAcceptedProfile() = runBlocking {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = Files.createTempDirectory(target.cacheDir.toPath(), "gnss-repository-test-").toFile()
        val isolated = object : ContextWrapper(target) { override fun getNoBackupFilesDir() = directory }
        val now = System.currentTimeMillis()
        // Synthetic storage fixture; never placed in the application's production GNSS directory.
        val accepted = GnssCapture(deviceModel = "storage fixture", osVersion = "test",
            startedAtMillis = now - 60_000, endedAtMillis = now,
            frames = (0..60).map { index -> GnssObservationFrame(now - 60_000 + index * 1_000L,
                observer = Wgs84(31.0, 121.0), satellites = listOf(
                    GnssSatellite(Constellation.GPS, 1, 25f, 123f, 32f, true, true, true))) })
        val rejected = accepted.copy(frames = accepted.frames.map { it.copy(observer = null) })
        try {
            val repository = GnssCaptureRepository(isolated)
            repository.save(accepted)
            val active = directory.resolve("gnss/receiver-capture.json")
            val original = active.readBytes()
            val failure = runCatching { repository.save(rejected) }.exceptionOrNull()
            assertTrue(failure is IllegalStateException)
            assertTrue(failure?.message.orEmpty().contains("缺少真实观测坐标"))
            assertArrayEquals(original, active.readBytes())
            assertEquals(accepted, repository.load())
            assertEquals(rejected, GnssCaptureStore(directory.resolve("gnss/latest-rejected-capture.json")).load())
            val zeroCn0 = accepted.copy(frames = accepted.frames.map { frame ->
                frame.copy(satellites = frame.satellites.map { it.copy(cn0DbHz = 0f, usedInFix = false) })
            })
            val zeroFailure = runCatching { repository.save(zeroCn0) }.exceptionOrNull()
            assertTrue(zeroFailure is IllegalStateException)
            assertTrue(zeroFailure?.message.orEmpty().contains("有效帧不足"))
            assertArrayEquals(original, active.readBytes())
            assertEquals(zeroCn0, GnssCaptureStore(directory.resolve("gnss/latest-rejected-capture.json")).load())
        } finally { directory.deleteRecursively() }
    }
}
