package com.system.location.service.hook.hooks.ble

import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanRecord
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.system.location.service.core.environment.BleBeacon
import com.system.location.service.core.environment.EnvironmentReplayPolicy
import com.system.location.service.core.environment.EnvironmentSourceMode
import com.system.location.service.core.environment.ProceduralBleSource
import com.system.location.service.core.geo.Wgs84
import com.system.location.service.core.routing.RoutingDecision
import com.system.location.service.hook.hooks.ListenerRouting
import com.system.location.service.hook.runtime.Reflection
import com.system.location.service.hook.scope.HookInstaller
import com.system.location.service.hook.utils.FakeLoc
import com.system.location.service.hook.utils.Logger
import com.system.location.service.hook.utils.onceHook
import io.github.libxposed.api.XposedInterface.Hooker
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Replaces callback-based BLE scan observations in the target application process. */
object BleScanHook {
    private data class ScanState(
        val filters: List<ScanFilter>,
        val callbackType: Int = ScanSettings.CALLBACK_TYPE_ALL_MATCHES,
        val reportDelayMillis: Long = 0L,
        val nextIndex: AtomicInteger = AtomicInteger(),
        val seenAddresses: MutableSet<String> = mutableSetOf(),
        val active: AtomicBoolean = AtomicBoolean(true),
        var task: Runnable? = null,
    )

    private val states = WeakHashMap<ScanCallback, ScanState>()
    private val handler = Handler(Looper.getMainLooper())
    private val injectingCallback = ThreadLocal.withInitial { false }
    private val pendingFilters = PendingScanRegistry<PendingIntent, ScanFilter>()

    fun install(classLoader: ClassLoader, packageName: String, uid: Int) {
        val scanner = Reflection.findClassIfExists("android.bluetooth.le.BluetoothLeScanner", classLoader)
            ?: return
        HookInstaller.hookAllMethods(scanner, "startScan", Hooker { chain ->
            val callbackIndex = chain.executable.parameterTypes.indexOfFirst {
                ScanCallback::class.java.isAssignableFrom(it)
            }
            val filters = chain.args.filterIsInstance<List<*>>().firstOrNull()
                ?.filterIsInstance<ScanFilter>().orEmpty()
            val callback = chain.args.getOrNull(callbackIndex) as? ScanCallback
            if (callback == null) {
                val pendingIntent = chain.args.filterIsInstance<PendingIntent>().firstOrNull()
                    ?: return@Hooker chain.proceed()
                val result = chain.proceed()
                pendingFilters.register(pendingIntent, filters)
                return@Hooker result
            }
            val settings = chain.args.filterIsInstance<ScanSettings>().firstOrNull()
            val state = ScanState(
                filters = filters,
                callbackType = settings?.callbackType ?: ScanSettings.CALLBACK_TYPE_ALL_MATCHES,
                reportDelayMillis = settings?.reportDelayMillis ?: 0L,
            )
            synchronized(states) { states.put(callback, state) }?.let(::cancel)
            hookCallback(callback, packageName, uid)
            val result = chain.proceed()
            scheduleSyntheticScan(callback, state, packageName, uid)
            result
        })
        HookInstaller.hookAllMethods(scanner, "stopScan", Hooker { chain ->
            chain.args.filterIsInstance<PendingIntent>().firstOrNull()?.let { pending ->
                pendingFilters.unregister(pending)
            }
            chain.args.filterIsInstance<ScanCallback>().firstOrNull()?.let { callback ->
                synchronized(states) { states.remove(callback) }?.let(::cancel)
            }
            chain.proceed()
        })
        HookInstaller.hookAllMethods(Intent::class.java, "getExtras", Hooker { chain ->
            val intent = chain.thisObject as? Intent ?: return@Hooker chain.proceed()
            val original = chain.proceed() as? Bundle ?: return@Hooker null
            val rewritten = rewritePendingIntentResults(original, packageName, uid) ?: return@Hooker original
            intent.replaceExtras(rewritten)
            rewritten
        })
        HookInstaller.hookAllMethods(Intent::class.java, "getParcelableArrayListExtra", Hooker { chain ->
            val intent = chain.thisObject as? Intent ?: return@Hooker chain.proceed()
            if (chain.args.firstOrNull() != BluetoothLeScanner.EXTRA_LIST_SCAN_RESULT) return@Hooker chain.proceed()
            val originalExtras = runCatching { Reflection.getObjectField(intent, "mExtras") as? Bundle }.getOrNull()
                ?: return@Hooker chain.proceed()
            val rewritten = rewritePendingIntentResults(originalExtras, packageName, uid)
                ?: return@Hooker chain.proceed()
            intent.replaceExtras(rewritten)
            rewritten.getParcelableArrayList<ScanResult>(BluetoothLeScanner.EXTRA_LIST_SCAN_RESULT)
        })
        HookInstaller.hookAllMethods(Intent::class.java, "getSerializableExtra", Hooker { chain ->
            val intent = chain.thisObject as? Intent ?: return@Hooker chain.proceed()
            if (chain.args.firstOrNull() != BluetoothLeScanner.EXTRA_LIST_SCAN_RESULT) return@Hooker chain.proceed()
            val originalExtras = runCatching { Reflection.getObjectField(intent, "mExtras") as? Bundle }.getOrNull()
                ?: return@Hooker chain.proceed()
            val rewritten = rewritePendingIntentResults(originalExtras, packageName, uid)
                ?: return@Hooker chain.proceed()
            intent.replaceExtras(rewritten)
            rewritten.getSerializable(BluetoothLeScanner.EXTRA_LIST_SCAN_RESULT)
        })
    }

