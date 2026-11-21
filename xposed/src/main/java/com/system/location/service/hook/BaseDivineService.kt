package com.system.location.service.hook

import android.content.Context
import android.location.LocationManager
import android.os.Binder
import android.os.Bundle
import android.os.Parcel
import android.os.Handler
import android.os.Looper
import com.system.location.service.hook.runtime.DeferredServiceConnection
import com.system.location.service.hook.utils.BinderUtils
import com.system.location.service.hook.utils.FakeLoc
import com.system.location.service.hook.utils.Logger
import com.system.location.service.hook.security.CommandClient

abstract class BaseDivineService {
    private var connection: DeferredServiceConnection? = null

    /**
     * Hooks may install immediately: their runtime stays disabled until authenticated state
     * arrives. Never wait for location Binder calls or proxy delivery inside package binding;
     * phone must finish starting before the location service can query its subscriptions.
     */
    protected fun initDivineService(from: String): Boolean {
        if (FakeLoc.isSystemServerProcess) return true
        val pending = synchronized(this) {
            connection ?: DeferredServiceConnection(
                schedule = { delay, task -> executor.schedule({ task() }, delay,
                    java.util.concurrent.TimeUnit.MILLISECONDS); Unit },
                connect = { connectDivineService(from) },
                onFailure = { Logger.error("Runtime peer connection failed in $from", it) },
            ).also { connection = it }
        }
        // The next main-loop turn runs after the current bindApplication/onCreate has returned.
        // Only enqueue work there; all service discovery, IPC and waits stay on the worker.
        Handler(Looper.getMainLooper()).post { pending.start() }
        return true
    }

    private fun connectDivineService(from: String): Boolean {
        val locationManager = BinderUtils.getSystemContext()?.getSystemService(Context.LOCATION_SERVICE)
            as? LocationManager ?: return false
        if (!locationManager.isProviderEnabled("fused_ext")) return false

        val client = CommandClient()
        val rely = Bundle()
        if (!client.connect(locationManager)) {
            Logger.error("Failed to init service in $from")
            return false
        }

        val session = java.util.UUID.randomUUID().toString()
        RemoteCommandHandler.resetProxySession(session)
        val initialized = java.util.concurrent.CountDownLatch(1)

        rely.putBinder("proxy", object: Binder() {
            override fun getInterfaceDescriptor(): String {
                return "android.location.service.${from}Provider"
            }

            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                if (code == RuntimeSyncCommand.TRANSACTION) {
                    if (Binder.getCallingUid() != 1000) return false
                    val bundle = data.readBundle(javaClass.classLoader) ?: return false
                    val accepted = RemoteCommandHandler.handleProxyInstruction(bundle)
                    if (accepted) initialized.countDown()
                    reply?.writeNoException()
                    reply?.writeInt(if (accepted) 1 else 0)
                    return true
                }
                return super.onTransact(code, data, reply, flags)
            }
        })
        rely.putString("proxy_name", from)
        rely.putString(RuntimeSyncCommand.SESSION, session)
        rely.putString("command_id", "set_proxy")
        if (!client.send(locationManager, rely)) {
            RemoteCommandHandler.resetProxySession(java.util.UUID.randomUUID().toString())
            Logger.error("Failed to init service proxy in $from")
            return false
        }
        val ready = try {
            initialized.await(5, java.util.concurrent.TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
        if (!ready) {
            RemoteCommandHandler.resetProxySession(java.util.UUID.randomUUID().toString())
            Logger.error("Initial runtime state not restored in $from")
            return false
        }
        Logger.info("Runtime peer connected in $from")
        return true
    }

    private companion object {
        val executor by lazy {
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor { task ->
                Thread(task, "Portal-runtime-connect").apply { isDaemon = true }
            }
        }
    }
}
