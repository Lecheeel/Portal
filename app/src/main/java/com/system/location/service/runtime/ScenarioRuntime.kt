package com.system.location.service.runtime

import android.content.Context
import android.content.Intent
import android.location.LocationManager
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.system.location.service.LocationServiceApp
import com.system.location.service.backend.xposed.XposedBackend
import com.system.location.service.backend.mock.MockProviderBackend
import com.system.location.service.backend.mock.AndroidMockProviderPort
import com.system.location.service.core.backend.*
import com.system.location.service.core.config.ConfigEnvelope
import com.system.location.service.core.environment.CellMode
import com.system.location.service.core.environment.EnvironmentConfig
import com.system.location.service.core.environment.EnvironmentSourceType
import com.system.location.service.core.environment.EnvironmentRestorePolicy
import com.system.location.service.core.playback.NoiseProfile
import com.system.location.service.core.routing.RoutingConfig
import com.system.location.service.core.runtime.*
import com.system.location.service.core.scenario.Scenario
import com.system.location.service.ext.experimentalOrbitMotion
import com.system.location.service.ext.experimentalOrbitRadius
import com.system.location.service.ext.speed
import com.system.location.service.ext.environmentReplayJson
import com.system.location.service.ext.environmentSourceMode
import com.system.location.service.ext.environmentApCount
import com.system.location.service.ext.cellMode
import com.system.location.service.ext.physicalNoise
import com.system.location.service.ext.routingJson
import com.system.location.service.ext.orbitPeriodSeconds
import com.system.location.service.data.persistence.AtomicDocumentStore
import com.system.location.service.BuildConfig
import android.os.Build
import java.io.File
import com.system.location.service.backend.mock.FusedMockBackend
import com.system.location.service.backend.mock.AndroidGmsMockPort
import com.system.location.service.ext.startupBurstEnabled
import com.system.location.service.ext.gmsMockEnabled
import com.system.location.service.ext.platformFusedEnabled
import com.system.location.service.ext.experimentalClearMockFlag
import com.system.location.service.ext.smoothRouteMotion
import com.system.location.service.service.MockServiceHelper
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json

/** Process-owned command facade. UI cancellation never cancels an accepted runtime command. */
object ScenarioRuntime {
    private val context get() = LocationServiceApp.appContext

    /** Serialize system configuration with runtime commands; a Mock Provider session has no Hook config. */
    suspend fun applyConfigNow(): Boolean {
        initialized.await()
        return commands.withLock {
            val current = state.value
            if (!current.isActive || current.backend == BackendType.MOCK_PROVIDER) return@withLock false
            val manager = context.getSystemService(LocationManager::class.java) ?: return@withLock false
            withContext(Dispatchers.IO) {
                if (!MockServiceHelper.isServiceInit()) MockServiceHelper.tryInitService(manager)
                runCatching { MockServiceHelper.putConfig(manager, context) }.getOrDefault(false)
            }
        }
    }
    suspend fun gnssMockRunning(): Boolean {
        initialized.await()
        return commands.withLock {
            if (!state.value.isActive || state.value.backend == BackendType.MOCK_PROVIDER) return@withLock false
            withContext(Dispatchers.IO) {
                runCatching { MockServiceHelper.isGnssMockStart(context.getSystemService(LocationManager::class.java)) }
                    .getOrDefault(false)
            }
        }
    }

