package com.system.location.service.hook

import android.os.IBinder
import com.system.location.service.core.runtime.LatestStateWorker
import com.system.location.service.core.runtime.RuntimeTransfer

/** Bounded subscriptions keyed by authenticated UID and service name, each with its own mailbox. */
internal class RuntimePeerRegistry(
    private val maxPeers: Int = 16,
    private val deliver: (IBinder, String, Publication) -> Unit,
    private val onFailure: (Throwable) -> Unit,
) {
    internal data class Publication(val sequence: Long, val transfer: RuntimeTransfer)
    private data class Key(val uid: Int, val name: String)
    private class Entry(val binder: IBinder, val session: String, val death: IBinder.DeathRecipient,
        val worker: LatestStateWorker<Publication>)
    private val entries = LinkedHashMap<Key, Entry>()

    init { require(maxPeers > 0) }

    @Synchronized fun register(uid: Int, name: String, session: String, binder: IBinder): Boolean {
        require(uid >= 0 && name.isNotBlank() && name.length <= 128 && session.isNotBlank() && session.length <= 128)
        if (!binder.isBinderAlive) return false
        val key = Key(uid, name)
        val replaced = entries.filter { it.key == key || it.value.binder == binder }
        if (entries.size - replaced.size >= maxPeers) return false
        lateinit var entry: Entry
        val death = IBinder.DeathRecipient { remove(key, entry) }
        val worker = LatestStateWorker<Publication>({ deliver(binder, session, it) }, {
            remove(key, entry)
            onFailure(it)
        })
        entry = Entry(binder, session, death, worker)
        replaced.forEach { (oldKey, old) -> remove(oldKey, old) }
        entries[key] = entry
        try {
            binder.linkToDeath(death, 0)
        } catch (failure: Exception) {
            remove(key, entry)
            return false
        }
        if (!binder.isBinderAlive || entries[key] !== entry) { remove(key, entry); return false }
        return true
    }

    /** Caller orders offers; slow or reentrant Binder calls run outside all registry/command locks. */
    fun offer(publication: Publication) {
        require(publication.sequence > 0)
        val peers = synchronized(this) { entries.values.toList() }
        peers.forEach { it.worker.offer(publication) }
    }

    @Synchronized private fun remove(key: Key, expected: Entry) {
        if (entries[key] !== expected) return
        entries.remove(key)
        expected.worker.close()
        runCatching { expected.binder.unlinkToDeath(expected.death, 0) }
    }

    @Synchronized fun size(): Int = entries.size
    @Synchronized fun clear() { entries.toMap().forEach { (key, entry) -> remove(key, entry) } }
}