    private fun rewritePendingIntentResults(bundle: Bundle, packageName: String, uid: Int): Bundle? {
        val resultKey = BluetoothLeScanner.EXTRA_LIST_SCAN_RESULT
        if (!FakeLoc.enable || !bundle.containsKey(resultKey)) return null

        val rewritten = Bundle(bundle)
        val errorCode = rewritten.getInt(BluetoothLeScanner.EXTRA_ERROR_CODE, 0)
        if (errorCode != 0) {
            rewritten.putParcelableArrayList(resultKey, arrayListOf())
            return rewritten
        }

        val selection = pendingFilters.selection()
        val results = (selection as? PendingScanSelection.Unique)?.let { selected ->
            matchingSyntheticResults(packageName, uid, ScanState(selected.filters))
        }.orEmpty()
        if (results.isEmpty()) {
            rewritten.putParcelableArrayList(resultKey, arrayListOf())
            rewritten.putInt(BluetoothLeScanner.EXTRA_ERROR_CODE, ScanCallback.SCAN_FAILED_FEATURE_UNSUPPORTED)
        } else {
            rewritten.putParcelableArrayList(resultKey, ArrayList(results))
            rewritten.remove(BluetoothLeScanner.EXTRA_ERROR_CODE)
        }
        return rewritten
    }

    private fun hookCallback(callback: ScanCallback, packageName: String, uid: Int) {
        Reflection.findMethodExactIfExists(callback.javaClass, "onScanResult", Int::class.javaPrimitiveType!!,
            ScanResult::class.java)?.onceHook(Hooker { chain ->
            if (injectingCallback.get() == true) return@Hooker chain.proceed()
            if (!FakeLoc.enable) return@Hooker chain.proceed()
            val state = synchronized(states) { states[chain.thisObject as? ScanCallback] }
                ?: return@Hooker null
            val results = matchingSyntheticResults(packageName, uid, state)
            if (results.isEmpty()) return@Hooker null
            val result = results[Math.floorMod(state.nextIndex.getAndIncrement(), results.size)]
            val args = chain.args.toTypedArray()
            args[1] = result
            chain.proceed(args)
        })
        Reflection.findMethodExactIfExists(callback.javaClass, "onBatchScanResults", List::class.java)
            ?.onceHook(Hooker { chain ->
                if (injectingCallback.get() == true) return@Hooker chain.proceed()
                if (!FakeLoc.enable) return@Hooker chain.proceed()
                val state = synchronized(states) { states[chain.thisObject as? ScanCallback] }
                    ?: return@Hooker null
                val results = matchingSyntheticResults(packageName, uid, state)
                if (results.isEmpty()) return@Hooker null
                val args = chain.args.toTypedArray()
                args[0] = results
                chain.proceed(args)
            })
        Reflection.findMethodExactIfExists(callback.javaClass, "onScanFailed", Int::class.javaPrimitiveType!!)
            ?.onceHook(Hooker { chain ->
                synchronized(states) { states[chain.thisObject as? ScanCallback] }?.let(::cancel)
                chain.proceed()
            })
    }

