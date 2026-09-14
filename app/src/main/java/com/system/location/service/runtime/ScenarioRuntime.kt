package com.system.location.service.runtime

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.system.location.service.LocationServiceApp
import com.system.location.service.backend.xposed.XposedBackend
import com.system.location.service.core.backend.*
import com.system.location.service.core.runtime.*
import com.system.location.service.core.scenario.Scenario
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
            else -> error("此后端尚未安装")
        }
    }, object : RuntimeClock {
        override fun nanos() = SystemClock.elapsedRealtimeNanos()
        override fun millis() = System.currentTimeMillis()
    })
    val state = controller.state
    val diagnostics = controller.diagnostics
    @Volatile private var service: ScenarioService? = null
    @Volatile private var ready = CompletableDeferred<Unit>()
    private val initialized by lazy {
        scope.async {
            controller.selectBackend(BackendType.XPOSED)
            if (preferences.getBoolean("interrupted", false)) {
                controller.interrupted("上次场景未正常结束，运行进程已中断；请重试停止或重新启动")
            }
        }
    }

    fun initialize() { initialized.start() }

    fun selectBackend(type: BackendType) = command { controller.selectBackend(type) }
    fun start(scenario: Scenario) : Deferred<Boolean> {
        val frozen = scenario.frozen()
        return command {
            try {
                if (service == null) {
                    ready = CompletableDeferred()
                    withContext(Dispatchers.Main) {
                        ContextCompat.startForegroundService(context, Intent(context, ScenarioService::class.java))
                    }
                    withTimeout(10_000) { ready.await() }
                }
                check(preferences.edit().putBoolean("interrupted", true).commit()) { "无法持久化运行标记" }
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
    fun resume() = command { controller.resume() }
    fun motion(bearing: Double, moving: Boolean) = command { controller.setMotion(bearing, moving) }
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
    internal fun attached(instance: ScenarioService) { service = instance; ready.complete(Unit) }
    internal fun attachmentFailed(error: Exception) { ready.completeExceptionally(error) }
    internal fun detached(instance: ScenarioService) {
        if (service !== instance) return
        service = null
        command {
            controller.interrupted("前台服务已被系统终止，场景已中断")
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
        service?.let { instance -> service = null; instance.finishPlayback() }
    }
}
