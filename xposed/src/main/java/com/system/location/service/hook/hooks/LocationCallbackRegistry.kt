package com.system.location.service.hook.hooks

import android.os.IBinder
import android.os.IInterface
import android.os.RemoteException
import com.system.location.service.hook.runtime.CallbackSubscription
import com.system.location.service.hook.utils.BinderUtils
import com.system.location.service.hook.utils.LocationCallerGuard

/** One Binder/death recipient per transport, with distinct provider subscriptions beneath it. */
internal class LocationCallbackRegistry(
    private val added: (IInterface) -> Unit = {},
    private val removed: (IBinder) -> Unit = {},
) {
    private class Entry(val listener: IInterface, val uid: Int, val death: IBinder.DeathRecipient,
        val token: Any, val coarse: Boolean, val providers: MutableSet<String> = linkedSetOf())
    private val entries = LinkedHashMap<IBinder, Entry>()

    @Synchronized
    fun register(provider: String, listener: IInterface,
        callerUid: Int = runCatching { BinderUtils.getCallerUid() }.getOrDefault(-1),
        coarse: Boolean = LocationCallerGuard.isCoarse()): Boolean {
        val binder = listener.asBinder()
        if (!binder.isBinderAlive) {
            unregisterBinder(binder)
            return false
        }
        entries[binder]?.let { it.providers += provider; return true }
        val token = Any()
        // Implement the original zero-argument callback; newer Binder APIs delegate to it too.
        val death = IBinder.DeathRecipient { unregisterBinder(binder, token) }
        val entry = Entry(listener, callerUid, death, token, coarse).apply { providers += provider }
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

    /** True when synthetic delivery owns this transport, so real callbacks must be dropped. */
    @Synchronized
    fun contains(listener: Any?): Boolean {
        val binder = (listener as? IInterface)?.asBinder() ?: return false
        return entries.containsKey(binder)
    }

    @Synchronized
    fun subscriptions(): List<CallbackSubscription> = entries.values.map {
        CallbackSubscription(it.listener, it.uid, providers = it.providers.toList())
    }

    /** Re-links a Binder owned by the framework without invoking old-generation callbacks. */
    @Synchronized
    fun restore(subscription: CallbackSubscription): Boolean {
        val binder = subscription.listener.asBinder()
        if (!binder.isBinderAlive || entries.containsKey(binder)) return false
        val token = Any()
        val death = IBinder.DeathRecipient { unregisterBinder(binder, token) }
        val entry = Entry(subscription.listener, subscription.uid, death, token, coarse = false).apply {
            providers += subscription.providers
        }
        entries[binder] = entry
        return try {
            binder.linkToDeath(death, 0)
            if (!binder.isBinderAlive) { unregisterBinder(binder, token); false } else true
        } catch (_: RemoteException) {
            unregisterBinder(binder, token)
            false
        }
    }

    @Synchronized
    private fun unregisterBinder(binder: IBinder, expectedToken: Any? = null) {
        val entry = entries[binder] ?: return
        if (expectedToken != null && entry.token !== expectedToken) return
        entries.remove(binder)
        try { runCatching { binder.unlinkToDeath(entry.death, 0) } }
        finally { removed(binder) }
    }

    @Synchronized
    fun isCoarse(listener: IInterface): Boolean = entries[listener.asBinder()]?.coarse == true

    @Synchronized
    fun listeners(): List<Pair<String, IInterface>> {
        entries.keys.filter { !it.isBinderAlive }.toList().forEach { unregisterBinder(it) }
        return entries.values.flatMap { entry -> entry.providers.map { it to entry.listener } }
    }

    @Synchronized
    fun clear() { entries.keys.toList().forEach { unregisterBinder(it) } }
}
