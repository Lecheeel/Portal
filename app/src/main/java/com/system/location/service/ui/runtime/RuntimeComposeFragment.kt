package com.system.location.service.ui.runtime

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.dp
import androidx.fragment.app.Fragment
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.navigation.Navigation
import com.system.location.service.R
import com.system.location.service.core.backend.BackendType
import com.system.location.service.core.runtime.RuntimePhase
import com.system.location.service.core.runtime.RuntimeState
import com.system.location.service.designsystem.components.PortalCard
import com.system.location.service.designsystem.components.PortalSectionTitle
import com.system.location.service.designsystem.theme.PortalTheme
import com.system.location.service.designsystem.theme.PortalSpacing
import com.system.location.service.runtime.ScenarioRuntime
import com.system.location.service.ui.displayLabel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * First Compose page of the UI refactor.
 *
 * It intentionally reuses the existing ScenarioRuntime/RuntimeEnvironment APIs and stays inside
 * the Fragment navigation graph for now. This keeps the migration reversible while the design
 * system and Compose shell mature.
 */
class RuntimeComposeFragment : Fragment() {
    private val exportLog = registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain"),
    ) { uri ->
        val appContext = context?.applicationContext
        if (uri != null && appContext != null) {
            lifecycleScope.launch {
                runCatching {
                    val report = ScenarioRuntime.diagnosticReport()
                    withContext(Dispatchers.IO) {
                        appContext.contentResolver.openOutputStream(uri, "wt")
                            ?.bufferedWriter()
                            ?.use { it.write(report) }
                            ?: error("无法写入日志")
                    }
                    Toast.makeText(appContext, "诊断日志已导出", Toast.LENGTH_SHORT).show()
                }.onFailure {
                    Toast.makeText(appContext, it.message ?: "日志导出失败", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = ComposeView(requireContext()).apply {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        setContent {
            PortalTheme {
                RuntimeScreen(
                    onExport = { exportLog.launch("location-diagnostics.txt") },
                    onOpenEnvironment = {
                        Navigation.findNavController(this).navigate(R.id.nav_environment)
                    },
                    onOpenProbe = {
                        startActivity(
                            Intent(requireContext(), com.system.location.service.ui.diagnostics.LocationProbeActivity::class.java),
                        )
                    },
                    onOpenDeveloperSettings = {
                        runCatching {
                            startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
                        }.onFailure {
                            Toast.makeText(
                                requireContext(),
                                "请手动打开系统设置中的开发者选项",
                                Toast.LENGTH_LONG,
                            ).show()
                        }
                    },
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RuntimeScreen(
    onExport: () -> Unit,
    onOpenEnvironment: () -> Unit,
    onOpenProbe: () -> Unit,
    onOpenDeveloperSettings: () -> Unit,
) {
    val state by ScenarioRuntime.state.collectAsStateWithLifecycle()
    val events by ScenarioRuntime.diagnostics.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var environment by remember { mutableStateOf<RuntimeEnvironment?>(null) }
    var nativeRiskVisible by remember { mutableStateOf(false) }
    var nativeConfirmed by remember { mutableStateOf(false) }
    var selectedBackend by remember { mutableStateOf(state.backend) }
    var logsExpanded by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun requestApply(target: BackendType) {
        if (target == BackendType.NATIVE) {
            context.getSharedPreferences("scenario_runtime", android.content.Context.MODE_PRIVATE)
                .edit().putBoolean("native_opt_in", true).apply()
        }
        scope.launch {
            val selected = ScenarioRuntime.selectBackend(target).await()
            if (selected) {
                selectedBackend = target
                Toast.makeText(context, "已切换模式", Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(context, "请先停止场景并完成资源清理", Toast.LENGTH_LONG).show()
            }
        }
    }

    LaunchedEffect(Unit) {
        environment = withContext(Dispatchers.IO) {
            runCatching { RuntimeEnvironment.inspect(context.applicationContext) }.getOrNull()
        }
        ScenarioRuntime.refreshDiagnostics()
    }

    LaunchedEffect(state.backend) {
        if (!state.isActive) selectedBackend = state.backend
    }

    if (nativeRiskVisible) {
        AlertDialog(
            onDismissRequest = { nativeRiskVisible = false },
            title = { Text("实验性模式风险须知") },
            text = {
                Text(
                    "Native + Xposed 会在系统进程中加载扩展，修改定位、GNSS/NMEA 与传感器回调。\n\n" +
                        "停止模拟不会卸载已加载的 Native Hook；更新或卸载需要重启相关进程或设备。\n\n" +
                        "不同 ROM 兼容性有限，请确认已知晓恢复方式。",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    nativeConfirmed = true
                    nativeRiskVisible = false
                    requestApply(BackendType.NATIVE)
                }) { Text("已知悉并选择") }
            },
            dismissButton = {
                TextButton(onClick = { nativeRiskVisible = false }) { Text("取消") }
            },
        )
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                Column {
                    Text(
                        "查看模拟状态、控制播放和排查问题。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            item {
                RuntimeStatusCard(
                    state = state,
                    onPauseResume = {
                        if (state.phase == RuntimePhase.PAUSED) ScenarioRuntime.resume()
                        else ScenarioRuntime.pause()
                    },
                    onStop = { ScenarioRuntime.stop() },
                    onOpenDeveloperSettings = onOpenDeveloperSettings,
                )
            }

            item {
                PortalCard {
                    PortalSectionTitle("设备与授权")
                    Spacer(Modifier.height(PortalSpacing.md))
                    val env = environment
                    if (env == null) {
                        Text("正在检查设备状态…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        EnvironmentRow("Root 环境", if (env.rootPresent) "检测到 su" else "未检测到 su", env.rootPresent)
                        EnvironmentRow("Xposed 模块", if (env.moduleConnected) "已连接系统模块" else "未连接", env.moduleConnected)
                        EnvironmentRow("模拟位置应用", if (env.mockAllowed) "已设置" else "未设置", env.mockAllowed)
                        EnvironmentRow("精确位置权限", if (env.preciseLocation) "已允许" else "未允许", env.preciseLocation)
                        EnvironmentRow("系统定位", if (env.locationEnabled) "已开启" else "已关闭", env.locationEnabled)
                    }
                }
            }

            item {
                PortalCard {
                    PortalSectionTitle("运行模式", "切换前请先停止模拟")
                    Spacer(Modifier.height(PortalSpacing.md))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        BackendType.entries.forEach { backend ->
                            FilterChip(
                                selected = selectedBackend == backend,
                                enabled = !state.isActive,
                                onClick = { selectedBackend = backend },
                                label = { Text(backend.displayLabel()) },
                            )
                        }
                    }
                    Spacer(Modifier.height(PortalSpacing.sm))
                    Button(
                        onClick = {
                            val target = selectedBackend
                            if (target == BackendType.NATIVE &&
                                state.backend != BackendType.NATIVE &&
                                !nativeConfirmed
                            ) {
                                nativeRiskVisible = true
                            } else {
                                requestApply(selectedBackend)
                            }
                        },
                        enabled = !state.isActive,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("应用模式") }
                    if (selectedBackend == BackendType.NATIVE) {
                        Spacer(Modifier.height(PortalSpacing.xs))
                        Text(
                            "Native 是实验能力，需要 Root、arm64 与已激活的 Xposed。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            item {
                PortalCard {
                    PortalSectionTitle("开发者工具")
                    Spacer(Modifier.height(PortalSpacing.md))
                    OutlinedButton(onClick = onOpenEnvironment, modifier = Modifier.fillMaxWidth()) {
                        Text("环境工具")
                    }
                    Spacer(Modifier.height(PortalSpacing.sm))
                    OutlinedButton(onClick = onOpenProbe, modifier = Modifier.fillMaxWidth()) {
                        Text("独立进程接收诊断")
                    }
                    Spacer(Modifier.height(PortalSpacing.sm))
                    OutlinedButton(
                        onClick = { ScenarioRuntime.refreshDiagnostics() },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("刷新后端与 Hook 诊断") }
                }
            }

            item {
                PortalCard {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        PortalSectionTitle("运行日志", "最近 ${events.size} 条")
                        TextButton(onClick = { logsExpanded = !logsExpanded }) {
                            Text(if (logsExpanded) "收起" else "展开")
                        }
                    }
                    if (logsExpanded) {
                        Spacer(Modifier.height(PortalSpacing.sm))
                        val formatter = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
                        events.takeLast(8).reversed().forEach { event ->
                            HorizontalDivider()
                            Text(
                                "${formatter.format(Date(event.timestamp))} · ${backendLabel(event.backend)} · ${event.result}",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                "${event.stage} · ${event.reason}",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Spacer(Modifier.height(PortalSpacing.sm))
                        OutlinedButton(onClick = onExport, modifier = Modifier.fillMaxWidth()) {
                            Text("导出完整诊断日志")
                        }
                    }
                }
            }

            item {
                Text(
                    "提交成功不代表目标 App 已采用定位。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun RuntimeStatusCard(
    state: RuntimeState,
    onPauseResume: () -> Unit,
    onStop: () -> Unit,
    onOpenDeveloperSettings: () -> Unit,
) {
    val statusColor = when (state.phase) {
        RuntimePhase.RUNNING -> MaterialTheme.colorScheme.primary
        RuntimePhase.ERROR -> MaterialTheme.colorScheme.error
        RuntimePhase.PAUSED -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    PortalCard {
        Text(
            state.phase.displayLabel(),
            style = MaterialTheme.typography.headlineMedium,
            color = statusColor,
        )
        Spacer(Modifier.height(PortalSpacing.xs))
        Text(
            "${backendLabel(state.backend)} · ${state.scenarioName ?: "未选择场景"}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (state.routeId != null) {
            Spacer(Modifier.height(PortalSpacing.md))
            LinearProgressIndicator(
                progress = { state.progress.toFloat().coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        state.error?.let { error ->
            Spacer(Modifier.height(PortalSpacing.md))
            Text(error.stage, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.error)
            Text(error.reason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(PortalSpacing.md))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = onPauseResume,
                enabled = state.phase in setOf(RuntimePhase.RUNNING, RuntimePhase.PAUSED),
                modifier = Modifier.weight(1f),
            ) {
                Text(if (state.phase == RuntimePhase.PAUSED) "继续" else "暂停")
            }
            OutlinedButton(
                onClick = onStop,
                enabled = state.isActive || state.phase == RuntimePhase.ERROR,
                modifier = Modifier.weight(1f),
            ) { Text("停止") }
        }
        TextButton(onClick = onOpenDeveloperSettings) { Text("打开开发者选项") }
    }
}

@Composable
private fun EnvironmentRow(title: String, value: String, ok: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
        )
    }
}

private fun backendLabel(type: BackendType) = when (type) {
    BackendType.MOCK_PROVIDER -> "标准模拟位置"
    BackendType.XPOSED -> "Xposed"
    BackendType.NATIVE -> "Native + Xposed"
}
