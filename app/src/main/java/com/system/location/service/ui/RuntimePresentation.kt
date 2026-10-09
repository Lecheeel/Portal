package com.system.location.service.ui

import android.os.SystemClock
import android.widget.TextView
import com.system.location.service.core.backend.BackendType
import com.system.location.service.core.runtime.RuntimePhase
import com.system.location.service.core.runtime.RuntimeState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** Limit sample-only redraws to 5 Hz; lifecycle, backend, errors and capabilities remain immediate. */
fun Flow<RuntimeState>.displayStates(): Flow<RuntimeState> = flow {
    var previous: RuntimeState? = null
    var lastRender = 0L
    collect { state ->
        val old = previous
        val now = SystemClock.uptimeMillis()
        val controlsChanged = old == null || old.phase != state.phase || old.backend != state.backend ||
            old.scenarioId != state.scenarioId || old.scenarioName != state.scenarioName ||
            old.routeId != state.routeId || old.error != state.error || old.capabilities != state.capabilities ||
            old.configuredSpeedMps != state.configuredSpeedMps
        if (controlsChanged || now - lastRender >= 200L) {
            previous = state
            lastRender = now
            emit(state)
        }
    }
}

fun TextView.setTextIfChanged(value: CharSequence) {
    if (!android.text.TextUtils.equals(text, value)) text = value
}

fun RuntimePhase.displayLabel(): String = when (this) {
    RuntimePhase.IDLE -> "尚未开始"
    RuntimePhase.PREPARING -> "准备中"
    RuntimePhase.READY -> "准备就绪"
    RuntimePhase.RUNNING -> "正在模拟"
    RuntimePhase.PAUSED -> "已暂停"
    RuntimePhase.STOPPING -> "正在停止"
    RuntimePhase.STOPPED -> "已停止"
    RuntimePhase.ERROR -> "需要处理"
}

fun BackendType.displayLabel(): String = when (this) {
    BackendType.MOCK_PROVIDER -> "无 Root"
    BackendType.XPOSED -> "Xposed"
    BackendType.NATIVE -> "Native + Xposed"
}
