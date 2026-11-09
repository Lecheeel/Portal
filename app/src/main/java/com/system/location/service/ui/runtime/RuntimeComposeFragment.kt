package com.system.location.service.ui.runtime

import android.content.Intent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.Navigation
import com.system.location.service.R
import com.system.location.service.core.backend.BackendType
import com.system.location.service.core.backend.Availability
import com.system.location.service.core.backend.Capability
import com.system.location.service.core.runtime.DiagnosticEvent
import com.system.location.service.core.runtime.RuntimePhase
import com.system.location.service.core.runtime.RuntimeState
import com.system.location.service.designsystem.components.PortalCard
import com.system.location.service.designsystem.components.PortalSectionTitle
import com.system.location.service.designsystem.theme.PortalTheme
import com.system.location.service.designsystem.theme.PortalSpacing
import com.system.location.service.runtime.ScenarioRuntime
import com.system.location.service.data.repository.LibraryRepositories
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
    val lifecycleOwner = LocalLifecycleOwner.current
    var environment by remember { mutableStateOf<RuntimeEnvironment?>(null) }
    var environmentCheckFailed by remember { mutableStateOf(false) }
    var refreshGeneration by remember { mutableStateOf(0) }
    var nativeRiskVisible by rememberSaveable { mutableStateOf(false) }
    var nativeConfirmed by rememberSaveable { mutableStateOf(false) }
    var selectedBackend by rememberSaveable { mutableStateOf(state.backend) }
    var observedBackend by rememberSaveable { mutableStateOf(state.backend) }
    var logsExpanded by rememberSaveable { mutableStateOf(false) }
    var detailsExpanded by rememberSaveable { mutableStateOf(false) }
    var developerExpanded by rememberSaveable { mutableStateOf(false) }
    var capabilitiesExpanded by rememberSaveable { mutableStateOf(false) }
    var clearedAt by rememberSaveable { mutableStateOf(0L) }
    var logFilter by rememberSaveable { mutableStateOf(0) }
    var logLimit by rememberSaveable { mutableStateOf(20) }
    var rebuilding by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val visibleEvents = remember(events, clearedAt, logFilter, state.backend) {
        visibleRuntimeEvents(events, clearedAt, logFilter, state.backend)
    }
    val formatter = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
    val migrationIssues = remember { LibraryRepositories.migrationIssues() }

    fun dismissNativeRisk() {
        nativeRiskVisible = false
        nativeConfirmed = false
        selectedBackend = ScenarioRuntime.state.value.backend
    }

    fun requestApply(target: BackendType) {
        if (target == BackendType.NATIVE) {
            context.getSharedPreferences("scenario_runtime", android.content.Context.MODE_PRIVATE)
                .edit().putBoolean("native_opt_in", true).apply()
        }
        scope.launch {
            val selected = ScenarioRuntime.selectBackend(target).await()
            if (selected) {
                selectedBackend = ScenarioRuntime.state.value.backend
                Toast.makeText(context, "已切换模式", Toast.LENGTH_LONG).show()
            } else {
                selectedBackend = ScenarioRuntime.state.value.backend
                Toast.makeText(context, "请先停止场景并完成资源清理", Toast.LENGTH_LONG).show()
            }
        }
    }

    LaunchedEffect(lifecycleOwner, refreshGeneration) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            val result = withContext(Dispatchers.IO) {
                runCatching { RuntimeEnvironment.inspect(context.applicationContext) }
            }
            environment = result.getOrNull()
            environmentCheckFailed = result.isFailure
            ScenarioRuntime.refreshDiagnostics()
        }
    }

    LaunchedEffect(state.backend, state.isActive) {
        // Keep a pending choice on recreation, but follow real backend changes and active sessions.
        if (observedBackend != state.backend || state.isActive) {
            selectedBackend = state.backend
            nativeRiskVisible = false
            nativeConfirmed = false
        }
        observedBackend = state.backend
    }

    if (nativeRiskVisible) {
        AlertDialog(
            onDismissRequest = ::dismissNativeRisk,
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
                TextButton(onClick = ::dismissNativeRisk) { Text("取消") }
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
                    RuntimeSectionToggle("运行详情", detailsExpanded, { detailsExpanded = !detailsExpanded })
                    if (detailsExpanded) {
                        SelectionContainer {
                            Text(
                                runtimeDetails(state),
                                modifier = Modifier.testTag("runtime-state-details"),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }

            item {
                PortalCard {
                    PortalSectionTitle("设备与授权")
                    Spacer(Modifier.height(PortalSpacing.md))
                    val env = environment
                    if (env == null) {
                        Text(
                            if (environmentCheckFailed) "设备状态检查失败，请刷新重试" else "正在检查设备状态…",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        EnvironmentRow("Root 环境", if (env.rootPresent) "检测到 su" else "未检测到 su", env.rootPresent)
                        EnvironmentRow("Xposed 模块", if (env.moduleConnected) "已连接系统模块" else "未连接", env.moduleConnected)
                        EnvironmentRow("模拟位置应用", if (env.mockAllowed) "已设置" else "未设置", env.mockAllowed)
                        EnvironmentRow("精确位置权限", if (env.preciseLocation) "已允许" else "未允许", env.preciseLocation)
                        EnvironmentRow("系统定位", if (env.locationEnabled) "已开启" else "已关闭", env.locationEnabled)
                    }
                    val issues = buildList {
                        state.error?.let {
                            add(listOf(it.reason, it.suggestion).filter(String::isNotBlank).joinToString("\n"))
                        }
                        env?.issues(state.backend)?.forEach { add(context.getString(it)) }
                    }.distinct()
                    if (issues.isNotEmpty()) {
                        Spacer(Modifier.height(PortalSpacing.sm))
                        Text(issues.joinToString("\n"), style = MaterialTheme.typography.bodySmall)
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
                                onClick = {
                                    selectedBackend = backend
                                    if (backend != BackendType.NATIVE) nativeConfirmed = false
                                },
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
                    RuntimeSectionToggle("开发者工具", developerExpanded, { developerExpanded = !developerExpanded })
                    if (developerExpanded) {
                        OutlinedButton(onClick = onOpenEnvironment, modifier = Modifier.fillMaxWidth()) {
                            Text("环境工具")
                        }
                        RuntimeSectionToggle("能力与诊断", capabilitiesExpanded, { capabilitiesExpanded = !capabilitiesExpanded })
                        if (capabilitiesExpanded) {
                            OutlinedButton(
                                onClick = {
                                    rebuilding = true
                                    scope.launch {
                                        try {
                                            val rebuilt = ScenarioRuntime.rebuildMockProviders().await()
                                            Toast.makeText(
                                                context,
                                                if (rebuilt) "通道已重建，场景进度已保留" else "重建失败，请查看运行日志并检查授权",
                                                Toast.LENGTH_LONG,
                                            ).show()
                                        } finally {
                                            rebuilding = false
                                        }
                                    }
                                },
                                enabled = !rebuilding && state.isActive && state.backend == BackendType.MOCK_PROVIDER,
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text("重建标准定位通道") }
                            OutlinedButton(onClick = onOpenProbe, modifier = Modifier.fillMaxWidth()) {
                                Text("独立进程接收诊断")
                            }
                            OutlinedButton(
                                onClick = { refreshGeneration++ },
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text("刷新后端与 Hook 诊断") }
                            SelectionContainer {
                                Text(
                                    state.capabilities.entries.joinToString("\n\n") { (capability, status) ->
                                        "${capabilityLabel(capability)}：${when (status.availability) {
                                            Availability.AVAILABLE -> "可用"
                                            Availability.REQUIRES_ACTION -> "需要设置"
                                            Availability.UNAVAILABLE -> "不可用"
                                            Availability.EXPERIMENTAL -> "实验性"
                                        }}\n${status.reason}"
                                    }.ifBlank { "暂无能力诊断，请刷新后查看" },
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                }
            }

            item {
                PortalCard {
                    RuntimeSectionToggle("运行日志", logsExpanded, { logsExpanded = !logsExpanded })
                    Text("${visibleEvents.size} 条", style = MaterialTheme.typography.bodySmall)
                }
            }
            if (logsExpanded) {
                item {
                    PortalCard {
                        OutlinedButton(onClick = onExport, modifier = Modifier.fillMaxWidth()) {
                            Text("导出完整诊断日志")
                        }
                        Text("日志筛选", style = MaterialTheme.typography.titleSmall)
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            listOf("全部日志", "异常与待处理", "当前后端").forEachIndexed { index, label ->
                                FilterChip(
                                    selected = logFilter == index,
                                    onClick = { logFilter = index; logLimit = 20 },
                                    label = { Text(label) },
                                )
                            }
                        }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = {
                                val text = buildString {
                                    if (migrationIssues.isNotEmpty()) appendLine("资料迁移提示：${migrationIssues.joinToString("; ")}")
                                    append(visibleEvents.take(logLimit).joinToString("\n\n") { formatEvent(it, formatter) }
                                        .ifBlank { "暂无符合条件的日志" })
                                }
                                (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                                    .setPrimaryClip(ClipData.newPlainText("运行日志", text))
                                Toast.makeText(context, "已复制当前显示的日志", Toast.LENGTH_SHORT).show()
                            }) { Text("复制日志") }
                            TextButton(onClick = {
                                // Clear only this view; retain engine history and the full diagnostic report.
                                clearedAt = System.currentTimeMillis()
                                logLimit = 20
                            }) { Text("清空日志") }
                        }
                        Text("清空仅影响当前视图，完整诊断日志仍保留。", style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (migrationIssues.isNotEmpty()) {
                    item { Text("资料迁移提示：${migrationIssues.joinToString("; ")}", style = MaterialTheme.typography.bodySmall) }
                }
                if (visibleEvents.isEmpty()) {
                    item { Text("暂无符合条件的日志", style = MaterialTheme.typography.bodyMedium) }
                }
                items(visibleEvents.take(logLimit)) { event ->
                    SelectionContainer {
                        Column {
                            HorizontalDivider()
                            Text(formatEvent(event, formatter), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                if (visibleEvents.size > logLimit) {
                    item {
                        TextButton(onClick = { logLimit += 20 }, modifier = Modifier.fillMaxWidth()) { Text("显示更多") }
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
private fun RuntimeSectionToggle(title: String, expanded: Boolean, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().semantics {
            stateDescription = if (expanded) "已展开" else "已收起"
        },
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(if (expanded) "收起" else "展开")
        }
    }
}

private fun runtimeDetails(state: RuntimeState): String = buildString {
    val health = state.submissionHealth
    appendLine("提交 ${health.count} 次 · 最近间隔 ${health.lastGapMs.toLong()}ms · 最大间隔 ${health.maxGapMs.toLong()}ms")
    appendLine("最近耗时 ${health.lastLatencyMs.toLong()}ms · 最大耗时 ${health.maxLatencyMs.toLong()}ms · 延迟 ${health.delayedCount} 次")
    appendLine("提交成功不代表目标 App 已采用定位")
    appendLine("当前后端：${state.backend.displayLabel()}\n状态：${state.phase.displayLabel()}")
    appendLine("场景：${state.scenarioName ?: "未选择"}\n路线：${state.routeId ?: "—"}")
    state.sample?.let {
        appendLine(String.format(Locale.ROOT, "WGS84：%.6f, %.6f", it.latitude, it.longitude))
        appendLine(String.format(Locale.ROOT, "速度：%.2f km/h　进度：%.1f%%", it.speed * 3.6, state.progress * 100))
    }
    state.error?.let { appendLine("${it.stage}\n${it.reason}\n${it.suggestion}") }
}

internal fun visibleRuntimeEvents(
    events: List<DiagnosticEvent>,
    clearedAt: Long,
    filter: Int,
    backend: BackendType,
): List<DiagnosticEvent> = events.filter { event ->
    event.timestamp > clearedAt && when (filter) {
        1 -> event.result in setOf("FAILED", "ERROR", "REQUIRES_ACTION", "UNAVAILABLE")
        2 -> event.backend == backend
        else -> true
    }
}.asReversed()

private fun formatEvent(event: DiagnosticEvent, formatter: SimpleDateFormat): String =
    "${formatter.format(Date(event.timestamp))} · ${event.backend.displayLabel()} · ${event.result}\n${event.stage} · ${event.reason}" +
        if (event.suggestion.isBlank()) "" else "\n建议：${event.suggestion}"

private fun capabilityLabel(capability: Capability): String = when (capability) {
    Capability.STANDARD_MOCK -> "标准模拟位置"
    Capability.XPOSED -> "系统 Hook"
    Capability.NATIVE -> "实验性 Native"
    Capability.GMS_FUSED -> "Google 融合定位"
    Capability.BACKGROUND_PLAYBACK -> "后台播放"
    Capability.PER_APP_SCENARIO -> "按应用分配场景"
    Capability.GNSS_INJECTION -> "GNSS 注入"
    Capability.SENSOR_SIMULATION -> "传感器模拟"
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
            "${state.backend.displayLabel()} · ${state.scenarioName ?: "未选择场景"}",
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

