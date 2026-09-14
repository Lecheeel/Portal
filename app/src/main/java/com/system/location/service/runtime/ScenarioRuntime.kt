package com.system.location.service.runtime

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.system.location.service.LocationServiceApp
import com.system.location.service.backend.xposed.XposedBackend
import com.system.location.service.backend.mock.MockProviderBackend
import com.system.location.service.backend.mock.AndroidMockProviderPort
import com.system.location.service.core.backend.*
import com.system.location.service.core.runtime.*
import com.system.location.service.core.scenario.Scenario
import com.system.location.service.ext.experimentalOrbitMotion
import com.system.location.service.ext.experimentalOrbitRadius
import com.system.location.service.ext.speed
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Process-owned command facade. UI cancellation never cancels an accepted runtime command. */
object ScenarioRuntime {
    private val context get() = LocationServiceApp.appContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val commands = Mutex()
    private val preferences get() = context.getSharedPreferences("scenario_runtime", Context.MODE_PRIVATE)
    val controller = ScenarioController({ type ->
        when (type) {
            BackendType.XPOSED -> XposedBackend(context)
            BackendType.MOCK_PROVIDER -> MockProviderBackend(AndroidMockProviderPort(context))
            BackendType.NATIVE -> com.system.location.service.backend.native.NativeBackend(context)
        }
    }, object : RuntimeClock {
        override fun nanos() = SystemClock.elapsedRealtimeNanos()
        override fun millis() = System.currentTimeMillis()
    })
    val state = controller.state
    val diagnostics = controller.diagnostics
    @Volatile private var service: ScenarioService? = null
    @Volatile private var ready = CompletableDeferred<Unit>()
    private val serviceStarts = ServiceStartGate()
    private val initialized by lazy {
        scope.async {
            val selected = runCatching { BackendType.valueOf(preferences.getString("backend", "MOCK_PROVIDER")!!) }
                .getOrDefault(BackendType.MOCK_PROVIDER)
            controller.selectBackend(selected)
            if (preferences.getBoolean("interrupted", false)) {
                controller.recoverInterrupted(selected, "上次场景未正常结束，运行进程已中断")
            }
        }
    }

    fun initialize() { initialized.start() }

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
                controller.configureOrbit(context.experimentalOrbitMotion, context.experimentalOrbitRadius)
                controller.start(frozen).also { if (!state.value.isActive) finishService() }
            } catch (cancelled: CancellationException) {
                if (cancelled !is TimeoutCancellationException) throw cancelled
                failStart(cancelled)
            } catch (error: Exception) { failStart(error) }
        }
    }

    private suspend fun failStart(error: Exception): Boolean {
        controller.reportFailure(BackendResult.Failure("FOREGROUND_SERVICE", error.message ?: error.javaClass.simpleName,
            "在应用前台启动，并授予精确位置权限"))
        finishService()
        return false
    }
    fun pause() = command { controller.pause() }
    fun refreshDiagnostics() = command { controller.refreshDiagnostics(); true }
    fun resume() = command { controller.resume() }
    fun motion(bearing: Double, moving: Boolean) = command { controller.setMotion(bearing, moving) }
    fun setSpeed(speed: Double): Deferred<Boolean> {
        val expected = state.value
        return command {
            val current = state.value
            if (!speed.isFinite() || speed !in 0.0..1000.0 ||
                current.scenarioId != expected.scenarioId || current.startedAt != expected.startedAt ||
                current.phase != expected.phase) return@command false
            val accepted = if (current.isActive) controller.setSpeed(speed) else true
            if (accepted) context.speed = speed
            if (current.isActive && !state.value.isActive) {
                if (state.value.phase == RuntimePhase.STOPPED) preferences.edit().putBoolean("interrupted", false).commit()
                finishService()
            }
            accepted
        }
    }
    fun stop() = command {
        val stopped = controller.stop()
        if (stopped) {
            preferences.edit().putBoolean("interrupted", false).commit()
            finishService()
        }
        stopped
    }

    private fun command(action: suspend () -> Boolean) = scope.async {
        initialized.await()
        commands.withLock { action() }
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
    internal suspend fun tick(instance: ScenarioService) = commands.withLock {
        if (service === instance && state.value.isActive) {
            controller.tick()
            if (!state.value.isActive) {
                if (state.value.phase == RuntimePhase.STOPPED) preferences.edit().putBoolean("interrupted", false).commit()
                finishService()
            }
        }
    }
    private suspend fun finishService() = withContext(Dispatchers.Main) {
        serviceStarts.cancel()
        service?.let { instance -> service = null; instance.finishPlayback() }
    }
}
