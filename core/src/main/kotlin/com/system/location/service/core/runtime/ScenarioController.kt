package com.system.location.service.core.runtime

import com.system.location.service.core.backend.*
import com.system.location.service.core.playback.PlaybackEngine
import com.system.location.service.core.scenario.Scenario
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Owns the only runtime state. A service supplies ticks; UI only sends commands and observes. */
class ScenarioController(private val factory: (BackendType) -> LocationBackend, private val clock: RuntimeClock,
    private val diagnosticSink: (DiagnosticEvent) -> Unit = {}) {
    private val mutex = Mutex()
    private val mutableState = MutableStateFlow(RuntimeState())
    val state = mutableState.asStateFlow()
    private val mutableDiagnostics = MutableStateFlow<List<DiagnosticEvent>>(emptyList())
    val diagnostics = mutableDiagnostics.asStateFlow()
    private var backend: LocationBackend? = null
    private var engine: PlaybackEngine? = null
    private var orbitEnabled = false
    private var orbitRadiusMeters = 0.2
    private var orbitPeriodSeconds = 20.0
    private var previousSubmissionNanos: Long? = null
    private var healthRecordedAt: Long? = null
    private var storageFailureReported = false
    private val lastRecords = mutableMapOf<Pair<BackendType, String>, Pair<DiagnosticEvent, Long>>()
    @Volatile var intervalMs = 500L
        private set

    suspend fun selectBackend(type: BackendType): Boolean = mutex.withLock {
        if (backend != null || state.value.isActive) return@withLock false
        val instance = createBackend(type) ?: return@withLock false
        mutableState.value = RuntimeState(backend = type, capabilities = instance.capabilities)
        true
    }

    suspend fun start(input: Scenario, type: BackendType = state.value.backend): Boolean = mutex.withLock {
        // Freeze/validate before touching an already-running scenario.
        val next = try { PlaybackEngine(input) } catch (error: IllegalArgumentException) {
            event(BackendResult.Failure("VALIDATE_SCENARIO", error.message ?: "Invalid scenario"))
            if (!state.value.isActive) mutableState.value = state.value.copy(phase = RuntimePhase.ERROR,
                error = BackendResult.Failure("VALIDATE_SCENARIO", error.message ?: "Invalid scenario"))
            return@withLock false
        }
        if (!stopLocked()) return@withLock false
        val instance = createBackend(type) ?: return@withLock false
        backend = instance
        engine = next
        next.setOrbit(orbitEnabled, orbitRadiusMeters, orbitPeriodSeconds)
        intervalMs = next.scenario.profile.intervalMs
        previousSubmissionNanos = null
        healthRecordedAt = null
        mutableState.value = RuntimeState(RuntimePhase.PREPARING, type, input.id, input.name,
            input.route?.id, capabilities = instance.capabilities, configuredSpeedMps = next.scenario.profile.speedMps)
        record("SCENARIO_PARAMETERS", "INFO", "场景=${input.name}；路线点数=${input.route?.points?.size ?: 0}；速度上限=${input.profile.speedMps}m/s；平滑运动=${input.profile.smoothMotion}；间隔=${intervalMs}ms；圆周=$orbitEnabled 半径=${orbitRadiusMeters}m 周期=${orbitPeriodSeconds}s")
        if (!step("PREPARE") { instance.prepare() }) return@withLock false
        mutableState.value = state.value.copy(phase = RuntimePhase.READY, capabilities = instance.capabilities)
        if (!step("START") { instance.start() }) return@withLock false
        mutableState.value = state.value.copy(phase = RuntimePhase.RUNNING, startedAt = clock.millis())
        publishLocked()
    }

    suspend fun tick(): Boolean = mutex.withLock { publishLocked() }
    suspend fun restoreDiagnostics(history: List<DiagnosticEvent>) = mutex.withLock {
        mutableDiagnostics.value = (history + mutableDiagnostics.value).takeLast(300)
    }
    suspend fun note(stage: String, result: String, reason: String) = mutex.withLock { record(stage, result, reason) }

    suspend fun pause(): Boolean = mutex.withLock {
        if (state.value.phase != RuntimePhase.RUNNING) return@withLock false
        // Account for movement up to the pause event, then freeze it.
        if (!publishLocked() || state.value.phase != RuntimePhase.RUNNING) return@withLock false
        if (!step("PAUSE") { backend!!.pause() }) return@withLock false
        engine!!.pause()
        mutableState.value = state.value.copy(phase = RuntimePhase.PAUSED)
        publishLocked()
    }

    suspend fun resume(): Boolean = mutex.withLock {
        if (state.value.phase != RuntimePhase.PAUSED) return@withLock false
        if (!step("RESUME") { backend!!.resume() }) return@withLock false
        engine!!.resume(clock.nanos())
        mutableState.value = state.value.copy(phase = RuntimePhase.RUNNING)
        publishLocked()
    }

    suspend fun setMotion(bearing: Double, moving: Boolean, strength: Double = 1.0): Boolean = mutex.withLock {
        if (engine?.scenario?.route != null || !state.value.isActive || !bearing.isFinite() || !strength.isFinite() || strength !in 0.0..1.0) return@withLock false
        if (!publishLocked()) return@withLock false
        engine?.setMotion(bearing, moving, strength)
        true
    }

    suspend fun configureOrbit(enabled: Boolean, radiusMeters: Double, periodSeconds: Double = 20.0): Boolean = mutex.withLock {
        if (!radiusMeters.isFinite() || radiusMeters !in 0.05..5.0 || !periodSeconds.isFinite() || periodSeconds !in 5.0..120.0) return@withLock false
        orbitEnabled = enabled
        orbitRadiusMeters = radiusMeters
        orbitPeriodSeconds = periodSeconds
        engine?.setOrbit(enabled, radiusMeters, periodSeconds)
        true
    }
    suspend fun setSpeed(speed: Double): Boolean = mutex.withLock {
        if (!speed.isFinite() || speed !in 0.0..1000.0 ||
            state.value.phase !in setOf(RuntimePhase.RUNNING, RuntimePhase.PAUSED)) return@withLock false
        // Settle elapsed movement at the old speed before changing it.
        if (!publishLocked() || !state.value.isActive) return@withLock false
        engine!!.setSpeed(speed)
        mutableState.value = state.value.copy(configuredSpeedMps = speed)
        publishLocked()
    }

    suspend fun stop(): Boolean = mutex.withLock { stopLocked() }

    suspend fun reportFailure(failure: BackendResult.Failure) = mutex.withLock {
        stopLocked()
        mutableState.value = state.value.copy(phase = RuntimePhase.ERROR, error = failure)
        event(failure)
    }

    suspend fun refreshDiagnostics() = mutex.withLock {
        val instance = backend ?: createBackend(state.value.backend) ?: return@withLock
        try {
            instance.diagnose().forEach { diagnostic ->
                record(diagnostic.stage, diagnostic.result, diagnostic.reason, diagnostic.suggestion)
            }
            mutableState.value = state.value.copy(capabilities = instance.capabilities)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { event(BackendResult.Failure("DIAGNOSE", error.message ?: "诊断失败")) }
    }

    private fun createBackend(type: BackendType): LocationBackend? = try { factory(type) }
    catch (cancelled: CancellationException) { throw cancelled }
    catch (error: Exception) {
        val failure = BackendResult.Failure("CREATE_BACKEND", error.message ?: error.javaClass.simpleName)
        mutableState.value = RuntimeState(phase = RuntimePhase.ERROR, backend = type, error = failure)
        event(failure)
        null
    }

    suspend fun interrupted(reason: String) = mutex.withLock {
        stopLocked()
        val failure = BackendResult.Failure("PROCESS_INTERRUPTED", reason, "重新选择后端并启动场景")
        mutableState.value = state.value.copy(phase = RuntimePhase.ERROR, error = failure)
        event(failure)
    }

    /** On process recreation, retain a failing backend so the user can retry cleanup. */
    suspend fun recoverInterrupted(type: BackendType, reason: String) = mutex.withLock {
        backend = createBackend(type) ?: return@withLock
        val clean = stopLocked()
        val failure = BackendResult.Failure("PROCESS_INTERRUPTED", reason +
            if (clean) "; 后端残留资源已清理" else "; 清理未完成: ${state.value.error?.reason}",
            if (clean) "重新启动场景" else "恢复后端授权或连接，然后重试停止")
        mutableState.value = state.value.copy(backend = type, phase = RuntimePhase.ERROR, error = failure)
        event(failure)
    }

    private suspend fun publishLocked(): Boolean {
        if (state.value.phase !in setOf(RuntimePhase.RUNNING, RuntimePhase.PAUSED)) return false
        val frame = engine!!.tick(clock.nanos(), clock.millis())
        val started = clock.nanos()
        if (!step("PUBLISH") { backend!!.publish(frame.sample) }) return false
        val ended = clock.nanos()
        val gap = previousSubmissionNanos?.let { (frame.sample.elapsedNanos - it).coerceAtLeast(0) / 1_000_000.0 } ?: 0.0
        val latency = (ended - started).coerceAtLeast(0) / 1_000_000.0
        previousSubmissionNanos = frame.sample.elapsedNanos
        val old = state.value.submissionHealth
        val health = SubmissionHealth(old.count + 1, gap, maxOf(old.maxGapMs, gap), latency, maxOf(old.maxLatencyMs, latency),
            old.delayedCount + if (gap > maxOf(1000.0, intervalMs * 3.0)) 1 else 0)
        mutableState.value = state.value.copy(sample = frame.sample, currentPoint = frame.segment,
            progress = frame.progress, lastUpdateAt = frame.sample.timeMillis, submissionHealth = health)
        record("PUBLISH", "SUCCESS", "最近定位样本已提交至后端")
        if (healthRecordedAt == null || ended - healthRecordedAt!! >= 5_000_000_000) {
            healthRecordedAt = ended
            record("SUBMISSION_HEALTH", "INFO", "提交=${health.count}；设定间隔=${intervalMs}ms；最近间隔=${gap.toLong()}ms；最大间隔=${health.maxGapMs.toLong()}ms；最近耗时=${latency.toLong()}ms；延迟次数=${health.delayedCount}；仅表示后端 API 提交，不表示目标应用采用")
            try { backend!!.diagnose().forEach { record(it.stage, it.result, it.reason, it.suggestion) } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { record("DIAGNOSE", "FAILED", error.message ?: "诊断失败") }
            mutableState.value = state.value.copy(capabilities = backend!!.capabilities)
        }
        if (frame.completed) return stopLocked()
        return true
    }

    private suspend fun step(stage: String, action: suspend () -> BackendResult): Boolean {
        val result = try { action() } catch (cancelled: CancellationException) {
            withContext(NonCancellable) { stopLocked() }
            throw cancelled
        } catch (error: Exception) {
            BackendResult.Failure(stage, error.message ?: error.javaClass.simpleName)
        }
        if (result is BackendResult.Failure) {
            stopLocked()
            mutableState.value = state.value.copy(phase = RuntimePhase.ERROR, error = result)
            event(result)
            return false
        }
        if (stage != "PUBLISH") record(stage, "SUCCESS", "后端操作完成")
        return true
    }

    private suspend fun stopLocked(): Boolean = withContext(NonCancellable) {
        val active = backend ?: run {
            if (state.value.phase != RuntimePhase.IDLE) mutableState.value = state.value.copy(phase = RuntimePhase.STOPPED, error = null)
            return@withContext true
        }
        mutableState.value = state.value.copy(phase = RuntimePhase.STOPPING)
        suspend fun cleanup(action: suspend () -> BackendResult): BackendResult = try { action() }
        catch (error: Exception) { BackendResult.Failure("CLEANUP", error.message ?: error.javaClass.simpleName) }
        val stopped = cleanup { active.stop() }
        val released = cleanup { active.release() }
        val failure = (released as? BackendResult.Failure) ?: (stopped as? BackendResult.Failure)
        if (released is BackendResult.Success) { backend = null; engine = null }
        mutableState.value = state.value.copy(phase = if (failure == null) RuntimePhase.STOPPED else RuntimePhase.ERROR,
            error = failure, sample = state.value.sample?.copy(speed = 0f))
        failure?.let(::event)
        if (failure == null) record("CLEANUP", "SUCCESS", "后端已停止并释放资源")
        failure == null
    }

    private fun event(failure: BackendResult.Failure) {
        record(failure.stage, "FAILED", failure.reason, failure.suggestion)
    }
    private fun record(stage: String, result: String, reason: String, suggestion: String = "") {
        val now = clock.millis()
        val key = state.value.backend to stage
        val monotonic = clock.nanos()
        val previous = lastRecords[key]
        if (previous != null && previous.first.result == result && previous.first.reason == reason &&
            monotonic - previous.second in 0 until 5_000_000_000 && stage !in setOf("PREPARE", "START", "CLEANUP", "SCENARIO_PARAMETERS")) return
        val event = DiagnosticEvent(now, state.value.backend, stage, result, reason, suggestion)
        lastRecords[key] = event to monotonic
        try { diagnosticSink(event) }
        catch (error: Exception) {
            if (!storageFailureReported) {
                storageFailureReported = true
                mutableDiagnostics.value += DiagnosticEvent(now, state.value.backend, "DIAGNOSTIC_STORAGE", "FAILED", error.message ?: "诊断日志保存失败")
            }
        }
        mutableDiagnostics.value = (mutableDiagnostics.value + event).takeLast(300)
    }
}
