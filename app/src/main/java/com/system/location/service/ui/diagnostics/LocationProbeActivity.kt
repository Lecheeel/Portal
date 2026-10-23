package com.system.location.service.ui.diagnostics

import android.Manifest
import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.*
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.location.*
import com.google.android.gms.tasks.CancellationTokenSource
import com.system.location.service.databinding.ActivityLocationProbeBinding

/** Separate process, same package/UID. This observer never writes providers or mock AppOps. */
class LocationProbeActivity : AppCompatActivity() {
    class State : ViewModel() { val journal = LocationProbeJournal() }
    private lateinit var binding: ActivityLocationProbeBinding
    private val journal by lazy { ViewModelProvider(this)[State::class.java].journal }
    private val manager by lazy { getSystemService(LocationManager::class.java) }
    private val handler = Handler(Looper.getMainLooper())
    private val listeners = mutableListOf<LocationListener>()
    private val cancellations = mutableListOf<CancellationSignal>()
    private val timeouts = mutableListOf<Runnable>()
    private var gmsClient: FusedLocationProviderClient? = null
    private var gmsCallback: LocationCallback? = null
    private var gmsCancellation: CancellationTokenSource? = null
    private var gmsRegistration: com.google.android.gms.tasks.Task<Void>? = null
    private var active = false
    private var wanted = true
    private var generation = 0L
    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (wanted && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) startReceiving()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLocationProbeBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        binding.closeProbe.setOnClickListener { finish() }
        binding.startProbe.setOnClickListener {
            wanted = true
            if (!hasPermission()) permissions.launch(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION))
            else { stopReceiving(); startReceiving() }
        }
        binding.stopProbe.setOnClickListener { wanted = false; stopReceiving(); render() }
        binding.copyProbe.setOnClickListener {
            getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("位置接收诊断", report()))
            Toast.makeText(this, "接收日志已复制", Toast.LENGTH_SHORT).show()
        }
        render()
    }

    override fun onStart() { super.onStart(); if (wanted) startReceiving() }
    override fun onStop() { stopReceiving(); super.onStop() }
    private fun hasPermission() = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    private fun current(token: Long) = active && token == generation
    private fun event(source: String, detail: String) { journal.message(source, detail); render() }
    private fun receive(source: String, location: Location?, token: Long) {
        if (!current(token)) return
        val fix = location?.let { ProbeFix(it.provider, it.latitude, it.longitude, it.isMock, it.elapsedRealtimeNanos, it.time, it.accuracy) }
        journal.receive(source, fix, SystemClock.elapsedRealtimeNanos()); render()
    }
    private fun report() = "LocationService 接收端 ${com.system.location.service.BuildConfig.VERSION_NAME}\nAndroid ${Build.VERSION.RELEASE} API ${Build.VERSION.SDK_INT} ROM=${Build.DISPLAY}\nprocess=${android.app.Application.getProcessName()} uid=${Process.myUid()}\n" + journal.report()
    private fun render() {
        binding.probeStatus.text = if (active) "正在前台接收 · 收到 ${journal.sampleCount} 条 · mock ${journal.mockCount} 条" else "接收已停止；无新样本不能证明不存在模拟位置"
        binding.probeLog.text = report()
    }

    @SuppressLint("MissingPermission")
    private fun startReceiving() {
        if (active) return
        if (!hasPermission()) { event("permission", "请点击重新订阅并授予精确位置权限"); return }
        active = true
        val token = ++generation
        event("session", "开始订阅 generation=$token；只读观察，不修改模拟授权")
        for (provider in listOf("gps", "network", "passive", "fused")) {
            if (!manager.allProviders.contains(provider)) { event(provider, "系统未提供此通道"); continue }
            runCatching { manager.getLastKnownLocation(provider) }
                .onSuccess { receive("lastKnown/$provider", it, token) }.onFailure { event(provider, "缓存读取失败：${it.javaClass.simpleName}") }
            val listener = object : LocationListener {
                override fun onLocationChanged(location: Location) { receive("updates/$provider", location, token) }
                override fun onProviderDisabled(name: String) { if (current(token)) event(provider, "Provider 已禁用") }
            }
            listeners += listener
            runCatching { manager.requestLocationUpdates(provider, 1000L, 0f, listener, Looper.getMainLooper()) }
                .onFailure { event(provider, "持续订阅失败：${it.javaClass.simpleName}") }
            if (provider == "passive") continue // Passive observes other requests, it is not an independent fix source.
            val signal = CancellationSignal().also { cancellations += it }
            var complete = false
            val timeout = Runnable {
                if (current(token) && !complete) { complete = true; signal.cancel(); event("current/$provider", "10 秒超时，无样本") }
            }
            timeouts += timeout; handler.postDelayed(timeout, 10_000)
            runCatching {
                manager.getCurrentLocation(provider, signal, mainExecutor) { location ->
                    if (!complete) { complete = true; handler.removeCallbacks(timeout); receive("current/$provider", location, token) }
                }
            }.onFailure { complete = true; handler.removeCallbacks(timeout); event(provider, "单次请求失败：${it.javaClass.simpleName}") }
        }
        startGms(token)
    }

    @SuppressLint("MissingPermission")
    private fun startGms(token: Long) {
        if (GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(this) != ConnectionResult.SUCCESS) {
            event("FLP", "Google Play 服务不可用"); return
        }
        runCatching {
            val client = LocationServices.getFusedLocationProviderClient(this).also { gmsClient = it }
            client.lastLocation.addOnSuccessListener { receive("lastKnown/FLP", it, token) }
                .addOnFailureListener { if (current(token)) event("FLP", "缓存读取失败：${it.javaClass.simpleName}") }
            val cancellation = CancellationTokenSource().also { gmsCancellation = it }
            client.getCurrentLocation(CurrentLocationRequest.Builder().setPriority(Priority.PRIORITY_HIGH_ACCURACY)
                .setMaxUpdateAgeMillis(0).setDurationMillis(10_000).build(), cancellation.token)
                .addOnSuccessListener { receive("current/FLP", it, token) }
                .addOnFailureListener { if (current(token)) event("FLP", "单次请求失败：${it.javaClass.simpleName}") }
            val callback = object : LocationCallback() {
                override fun onLocationResult(result: LocationResult) { result.locations.forEach { receive("updates/FLP", it, token) } }
            }.also { gmsCallback = it }
            gmsRegistration = client.requestLocationUpdates(LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000L).build(), callback, Looper.getMainLooper())
                .addOnFailureListener { if (current(token)) event("FLP", "持续订阅失败：${it.javaClass.simpleName}") }
        }.onFailure { event("FLP", "初始化失败：${it.javaClass.simpleName}") }
    }

    private fun stopReceiving() {
        val wasActive = active
        active = false; generation++
        timeouts.forEach(handler::removeCallbacks); timeouts.clear()
        cancellations.forEach { runCatching { it.cancel() } }; cancellations.clear()
        listeners.forEach { runCatching { manager.removeUpdates(it) } }; listeners.clear()
        gmsCancellation?.cancel(); gmsCancellation = null
        val client = gmsClient
        gmsCallback?.let { callback ->
            if (client != null) runCatching {
                // A subscription can finish after onStop; queue removal after that task.
                gmsRegistration?.continueWithTask(java.util.concurrent.Executor { it.run() }) { client.removeLocationUpdates(callback) }
                    ?: client.removeLocationUpdates(callback)
            }
        }
        gmsRegistration = null
        gmsCallback = null; gmsClient = null
        if (wasActive) event("session", "已取消订阅；忽略旧会话回调")
    }
}