    private fun scheduleSyntheticScan(callback: ScanCallback, state: ScanState, packageName: String, uid: Int) {
        val task = object : Runnable {
            override fun run() {
                if (!state.active.get()) return
                if (!FakeLoc.enable) {
                    handler.postDelayed(this, RESCAN_INTERVAL_MILLIS)
                    return
                }
                val results = matchingSyntheticResults(packageName, uid, state)
                if (results.isNotEmpty()) {
                    if (state.reportDelayMillis > 0L) {
                        dispatch(callback) {
                            Reflection.callMethod(callback, "onBatchScanResults", ArrayList(results))
                        }
                    } else {
                        results.forEach { result ->
                            val address = result.device.address
                            val isFirst = synchronized(state.seenAddresses) { state.seenAddresses.add(address) }
                            when {
                                isFirst && state.callbackType and ScanSettings.CALLBACK_TYPE_FIRST_MATCH != 0 ->
                                    dispatch(callback) {
                                        Reflection.callMethod(callback, "onScanResult", ScanSettings.CALLBACK_TYPE_FIRST_MATCH, result)
                                    }
                                state.callbackType and ScanSettings.CALLBACK_TYPE_ALL_MATCHES != 0 ->
                                    dispatch(callback) {
                                        Reflection.callMethod(callback, "onScanResult", ScanSettings.CALLBACK_TYPE_ALL_MATCHES, result)
                                    }
                                isFirst -> dispatch(callback) {
                                    Reflection.callMethod(callback, "onScanResult", ScanSettings.CALLBACK_TYPE_FIRST_MATCH, result)
                                }
                            }
                            if (isFirst && state.callbackType and ScanSettings.CALLBACK_TYPE_MATCH_LOST != 0) {
                                handler.postDelayed({
                                    if (state.active.get()) dispatch(callback) {
                                        Reflection.callMethod(
                                            callback, "onScanResult", ScanSettings.CALLBACK_TYPE_MATCH_LOST, result,
                                        )
                                    }
                                }, MATCH_LOST_DELAY_MILLIS)
                            }
                        }
                    }
                }
                if (state.callbackType and ScanSettings.CALLBACK_TYPE_ALL_MATCHES != 0 || state.reportDelayMillis > 0L) {
                    handler.postDelayed(this, RESCAN_INTERVAL_MILLIS)
                }
            }
        }
        state.task = task
        if (state.active.get()) handler.postDelayed(task, INITIAL_RESULT_DELAY_MILLIS)
    }

    private fun dispatch(callback: ScanCallback, action: () -> Any?) {
        val previous = injectingCallback.get()
        injectingCallback.set(true)
        try {
            action()
        } finally {
            injectingCallback.set(previous)
        }
    }

    private fun cancel(state: ScanState) {
        state.active.set(false)
        state.task?.let(handler::removeCallbacks)
    }

    private fun matchingSyntheticResults(packageName: String, uid: Int, state: ScanState): List<ScanResult> {
        val route = ListenerRouting.decisionFor(packageName, uid)
        if (route == RoutingDecision.Deny) return emptyList()
        val point = when (route) {
            is RoutingDecision.Point -> route.point
            is RoutingDecision.Scenario -> FakeLoc.snapshotForScenario(route.scenarioId).let {
                Wgs84(it.latitude, it.longitude)
            }
            else -> Wgs84(FakeLoc.latitude, FakeLoc.longitude)
        }
        val replay = if (FakeLoc.environmentSourceMode == EnvironmentSourceMode.PROCEDURAL) null else {
            FakeLoc.replayEnvironment?.takeIf { EnvironmentReplayPolicy.isApplicable(it, point) }
        }
        val beacons = replay?.ble?.takeIf { it.isNotEmpty() }
            ?: ProceduralBleSource().beaconsAt(point, FakeLoc.environmentSeed)
        val generated = beacons.mapNotNull(::buildScanResult)
            .filter { result -> state.filters.isEmpty() || state.filters.any { filter ->
                runCatching { filter.matches(result) }.getOrDefault(false)
            } }
        if (generated.isEmpty() && FakeLoc.enableDebugLog) {
            Logger.warn("BLE scan: no synthetic results match filters for $packageName")
        }
        return generated
    }

    private fun buildScanResult(beacon: BleBeacon): ScanResult? = runCatching {
        val adapter = BluetoothAdapter.getDefaultAdapter() ?: return null
        val device = adapter.getRemoteDevice(beacon.address)
        val bytes = beacon.scanRecordHex?.takeIf(BleBeacon::isValidAdvertisementHex)
            ?.let(::hexToBytes) ?: synthesizedAdvertisementBytes(beacon)
        val parsed = parseScanRecord(bytes)
        val record = parsed ?: parseScanRecord(synthesizedAdvertisementBytes(beacon))
            ?: return null
        ScanResult(device, record, beacon.rssi, SystemClock.elapsedRealtimeNanos())
    }.getOrNull()

    private fun parseScanRecord(bytes: ByteArray): ScanRecord? =
        runCatching {
            Reflection.callStaticMethod(ScanRecord::class.java, "parseFromBytes", bytes) as? ScanRecord
        }.getOrNull()

    private fun hexToBytes(hex: String): ByteArray =
        ByteArray(hex.length / 2) { index -> hex.substring(index * 2, index * 2 + 2).toInt(16).toByte() }

    private fun synthesizedAdvertisementBytes(beacon: BleBeacon): ByteArray {
        val name = beacon.name.take(29).toByteArray(Charsets.UTF_8)
        val nameField = byteArrayOf((name.size + 1).toByte(), 0x09) + name
        val txPower = beacon.txPowerDbm?.let { byteArrayOf(2, 0x0A, it.toByte()) } ?: byteArrayOf()
        return byteArrayOf(2, 0x01, 0x06) + txPower + nameField
    }

    private const val INITIAL_RESULT_DELAY_MILLIS = 250L
    private const val RESCAN_INTERVAL_MILLIS = 5_000L
    private const val MATCH_LOST_DELAY_MILLIS = 15_000L
}
