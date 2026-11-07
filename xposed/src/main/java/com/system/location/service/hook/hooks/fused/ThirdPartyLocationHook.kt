package com.system.location.service.hook.hooks.fused

import android.location.LocationListener
import android.os.Bundle
import com.system.location.service.hook.BaseLocationHook
import com.system.location.service.hook.hooks.blindhook.BlindHookLocation
import com.system.location.service.hook.hooks.blindhook.BlindHookLocation.invoke
import com.system.location.service.hook.runtime.Reflection
import com.system.location.service.hook.utils.FakeLoc
import com.system.location.service.hook.utils.Logger
import com.system.location.service.hook.utils.hookMethod
import com.system.location.service.hook.utils.onceHookMethod
import com.system.location.service.hook.utils.toClass
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Modifier

object ThirdPartyLocationHook : BaseLocationHook() {
    operator fun invoke(classLoader: ClassLoader) {
        if (!initDivineService("ThirdPartyLocationHook")) {
            Logger.error("Failed to init DivineService in ThirdPartyLocationHook")
            return
        }

        hookAMapNetLocManager(classLoader)
        hookBDMap(classLoader)
        hookTencent(classLoader)
    }

    private fun hookAMapNetLocManager(classLoader: ClassLoader) {
        val cNetworkLocationManager =
            "com.amap.android.location.NetworkLocationManager".toClass(classLoader)
        if (cNetworkLocationManager == null) {
            Logger.warn("Failed to find NetworkLocationManager (amap service)")
            return
        }

        cNetworkLocationManager.onceHookMethod(
            "onSendExtraCommand",
            String::class.java,
            Bundle::class.java,
            callback =
                XposedInterface.Hooker intercept60@{ chain ->
                    val args = chain.args.toTypedArray()

                    if (FakeLoc.enableDebugLog) {
                        val cmd = args[0] as String
                        val extras = args[1] as Bundle

                        Logger.debug("NetworkLocationManager.onSendExtraCommand: $cmd, $extras")
                    }
                    if (FakeLoc.enable) {
                        return@intercept60 false
                    }

                    chain.proceed(args)
                },
        )

        val boolFields =
            cNetworkLocationManager.declaredFields.filter {
                it.type == Boolean::class.java && !Modifier.isStatic(it.modifiers)
            }
        boolFields.forEach { it.isAccessible = true }
        val listenerField =
            cNetworkLocationManager.declaredFields.filter {
                it.type == LocationListener::class.java && !Modifier.isStatic(it.modifiers)
            }
        listenerField.forEach { it.isAccessible = true }
        cNetworkLocationManager.onceHookMethod(
            "requestLocationUpdates",
            Long::class.javaPrimitiveType!!,
            LocationListener::class.java,
            callback =
                XposedInterface.Hooker intercept61@{ chain ->
                    val args = chain.args.toTypedArray()

                    if (FakeLoc.enable) {
                        if (args[1] is LocationListener) {
                            args[1] = null // 拒绝高德地图注册私有listener
                        }
                        listenerField.forEach { it.set(chain.thisObject, null) }
                        boolFields.forEach { it.setBoolean(chain.thisObject, false) }
                    }

                    chain.proceed(args)
                },
        )

        cNetworkLocationManager.hookMethod(
            "updateOffLocEnable",
            Boolean::class.javaPrimitiveType!!,
            callback =
                XposedInterface.Hooker { null },
        )

        BlindHookLocation(cNetworkLocationManager, classLoader)
    }

    private fun hookBDMap(classLoader: ClassLoader) {
        run {
            val bdLocationClient = "com.baidu.location.LocationClient".toClass(classLoader)
            if (bdLocationClient == null) {
                Logger.warn("Failed to find LocationClient (baidu service)")
                return@run
            }

            bdLocationClient.hookMethod(
                "requestNLPNormal",
                String::class.java,
                Int::class.javaPrimitiveType!!,
                callback =
                    XposedInterface.Hooker intercept63@{ chain ->
                        val args = chain.args.toTypedArray()
                        var result = chain.proceed()

                        if (FakeLoc.enable) {
                            val bdLocation = result ?: return@intercept63 result

                            val jitterLat = FakeLoc.jitterLocation()
                            Reflection.callMethod(bdLocation, "setLatitude", jitterLat.first)
                            Reflection.callMethod(bdLocation, "setLongitude", jitterLat.second)
                            Reflection.callMethod(bdLocation, "setBuildingID", "")
                            Reflection.callMethod(bdLocation, "setAddrStr", "")
                        }

                        result
                    },
            )

            BlindHookLocation(bdLocationClient, classLoader)
        }
    }

    private fun hookTencent(classLoader: ClassLoader) {
        run {
            val cTencentNLPManager =
                "com.tencent.geolocation.nlp.TencentNLPManager".toClass(classLoader)
            if (cTencentNLPManager == null) {
                Logger.warn("Failed to find TencentNLPManager (tencent service)")
                return@run
            }

            BlindHookLocation(cTencentNLPManager, classLoader)
        }
    }
}