    fun setGnssMock(enabled: Boolean) = command {
        if (!state.value.isActive || state.value.backend == BackendType.MOCK_PROVIDER) return@command false
        withContext(Dispatchers.IO) {
            val manager = context.getSystemService(LocationManager::class.java) ?: return@withContext false
            if (enabled) {
                if (!MockServiceHelper.putConfig(manager, context)) return@withContext false
                if (!MockServiceHelper.startGnssMock(manager)) return@withContext false
            } else if (!MockServiceHelper.stopGnssMock(manager)) return@withContext false
            MockServiceHelper.isGnssMockStart(manager) == enabled
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val commands = Mutex()
    private val preferences get() = context.getSharedPreferences("scenario_runtime", Context.MODE_PRIVATE)
    private val journal by lazy { DiagnosticJournal(AtomicDocumentStore(File(context.noBackupFilesDir, "runtime-diagnostics.json"))) }
    private val configStore by lazy { FileConfigStore(File(context.noBackupFilesDir, "active-config.json")) }
    private val writer by lazy { DiagnosticWriter(journal) }
    val controller = ScenarioController({ type ->
        when (type) {
            BackendType.XPOSED -> XposedBackend(context)
            BackendType.MOCK_PROVIDER -> FusedMockBackend(MockProviderBackend(AndroidMockProviderPort(context)), AndroidGmsMockPort(context))
            BackendType.NATIVE -> com.system.location.service.backend.native.NativeBackend(context)
        }
    }, object : RuntimeClock {
        override fun nanos() = SystemClock.elapsedRealtimeNanos()
        override fun millis() = System.currentTimeMillis()
    }, diagnosticBatchSink = { events -> writer.enqueue(events) })
    val state = controller.state
    val diagnostics = controller.diagnostics
    @Volatile private var service: ScenarioService? = null
    @Volatile private var ready = CompletableDeferred<Unit>()
    private val serviceStarts = ServiceStartGate()
    private var startupBurst: StartupBurst? = null
    private val lifecycle = RuntimeCommandLifecycle({ state.value }, {
        check(preferences.edit().putBoolean("interrupted", false).commit()) { "无法清除运行标记" }
    }, { finishService() })
    private val initialized by lazy {
        scope.async {
            scope.launch { writer.failure.collect { controller.reportDiagnosticStorageFailure(it) } }
            withContext(Dispatchers.IO) {
                runCatching { journal.read() }.onSuccess { controller.restoreDiagnostics(it) }
                    .onFailure { controller.note("DIAGNOSTIC_STORAGE", "FAILED", it.message ?: "历史日志读取失败") }
                runCatching { configStore.load() }.getOrNull()?.let { saved ->
                    controller.note("CONFIG_STORE", "INFO", "最近配置：${saved.scenario.name} rev=${saved.revision}")
                }
            }
            val selected = runCatching { BackendType.valueOf(preferences.getString("backend", "MOCK_PROVIDER")!!) }
                .getOrDefault(BackendType.MOCK_PROVIDER)
            controller.selectBackend(selected)
            if (preferences.getBoolean("interrupted", false)) {
                controller.recoverInterrupted(selected, "上次场景未正常结束，运行进程已中断")
            }
        }
    }

    fun initialize() { initialized.start() }

    /** Restore and start the last successfully persisted config; never auto-starts on boot. */
    suspend fun restoreLastConfig(): Boolean {
        initialize()
        initialized.await()
        if (state.value.phase != RuntimePhase.IDLE) return false
        val saved = withContext(Dispatchers.IO) {
            runCatching { configStore.load() }.getOrNull()
        } ?: return false
        withContext(Dispatchers.IO) {
            context.routingJson = Json.encodeToString(RoutingConfig.serializer(), saved.routing)
            context.environmentApCount = saved.environment.apPerCell
            context.cellMode = saved.environment.cellMode.name
            val replayJson = saved.replayEnvironmentJson.orEmpty()
            context.environmentReplayJson = replayJson
            context.environmentSourceMode = EnvironmentRestorePolicy.sourceMode(saved.environment.source, replayJson)
            context.physicalNoise = saved.noise.enabled
        }
        return start(saved.scenario).await()
    }

    fun selectBackend(type: BackendType) = command {
        controller.selectBackend(type).also { if (it) preferences.edit().putString("backend", type.name).commit() }
    }
    fun start(scenario: Scenario) : Deferred<Boolean> {
        val frozen = scenario.frozen()
        return command {
            try {
                if (service == null) {
                    ready = CompletableDeferred()
                    val request = serviceStarts.begin()
                    withContext(Dispatchers.Main) {
                        ContextCompat.startForegroundService(context, Intent(context, ScenarioService::class.java)
                            .putExtra(ScenarioService.REQUEST, request))
                    }
                    withTimeout(10_000) { ready.await() }
                }
                check(preferences.edit().putBoolean("interrupted", true).commit()) { "无法持久化运行标记" }
                controller.configureOrbit(context.experimentalOrbitMotion, context.experimentalOrbitRadius, context.orbitPeriodSeconds)
                val started = controller.start(frozen)
                if (started) {
                    withContext(Dispatchers.IO) {
                        runCatching {
                            val revision = (configStore.load()?.revision ?: 0L) + 1L
                            val routing = context.routingJson.takeIf { it.isNotBlank() }
                                ?.let { Json.decodeFromString(RoutingConfig.serializer(), it) }
                                ?: RoutingConfig()
                            val replay = context.environmentReplayJson.isNotBlank() ||
                                context.environmentSourceMode == "REPLAY"
                            val environment = EnvironmentConfig(
                                source = if (replay) EnvironmentSourceType.REPLAY else EnvironmentSourceType.PROCEDURAL,
                                apPerCell = context.environmentApCount.coerceIn(3, 8),
                                cellMode = runCatching { CellMode.valueOf(context.cellMode) }
                                    .getOrDefault(CellMode.AUTO),
                            )
                            configStore.save(ConfigEnvelope(
                                revision = revision,
                                scenario = frozen,
                                routing = routing,
                                environment = environment,
                                noise = NoiseProfile(enabled = context.physicalNoise),
                                updatedAtMillis = System.currentTimeMillis(),
                                replayEnvironmentJson = context.environmentReplayJson.takeIf { it.isNotBlank() },
                            ))
                        }.onFailure { controller.note("CONFIG_STORE", "FAILED", it.message ?: "配置保存失败") }
                    }
                }
                started.also {
                    startupBurst = if (it && context.startupBurstEnabled && state.value.backend == BackendType.MOCK_PROVIDER)
                        StartupBurst(state.value.scenarioId, state.value.startedAt) else null
                    service?.reschedule()
                }
            } catch (cancelled: CancellationException) {
                if (cancelled !is TimeoutCancellationException) throw cancelled
                failStart(cancelled)
            } catch (error: Exception) { failStart(error) }
        }
    }

    private suspend fun failStart(error: Exception): Boolean {
        controller.reportFailure(BackendResult.Failure("FOREGROUND_SERVICE", error.message ?: error.javaClass.simpleName,
            "在应用前台启动，并授予精确位置权限"))
        return false
    }
    fun pause() = command { startupBurst = null; controller.pause() }
    fun refreshDiagnostics() = command { controller.refreshDiagnostics(); true }
    suspend fun diagnosticReport(): String = withContext(Dispatchers.IO) {
        initialized.await()
        flushDiagnostics()
        commands.withLock {
            buildString {
                appendLine("LocationService ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) revision=${BuildConfig.GIT_REVISION}")
                appendLine("Android ${Build.VERSION.RELEASE} API ${Build.VERSION.SDK_INT} ROM=${Build.DISPLAY}")
                appendLine(BackgroundGuidance.status(context))
                appendLine("状态=${state.value.phase} 后端=${state.value.backend} 配置间隔=${controller.intervalMs}ms")
                appendLine("当前偏好（运行场景可能持有启动时快照）：GMS=${context.gmsMockEnabled} 系统Fused=${context.platformFusedEnabled} 密集启动=${context.startupBurstEnabled} 清除标记=${context.experimentalClearMockFlag} 平滑运动=${context.smoothRouteMotion} 圆周=${context.experimentalOrbitMotion} 半径=${context.experimentalOrbitRadius}m 周期=${context.orbitPeriodSeconds}s")
                appendLine("以下是 API 提交及运行诊断，不能证明目标 App 接受定位。")
                controller.diagnostics.value.forEach { appendLine("${java.time.Instant.ofEpochMilli(it.timestamp)} ${it.backend} ${it.stage} ${it.result}: ${it.reason} ${it.suggestion}") }
            }
        }
    }
    fun resume() = command { controller.resume() }
    fun rebuildMockProviders() = command {
        startupBurst = null
        controller.rebuildMockProviders().also {
            service?.reschedule()
        }
    }
    fun motion(bearing: Double, moving: Boolean, strength: Double = 1.0) = command { controller.setMotion(bearing, moving, strength) }
    fun setSpeed(speed: Double): Deferred<Boolean> {
        val expected = state.value
        return command {
            val current = state.value
            if (!speed.isFinite() || speed !in 0.0..1000.0 ||
                current.scenarioId != expected.scenarioId || current.startedAt != expected.startedAt ||
                current.phase != expected.phase) return@command false
            val accepted = if (current.isActive) controller.setSpeed(speed) else true
            if (accepted) context.speed = speed
            accepted
        }
    }
    fun stop() = command {
        startupBurst = null
        controller.stop()
    }

    private fun command(action: suspend () -> Boolean) = scope.async {
        initialized.await()
        try {
            commands.withLock { lifecycle.execute(action) }
        } finally {
            if (!state.value.isActive) flushDiagnostics()
        }
    }
    private suspend fun flushDiagnostics() {
        controller.reportDiagnosticStorageFailure(writer.flush().exceptionOrNull())
    }
    internal fun attached(instance: ScenarioService, request: Long): Boolean {
        if (!serviceStarts.accept(request)) return false
        service = instance
        ready.complete(Unit)
        return true
    }
    internal fun attachmentFailed(request: Long, error: Exception) {
        if (serviceStarts.accept(request)) ready.completeExceptionally(error)
    }
    internal fun detached(instance: ScenarioService) {
        if (service !== instance) return
        service = null
        command {
            // An old service can finish destruction after a replacement has attached.
            if (service == null) controller.interrupted("前台服务已被系统终止，场景已中断")
            false
        }
    }
    internal suspend fun nextTickDelay(instance: ScenarioService): Long = commands.withLock {
        if (service !== instance) controller.intervalMs else startupBurst?.nextDelay(state.value, controller.intervalMs) ?: controller.intervalMs
    }
    internal suspend fun tick(instance: ScenarioService) {
        try {
            commands.withLock {
                if (service === instance) {
                    lifecycle.execute {
                        if (state.value.isActive) {
                            startupBurst?.accept(state.value)
                            controller.tick()
                        }
                    }
                }
            }
        } finally {
            if (!state.value.isActive) flushDiagnostics()
        }
    }
    private suspend fun finishService() = withContext(Dispatchers.Main) {
        startupBurst = null
        serviceStarts.cancel()
        service?.let { instance -> service = null; instance.finishPlayback() }
    }
}
