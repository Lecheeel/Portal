package com.system.location.service.ui.mock

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.system.location.service.core.backend.BackendType
import com.system.location.service.core.gnss.GnssSimulationMode
import com.system.location.service.designsystem.components.PortalActionRow
import com.system.location.service.designsystem.components.PortalCard
import com.system.location.service.designsystem.components.PortalSectionTitle
import com.system.location.service.designsystem.theme.PortalSpacing
import com.system.location.service.ext.environmentSourceMode
import com.system.location.service.ext.mockWifiEnabled
import com.system.location.service.ext.sharedPrefs
import com.system.location.service.gnss.gnssReplayOnStart
import com.system.location.service.gnss.replayEnvironmentName
import com.system.location.service.runtime.ScenarioRuntime
import com.system.location.service.ui.preferenceRevision
import com.system.location.service.ui.viewmodel.QuickReplayViewModel
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch

/** Selection immediately saves and applies; all entry points share the activity-owned command. */
@Composable
internal fun QuickReplayCard(
    model: QuickReplayViewModel,
    environment: Boolean = true,
    satellite: Boolean = true,
    onManage: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val revision = preferenceRevision()
    val state by model.state.collectAsStateWithLifecycle()
    val runtime by ScenarioRuntime.state.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(model, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            model.refresh()
            awaitCancellation()
        }
    }
    val environmentLabel = remember(revision) {
        when {
            context.environmentSourceMode == "REPLAY" -> context.replayEnvironmentName.ifEmpty { "已选环境" }
            context.environmentSourceMode == "PROCEDURAL" -> "程序生成"
            else -> "自动"
        }
    }
    val satelliteLabel = remember(revision) {
        if (!context.gnssReplayOnStart) "关闭"
        else when (context.sharedPrefs.getString("gnssSimulationMode", "AUTONOMOUS")) {
            "REPLAY" -> "最近采集"
            "AUTO" -> "自动"
            "OFF" -> "关闭"
            else -> "目标天空"
        }
    }
    var picker by rememberSaveable { mutableStateOf<String?>(null) }
    PortalCard {
        Column(verticalArrangement = Arrangement.spacedBy(PortalSpacing.sm)) {
            PortalSectionTitle("快捷回放")
            if (state.busy || state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (environment) Box {
                PortalActionRow("回放环境", environmentLabel, { picker = "environment" }, enabled = !state.busy)
                DropdownMenu(expanded = picker == "environment", onDismissRequest = { picker = null }) {
                    DropdownMenuItem(text = { Text("关闭环境模拟") }, onClick = { picker = null; model.disableEnvironment() })
                    DropdownMenuItem(text = { Text("程序生成") }, onClick = { picker = null; model.environment("") })
                    state.names.forEach { name ->
                        DropdownMenuItem(text = { Text(name) }, onClick = { picker = null; model.environment(name) })
                    }
                    if (state.names.isEmpty() && !state.loading) DropdownMenuItem(text = { Text("暂无已保存环境") }, onClick = {}, enabled = false)
                    DropdownMenuItem(text = { Text("刷新列表") }, onClick = { picker = null; scope.launch { model.refresh() } })
                }
            }
            if (satellite) Box {
                PortalActionRow("回放卫星", satelliteLabel, { picker = "satellite" }, enabled = !state.busy)
                DropdownMenu(expanded = picker == "satellite", onDismissRequest = { picker = null }) {
                    DropdownMenuItem(text = { Text("关闭卫星模拟") }, onClick = { picker = null; model.satellite(null) })
                    DropdownMenuItem(text = { Text("目标天空（无需采集）") }, onClick = { picker = null; model.satellite(GnssSimulationMode.AUTONOMOUS) })
                    DropdownMenuItem(text = { Text("最近采集") }, onClick = { picker = null; model.satellite(GnssSimulationMode.REPLAY) })
                }
            }
            Text(
                if (runtime.backend == BackendType.MOCK_PROVIDER) "选择会保存；环境和卫星回放需要 Xposed / Native。"
                else if (runtime.isActive) "选择后立即应用。" else "选择后随定位启动。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (environment && context.environmentSourceMode == "REPLAY" && context.mockWifiEnabled) {
                Text("环境仅在录入或采集地点附近回放。", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (satellite && satelliteLabel == "最近采集") Text("卫星回放使用采集地点与时段的数据。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (runtime.isActive && runtime.backend != BackendType.MOCK_PROVIDER) Button(
                onClick = model::replay, enabled = !state.busy, modifier = Modifier.fillMaxWidth(),
            ) { Text("立即回放") }
            onManage?.let { PortalActionRow("管理环境 / 卫星", onClick = it) }
            state.message?.let { Text(it, style = MaterialTheme.typography.bodySmall,
                color = if (state.failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}
