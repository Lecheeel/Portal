package com.system.location.service.ui.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.system.location.service.R
import com.system.location.service.core.backend.BackendType
import com.system.location.service.core.runtime.RuntimePhase
import com.system.location.service.core.runtime.RuntimeState
import com.system.location.service.ui.displayLabel

/** Position samples belong to the map renderer and do not invalidate the controls. */
internal data class HomeRuntimeUi(val phase: RuntimePhase, val backend: BackendType, val name: String?) {
    val isActive get() = phase in setOf(RuntimePhase.PREPARING, RuntimePhase.READY,
        RuntimePhase.RUNNING, RuntimePhase.PAUSED, RuntimePhase.STOPPING)
    companion object {
        fun from(state: RuntimeState) = HomeRuntimeUi(state.phase, state.backend, state.scenarioName)
    }
}

@Composable
internal fun HomeRuntimeControls(
    runtime: HomeRuntimeUi,
    canStart: Boolean,
    onStart: () -> Unit,
    onPauseResume: () -> Unit,
    onStop: () -> Unit,
) {
    Text(listOfNotNull(runtime.phase.displayLabel(), runtime.backend.displayLabel(),
        runtime.name?.takeIf { runtime.isActive }).joinToString(" · "),
        style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = onStart,
            enabled = canStart && runtime.phase !in setOf(RuntimePhase.PREPARING, RuntimePhase.STOPPING),
            modifier = Modifier.weight(1f)) {
            Text(if (runtime.isActive) "切换位置" else "开始模拟")
        }
        AnimatedVisibility(runtime.isActive, enter = fadeIn(), exit = fadeOut()) {
            FilledTonalIconButton(onClick = onPauseResume,
                enabled = runtime.phase in setOf(RuntimePhase.RUNNING, RuntimePhase.PAUSED),
                modifier = Modifier.size(48.dp)) {
                val paused = runtime.phase == RuntimePhase.PAUSED
                Icon(painterResource(if (paused) R.drawable.ic_portal_run else R.drawable.ic_portal_pause),
                    contentDescription = if (paused) "继续模拟" else "暂停模拟")
            }
        }
        OutlinedButton(onClick = onStop, enabled = runtime.isActive && runtime.phase != RuntimePhase.STOPPING,
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
            modifier = Modifier.weight(1f)) { Text("停止模拟") }
    }
}
