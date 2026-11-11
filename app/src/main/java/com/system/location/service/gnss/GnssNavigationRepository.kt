package com.system.location.service.gnss

import com.system.location.service.core.gnss.RinexNavigationResource
import com.system.location.service.core.gnss.RinexNavigationResourceStore
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException

/** Network and disk operations are independent of receiver collection and location ticks. */
internal class GnssNavigationRepository(
    file: File,
    private val download: GnssNavigationDownload = GnssNavigationDownload(HttpsGnssNavigationTransport()),
    private val status: GnssNavigationSyncStatus = GnssNavigationSyncStatus.shared,
) {
    private val store = RinexNavigationResourceStore(file)
    private val updates = Mutex()

    /** True only when bytes/provenance changed; downloading the same upload does not reset sky. */
    suspend fun refresh(nowMillis: Long = System.currentTimeMillis()): Boolean = updates.withLock {
        status.begin()
        try {
            val candidate = runInterruptible(Dispatchers.IO) { download.fetch(nowMillis) }
            coroutineContext.ensureActive()
            val changed = withContext(Dispatchers.IO) {
                val old = runCatching { store.load() }.getOrNull()
                coroutineContext.ensureActive()
                store.update(RinexNavigationResource.encode(candidate))
                old?.sha256 != candidate.sha256 || old.sourceUrl != candidate.sourceUrl
            }
            status.accepted(nowMillis, candidate.sourceUrl)
            changed
        } catch (cancelled: CancellationException) {
            status.cancelled()
            throw cancelled
        } catch (error: Exception) {
            status.failed()
            throw error
        }
    }
}
