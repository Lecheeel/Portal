package com.system.location.service.hook.hooks

import android.os.IBinder
import android.os.IInterface
import android.os.RemoteException

/** One Binder/death recipient per transport, with distinct provider subscriptions beneath it. */
internal class LocationCallbackRegistry(
    private val added: (IInterface) -> Unit = {},
    private val removed: (IBinder) -> Unit = {},
) {
    private class Entry(val listener: IInterface, val death: IBinder.DeathRecipient,
        val token: Any, val providers: MutableSet<String> = linkedSetOf())
    private val entries = LinkedHashMap<IBinder, Entry>()

    @Synchronized
    fun register(provider: String, listener: IInterface): Boolean {
        val binder = listener.asBinder()
        if (!binder.isBinderAlive) {
            unregisterBinder(binder)
            return false
        }
        entries[binder]?.let { it.providers += provider; return true }
        val token = Any()
        // Implement the original zero-argument callback; newer Binder APIs delegate to it too.
        val death = IBinder.DeathRecipient { unregisterBinder(binder, token) }
        val entry = Entry(listener, death, token).apply { providers += provider }
        entries[binder] = entry
        try {
            binder.linkToDeath(death, 0)
        } catch (_: RemoteException) {
            entries.remove(binder)
            return false
        }
        if (entries[binder] !== entry || !binder.isBinderAlive) {
            unregisterBinder(binder, token)
            return false
        }
        try { added(listener) }
        catch (failure: Throwable) {
            unregisterBinder(binder, token)
            throw failure
        }
        return true
    }

    fun unregister(listener: IInterface) = unregisterBinder(listener.asBinder())

    @Synchronized
    private fun unregisterBinder(binder: IBinder, expectedToken: Any? = null) {
        val entry = entries[binder] ?: return
        if (expectedToken != null && entry.token !== expectedToken) return
        entries.remove(binder)
        try { runCatching { binder.unlinkToDeath(entry.death, 0) } }
        finally { removed(binder) }
    }

    @Synchronized
    fun listeners(): List<Pair<String, IInterface>> {
        entries.keys.filter { !it.isBinderAlive }.toList().forEach { unregisterBinder(it) }
        return entries.values.flatMap { entry -> entry.providers.map { it to entry.listener } }
    }

    @Synchronized
    fun clear() { entries.keys.toList().forEach { unregisterBinder(it) } }
}
