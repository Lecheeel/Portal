package com.system.location.service.hook.hooks.sensor

import android.content.Context
import android.hardware.Sensor
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.system.location.service.core.sensors.SensorEventValues
import com.system.location.service.core.sensors.SensorPlayback
import com.system.location.service.core.sensors.SensorCadence
import com.system.location.service.hook.runtime.HookRuntime
import com.system.location.service.hook.runtime.BackgroundQuery
import com.system.location.service.core.sensors.SensorPlaybackState
import com.system.location.service.hook.scope.HookInstaller
import java.lang.reflect.Method

/** Java SensorEventQueue extension. Hardware registration still controls permissions/availability. */
object ApplicationSensorHook {
    private val lock = Any()
    private val playback = SensorPlayback()
    private val registrations = mutableListOf<Registration>()
    private var worker: Handler? = null
    private var lastPoll = 0L
    private var queryContext: Context? = null
    private val query = BackgroundQuery()
    private lateinit var dispatch: Method
    private lateinit var sensorHandle: Method
    private var lastSyntheticCounter = 0L
    private var counterOffset = 0L
    private var counterSessionActive = false
    private var suppressHardware = false
    private var session: String? = null
    private var generation = 0L
    private val supported = SensorEventValues.continuousTypes + setOf(18, 19)

    private class Registration(val manager: Any, val listener: Any, val queue: Any,
        val handle: Int, val type: Int, val handler: Handler) {
        var active = true
        var lastSteps = -1L
        var lastDetectorTimestampNanos = 0L
        var pending = false
    }

    fun install(loader: ClassLoader) {
        val manager = Class.forName("android.hardware.SystemSensorManager", false, loader)
        val queue = Class.forName("android.hardware.SystemSensorManager\$SensorEventQueue", false, loader)
        sensorHandle = Sensor::class.java.getDeclaredMethod("getHandle").apply { isAccessible = true }
        dispatch = queue.getDeclaredMethod("dispatchSensorEvent", Int::class.javaPrimitiveType,
            FloatArray::class.java, Int::class.javaPrimitiveType, Long::class.javaPrimitiveType)
            .apply { isAccessible = true }
        HookInstaller.hookAllMethods(manager, "registerListenerImpl", { chain ->
            val result = chain.proceed()
            if (result == true) runCatching {
                val sensor = chain.args.getOrNull(1) as? Sensor ?: return@runCatching
                if (sensor.type !in supported) return@runCatching
                val owner = chain.thisObject ?: return@runCatching
                val listener = chain.args[0] ?: return@runCatching
                val context = field(owner, "mContext") as Context
                val map = field(owner, "mSensorListeners") as Map<*, *>
                val eventQueue = synchronized(map) { map[listener] } ?: return@runCatching
                val looper = (chain.args.firstOrNull { it is Handler } as? Handler)?.looper
                    ?: field(owner, "mMainLooper") as Looper
                val handle = sensorHandle.invoke(sensor) as Int
                synchronized(lock) {
                    if (registrations.none { it.queue === eventQueue && it.handle == handle }) {
                        registrations.add(Registration(owner, listener, eventQueue, handle, sensor.type, Handler(looper)))
                    }
                    queryContext = context.applicationContext ?: context
                    startWorker()
                }
            }.onFailure { HookRuntime.log(it) }
            result
        })
        HookInstaller.hookAllMethods(manager, "unregisterListenerImpl", { chain ->
            val result = chain.proceed()
            val listener = chain.args.firstOrNull()
            val sensor = chain.args.getOrNull(1) as? Sensor
            val handle = sensor?.let { runCatching { sensorHandle.invoke(it) as Int }.getOrNull() }
            synchronized(lock) {
                // Failed reflection must not unregister a different sensor from our bookkeeping.
                if (sensor == null || handle != null) registrations.removeAll {
                    val remove = it.manager === chain.thisObject && it.listener === listener &&
                        (sensor == null || it.handle == handle)
                    if (remove) it.active = false
                    remove
                }
                stopWorkerIfIdle()
            }
            result
        })
        HookInstaller.hookMethod(dispatch, { chain ->
            val handle = chain.args[0] as Int
            val original = chain.args[1] as FloatArray
            val timestamp = chain.args[3] as Long
            val args = chain.args.toTypedArray()
            val action = synchronized(lock) {
                val reg = registrations.firstOrNull { it.queue === chain.thisObject && it.handle == handle }
                    ?: return@synchronized 0
                // Check receipt time too: an old queued event cannot revive an expired grant.
                if (playback.frame(SystemClock.elapsedRealtimeNanos()) == null) {
                    return@synchronized if (suppressHardware) 2 else 0
                }
                // A queued hardware event from before the current grant must not cross the boundary.
                val frame = playback.frame(timestamp) ?: return@synchronized 2
                if (reg.type == 18 || reg.type == 19) return@synchronized 2
                val values = SensorEventValues.from(frame, reg.type, original.size) ?: return@synchronized 2
                args[1] = values
                1
            }
            when (action) {
                2 -> null // Suppressed hardware events are replaced only by the motion clock.
                1 -> chain.proceed(args)
                else -> chain.proceed()
            }
        })
    }

