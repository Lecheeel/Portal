package com.system.location.service.environment

import com.system.location.service.core.environment.BleBeacon
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal suspend fun collectBleScanWindow(
    timeoutMillis: Long,
    maxResults: Int,
    startScan: (onResult: (BleBeacon) -> Unit, onFailure: () -> Unit) -> Unit,
    stopScan: () -> Unit,
): List<BleBeacon> = coroutineScope {
    require(timeoutMillis > 0L && maxResults > 0)
    val completed = CompletableDeferred<List<BleBeacon>>()
    val found = LinkedHashMap<String, BleBeacon>()
    val lock = Any()
    fun snapshot() = synchronized(lock) { found.values.toList() }

    val onResult: (BleBeacon) -> Unit = { beacon ->
        val full = synchronized(lock) {
            val address = beacon.address.lowercase()
            if (address in found || found.size < maxResults) found[address] = beacon
            found.size >= maxResults
        }
        if (full) completed.complete(snapshot())
    }
    var started = false
    try {
        started = true
        startScan(onResult) { completed.complete(snapshot()) }
        val timeout = launch {
            delay(timeoutMillis)
            completed.complete(snapshot())
        }
        try {
            completed.await()
        } finally {
            timeout.cancel()
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        snapshot()
    } finally {
        if (started) runCatching(stopScan)
    }
}
