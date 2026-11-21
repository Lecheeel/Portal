package com.system.location.service.android.root

import java.util.concurrent.TimeUnit

/** Checks the root implementation's version without requesting a privileged shell or changing it. */
internal object RootEnvironmentProbe {
    fun isAvailable(): Boolean {
        val process = try { ProcessBuilder("su", "-v").redirectErrorStream(true).start() }
            catch (_: Exception) { return false }
        return try {
            process.waitFor(1_000, TimeUnit.MILLISECONDS) && process.exitValue() == 0
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        } finally {
            runCatching { if (process.isAlive) process.destroyForcibly() }
            runCatching { process.inputStream.close() }
            runCatching { process.errorStream.close() }
            runCatching { process.outputStream.close() }
        }
    }
}