    private fun field(owner: Any, name: String): Any? {
        var type: Class<*>? = owner.javaClass
        while (type != null) {
            val found = runCatching { type!!.getDeclaredField(name) }.getOrNull()
            if (found != null) return found.apply { isAccessible = true }.get(owner)
            type = type.superclass
        }
        error("Missing ${owner.javaClass.name}.$name")
    }

    private fun startWorker() {
        if (worker != null) return
        // Reuse an app-owned looper. A new named thread is enumerable through
        // Thread.getAllStackTraces() and is a permanent fingerprint in the target process.
        val handler = synchronized(lock) { registrations.firstOrNull()?.handler }
            ?: Handler(Looper.getMainLooper())
        worker = handler.also { it.post(tick) }
    }

    private fun stopWorkerIfIdle() {
        if (registrations.isNotEmpty()) return
        // The Handler belongs to the application; remove only our tick, never its messages.
        worker?.removeCallbacks(tick)
        worker = null
        queryContext = null
        lastPoll = 0L
        playback.update(null, SystemClock.elapsedRealtimeNanos())
        counterSessionActive = false
        suppressHardware = false
        session = null
        generation++
    }

    private data class PollResult(val protected: Boolean?, val state: SensorPlaybackState?)

    private fun pollState(context: Context?, handler: Handler, epoch: Long) {
        try {
            query.submit({
                val manager = context?.getSystemService(LocationManager::class.java)
                val result = Bundle()
                if (manager?.sendExtraCommand("fused_ext", SensorSnapshotCommand.COMMAND, result) != true)
                    PollResult(null, null)
                else PollResult(result.getBoolean(SensorSnapshotCommand.PROTECTED_KEY), SensorStateWire.read(result))
            }) { result ->
                synchronized(lock) {
                    // An unregister/re-register may reuse the same Handler. Its old in-flight
                    // Binder response must not restore a motion grant into the new subscription.
                    if (worker !== handler || generation != epoch) return@synchronized
                    val next = result.getOrDefault(PollResult(null, null))
                    next.protected?.let { suppressHardware = it }
                    if (session != next.state?.session) {
                        session = next.state?.session
                        generation++
                        counterSessionActive = false
                        registrations.forEach { it.lastSteps = -1 }
                    }
                    playback.update(next.state, SystemClock.elapsedRealtimeNanos())
                }
            }
        } catch (failure: RuntimeException) {
            HookRuntime.log(failure)
        }
    }

    private val tick = object : Runnable {
        override fun run() {
            val handler: Handler
            val context: Context?
            val epoch: Long
            val poll: Boolean
            synchronized(lock) {
                handler = worker ?: return
                context = queryContext
                epoch = generation
                val now = SystemClock.elapsedRealtimeNanos()
                poll = now - lastPoll >= 250_000_000
                if (poll) lastPoll = now
            }
            if (poll) pollState(context, handler, epoch)
            synchronized(lock) {
                if (worker !== handler) return
                val now = SystemClock.elapsedRealtimeNanos()
                val frame = playback.frame(now)
                if (frame == null) {
                    counterSessionActive = false
                    registrations.forEach { it.lastSteps = -1 }
                } else {
                    if (!counterSessionActive) {
                        counterOffset = (lastSyntheticCounter - frame.stepCount).coerceAtLeast(0)
                        counterSessionActive = true
                    }
                    counterOffset = maxOf(counterOffset, lastSyntheticCounter - frame.stepCount)
                    registrations.filter { it.type == 18 || it.type == 19 }.forEach { reg ->
                        if (reg.pending) return@forEach
                        val previous = reg.lastSteps
                        val previousDetectorTimestamp = reg.lastDetectorTimestampNanos
                        reg.lastSteps = frame.stepCount
                        if (reg.type == 18 && (previous < 0 || frame.stepCount <= previous)) return@forEach
                        if (reg.type == 19 && frame.stepCount == previous) return@forEach
                        val epoch = generation
                        val deliveries = if (reg.type == 18) (frame.stepCount - previous).coerceIn(1, 32).toInt() else 1
                        val detectorTimestamps = if (reg.type == 18) {
                            SensorCadence.detectorEventTimestamps(now, deliveries, frame.stepCadence,
                                previousDetectorTimestamp).also { reg.lastDetectorTimestampNanos = it.last() }
                        } else longArrayOf(now)
                        reg.pending = true
                        if (!reg.handler.post {
                            val delivery = synchronized(lock) {
                                reg.pending = false
                                if (!reg.active || generation != epoch || playback.frame(SystemClock.elapsedRealtimeNanos()) == null) null
                                else {
                                    val count = maxOf(lastSyntheticCounter, counterOffset + frame.stepCount)
                                    if (reg.type == 19) lastSyntheticCounter = count
                                    floatArrayOf(if (reg.type == 18) 1f else count.toFloat())
                                }
                            }
                            if (delivery != null) repeat(deliveries) { index ->
                                // Recheck after each listener call: it may unregister itself.
                                val active = synchronized(lock) { reg.active && generation == epoch }
                                if (active) HookRuntime.invokeOriginalMethod(dispatch, reg.queue,
                                    arrayOf(reg.handle, delivery, 3, detectorTimestamps[index]))
                            }
                        }) {
                            reg.pending = false
                            reg.lastSteps = previous
                            reg.lastDetectorTimestampNanos = previousDetectorTimestamp
                        }
                    }
                }
                handler.postDelayed(this, 20)
            }
        }
    }

}
