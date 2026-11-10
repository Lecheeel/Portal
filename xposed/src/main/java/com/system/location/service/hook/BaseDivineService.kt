package com.system.location.service.hook

import android.content.Context
import android.location.LocationManager
import android.os.Binder
import android.os.Bundle
import android.os.Parcel
import com.system.location.service.hook.utils.BinderUtils
import com.system.location.service.hook.utils.FakeLoc
import com.system.location.service.hook.utils.Logger
import com.system.location.service.hook.security.CommandClient

abstract class BaseDivineService {
    /**
     * if the hook is TelephonyService? or other service?
     * this service may not be in the same space as the `system_server`,
     * so a binder is used to talk.
     */
    protected fun initDivineService(from: String, retryCount: Int = 0): Boolean {
        if (FakeLoc.isSystemServerProcess) {
            return true
        }

        fun tryFetchLocationManager(): LocationManager? {
            var locationManager = BinderUtils.getSystemContext()?.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            var count = 0
            while (locationManager == null) {
                Thread.sleep(1000)
                if (count++ > 10) {
                    break
                }
                locationManager = BinderUtils.getSystemContext()?.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            }
            return locationManager
        }

        val locationManager = tryFetchLocationManager()
        if (locationManager == null) {
            Logger.error("LocationManager not found in $from")
            return false
        }

        if (!locationManager.isProviderEnabled("fused_ext")) {
            if (retryCount > 10) {
                return false
            }
            Thread.sleep(100)
            return initDivineService(from, retryCount + 1)
        }

        val client = CommandClient()
        val rely = Bundle()
        if (!client.connect(locationManager)) {
            Logger.error("Failed to init service in $from")
            return false
        }

        val session = java.util.UUID.randomUUID().toString()
        RemoteCommandHandler.resetProxySession(session)

        rely.putBinder("proxy", object: Binder() {
            override fun getInterfaceDescriptor(): String {
                return "android.location.service.${from}Provider"
            }

            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                if (code == RuntimeSyncCommand.TRANSACTION) {
                    if (Binder.getCallingUid() != 1000) return false
                    val bundle = data.readBundle(javaClass.classLoader) ?: return false
                    val accepted = RemoteCommandHandler.handleProxyInstruction(bundle)
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
            Logger.error("Failed to init service proxy in $from")
            return false
        }
        return true
    }

}
