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
    private val catalogDownload: GnssBeiDouCatalogDownload? = HttpsGnssBeiDouCatalogDownload(),
) {
    private val store = RinexNavigationResourceStore(file)
    private val updates = Mutex()

    /** True only when bytes/provenance changed; downloading the same upload does not reset sky. */
    suspend fun refresh(nowMillis: Long = System.currentTimeMillis()): Boolean = updates.withLock {
        status.begin()
        try {
            val old = withContext(Dispatchers.IO) { runCatching { store.load() }.getOrNull() }
            val navigation = try { runInterruptible(Dispatchers.IO) { download.fetch(nowMillis) } }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) { if (catalogDownload == null || old == null) throw error; null }
            val catalog = try { catalogDownload?.let { runInterruptible(Dispatchers.IO) { it.fetch(nowMillis) } } }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { null }
            coroutineContext.ensureActive()
            if (navigation == null && (catalog == null || catalog == old?.beiDouCatalog))
                error("Neither navigation nor metadata refresh supplied an update")
            val candidate = (navigation ?: requireNotNull(old)).copy(beiDouCatalog = catalog)
            val changed = withContext(Dispatchers.IO) {
                coroutineContext.ensureActive()
                val accepted = store.update(RinexNavigationResource.encode(candidate))
                old?.sha256 != accepted.sha256 || old.sourceUrl != accepted.sourceUrl ||
                    old.beiDouCatalog != accepted.beiDouCatalog
            }
            status.accepted(nowMillis, candidate.sourceUrl)
            if (catalogDownload != null && catalog == null) status.catalogFailed()
            if (navigation == null) status.navigationRetained()
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
