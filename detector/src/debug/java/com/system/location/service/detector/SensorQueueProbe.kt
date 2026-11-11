package com.system.location.service.detector

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** Independent public-API evidence; no dependency on Portal's model or hook implementation. */
internal object SensorQueueProbe {
    fun sample(context: Context, request: JSONObject): JSONObject {
        val duration = request.optLong("durationMillis", 5000).coerceIn(1000, 20_000)
        val removeType = request.optInt("removeType", -1)
        val removeAt = request.optLong("removeAfterMillis", duration / 2).coerceIn(0, duration)
        val thread = HandlerThread("DetectorSensorProbe").apply { start() }
        val manager = context.getSystemService(SensorManager::class.java)
        val samples = ConcurrentHashMap<Int, MutableList<JSONObject>>()
        val late = AtomicInteger()
        val registered = JSONArray()
        val started = SystemClock.elapsedRealtimeNanos()
        val removed = java.util.concurrent.atomic.AtomicBoolean(false)
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                if (removed.get() && event.sensor.type == removeType) late.incrementAndGet()
                val series = samples.getOrPut(event.sensor.type) { mutableListOf() }
                if (series.size < 2500) series.add(JSONObject().apply {
                    put("timestamp", event.timestamp)
                    put("arrivalNanos", SystemClock.elapsedRealtimeNanos())
                    put("thread", Thread.currentThread().name)
                    put("values", JSONArray(event.values.toList()))
                })
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        val errors = JSONArray()
        var removedSensor: Sensor? = null
        try {
            listOf(1, 2, 4, 6, 9, 10, 11, 15, 18, 19, 20).forEach { type ->
                val sensor = manager.getDefaultSensor(type) ?: return@forEach
                if (type == removeType) removedSensor = sensor
                runCatching { manager.registerListener(listener, sensor, 10_000, Handler(thread.looper)) }
                    .onSuccess { if (it) registered.put(type) }
                    .onFailure { errors.put("$type:${it.javaClass.simpleName}") }
            }
            SystemClock.sleep(removeAt)
            if (removeType >= 0 && removedSensor != null) {
                // Serialize removal with delivery to distinguish queued callbacks from new ones.
                val done = java.util.concurrent.CountDownLatch(1)
                Handler(thread.looper).post {
                    manager.unregisterListener(listener, removedSensor)
                    removed.set(true)
                    done.countDown()
                }
                check(done.await(5, java.util.concurrent.TimeUnit.SECONDS))
            }
            SystemClock.sleep(duration - removeAt)
        } finally {
            val done = java.util.concurrent.CountDownLatch(1)
            Handler(thread.looper).post { manager.unregisterListener(listener); done.countDown() }
            done.await(5, java.util.concurrent.TimeUnit.SECONDS)
            thread.quitSafely()
            thread.join(5000)
        }
        val grant = Bundle()
        val location = context.getSystemService(LocationManager::class.java)
        val grantAccepted = runCatching { location.sendExtraCommand("fused_ext", "get_sensor_state", grant) }.getOrDefault(false)
        val exchange = Bundle()
        val controlAccepted = runCatching { location.sendExtraCommand("fused_ext", "exchange_key", exchange) }.getOrDefault(false)
        return JSONObject().apply {
            put("package", context.packageName)
            put("uid", android.os.Process.myUid())
            put("startedNanos", started)
            put("endedNanos", SystemClock.elapsedRealtimeNanos())
            put("registered", registered)
            put("errors", errors)
            put("callbacksAfterRemoval", late.get())
            put("grantAccepted", grantAccepted)
            put("grantKeys", JSONArray(grant.keySet().sorted()))
            put("controllerKeyGranted", controlAccepted && exchange.containsKey("key"))
            put("series", JSONObject().apply {
                samples.entries.sortedBy { it.key }.forEach { (type, events) -> put(type.toString(), JSONArray(events)) }
            })
        }
    }
}
