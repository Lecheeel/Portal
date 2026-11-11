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

/** Network and disk operations are independent of receiver collection and location ticks. */
internal class GnssNavigationRepository(
    file: File,
    private val download: GnssNavigationDownload = GnssNavigationDownload(HttpsGnssNavigationTransport()),
) {
    private val store = RinexNavigationResourceStore(file)
    private val updates = Mutex()

    /** True only when bytes/provenance changed; downloading the same upload does not reset sky. */
    suspend fun refresh(nowMillis: Long = System.currentTimeMillis()): Boolean = updates.withLock {
        val candidate = runInterruptible(Dispatchers.IO) { download.fetch(nowMillis) }
        coroutineContext.ensureActive()
        withContext(Dispatchers.IO) {
            val old = runCatching { store.load() }.getOrNull()
            coroutineContext.ensureActive()
            store.update(RinexNavigationResource.encode(candidate))
            old?.sha256 != candidate.sha256 || old.sourceUrl != candidate.sourceUrl
        }
    }
}
