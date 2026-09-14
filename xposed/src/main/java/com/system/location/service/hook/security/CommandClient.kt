package com.system.location.service.hook.security

import android.location.LocationManager
import android.os.Bundle
import android.os.SystemClock

/** Serializes requests so sequence allocation and Binder delivery have the same order. */
class CommandClient {
    @Volatile private var token: String? = null
    private var sequence = 0L
    val connected: Boolean get() = token != null

    @Synchronized fun connect(manager: LocationManager): Boolean {
        token = null
        val reply = Bundle()
        if (!manager.sendExtraCommand(PROVIDER, "exchange_key", reply)) return false
        if (reply.getInt("protocol_version") != CommandSecurity.VERSION) return false
        token = reply.getString("key")
        sequence = 0
        return connected
    }

    @Synchronized fun send(manager: LocationManager, data: Bundle): Boolean {
        val key = token ?: return false
        data.putInt("protocol_version", CommandSecurity.VERSION)
        data.putLong("sequence", ++sequence)
        data.putLong("sent_at", SystemClock.elapsedRealtime())
        return manager.sendExtraCommand(PROVIDER, key, data)
    }

    companion object { const val PROVIDER = "fused_ext" }
}
