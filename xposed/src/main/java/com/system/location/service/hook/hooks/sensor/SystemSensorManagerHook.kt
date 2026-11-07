@file:Suppress("UNCHECKED_CAST")
package com.system.location.service.hook.hooks.sensor

import android.content.pm.FeatureInfo
import android.hardware.Sensor
import android.hardware.SensorEventListener
import android.util.ArrayMap
import com.system.location.service.hook.runtime.Reflection
import com.system.location.service.hook.utils.FakeLoc
import com.system.location.service.hook.utils.Logger
import io.github.libxposed.api.XposedInterface.Hooker
import com.system.location.service.hook.utils.hookAllMethods
import com.system.location.service.hook.utils.hookMethod
import com.system.location.service.hook.utils.onceHook
import java.util.concurrent.ConcurrentHashMap

object SystemSensorManagerHook {
    private val listenerMap = ConcurrentHashMap<SensorEventListener, Int>()

    operator fun invoke(classLoader: ClassLoader) {
        unlockGeoSensor(classLoader)

        hookSystemSensorManager(classLoader)
        hookSystemSensorManagerQueue(classLoader)
    }

    private fun hookSystemSensorManagerQueue(classLoader: ClassLoader) {
        val cSystemSensorManagerQueue = Reflection.findClassIfExists("android.hardware.SystemSensorManager\$SensorEventQueue", classLoader)
            ?: return


    }

    private fun hookSystemSensorManager(classLoader: ClassLoader) {
        val cSystemSensorManager = Reflection.findClassIfExists("android.hardware.SystemSensorManager", classLoader)
        if (cSystemSensorManager == null) {
            if (FakeLoc.enableDebugLog) {
                Logger.debug("Failed to find SystemSensorManager")
            }
            return
        }

        val hookRegisterListenerImpl = Hooker { chain ->
            val listener = chain.args[0] as SensorEventListener
            if (FakeLoc.enableDebugLog) {
                Logger.debug("RegisterListenerImpl: $listener, sensor: ${chain.args[1]}")
            }

            val sensor = chain.args[1] as? Sensor ?: return@Hooker chain.proceed()
            listenerMap[listener] = sensor.type

            chain.proceed()
        }
        cSystemSensorManager.declaredMethods.filter {
            it.name == "registerListenerImpl" && it.parameterTypes.size >= 2
                    && it.parameterTypes[0] == SensorEventListener::class.java
                    && it.parameterTypes[1] == Sensor::class.java
        }.forEach {
            it.onceHook(hookRegisterListenerImpl)
        }

        val hookUnregisterListenerImpl = Hooker { chain ->
            val listener = chain.args[0] as SensorEventListener
            if (FakeLoc.enableDebugLog) {
                Logger.debug("UnregisterListenerImpl: $listener")
            }
            listenerMap.remove(listener)
            chain.proceed()
        }
        cSystemSensorManager.declaredMethods.filter {
            it.name == "unregisterListenerImpl" && it.parameterTypes.isNotEmpty()
                    && it.parameterTypes[0] == SensorEventListener::class.java
        }.forEach {
            it.onceHook(hookUnregisterListenerImpl)
        }

        cSystemSensorManager.hookAllMethods("getSensorList", Hooker { chain ->
            val result = chain.proceed()
            if (FakeLoc.enableDebugLog) {
                Logger.debug("getSensorList: type: ${chain.args[0]} -> $result")
            }
            result
        })
        cSystemSensorManager.hookAllMethods("getFullSensorsList", Hooker { chain ->
            val result = chain.proceed()
            if (FakeLoc.enableDebugLog) {
                Logger.debug("getFullSensorsList-> $result")
            }
            result
        })
    }

    private fun unlockGeoSensor(classLoader: ClassLoader) {
        val cSystemConfig = Reflection.findClassIfExists("com.android.server.SystemConfig", classLoader)
            ?: return

        val openGLVersion = run {
            val cSystemProperties = Reflection.findClassIfExists("android.os.SystemProperties", classLoader)
                ?: return@run 0
            Reflection.callStaticMethod(cSystemProperties, "getInt", "ro.opengles.version", FeatureInfo.GL_ES_VERSION_UNDEFINED) as Int
        }

        cSystemConfig.hookMethod("getAvailableFeatures", callback = Hooker { chain ->
            val result = chain.proceed()
            val features = result as ArrayMap<String, FeatureInfo>
            if (FakeLoc.enableDebugLog) {
                Logger.debug("getAvailableFeatures: ${features.keys}")
            }

            features
        })
    }
}
