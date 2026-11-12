package com.system.location.service.ui.settings

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.remember
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.dp
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import androidx.fragment.app.viewModels
import com.system.location.service.ui.gnss.GnssCaptureStatus
import com.system.location.service.ui.gnss.GnssPolicyControls
import com.system.location.service.ui.viewmodel.GnssCaptureViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.system.location.service.MainActivity
import com.system.location.service.R
import com.system.location.service.core.backend.BackendType
import com.system.location.service.core.routing.withPointAssignments
import com.system.location.service.core.routing.withDeniedPackages
import com.system.location.service.core.routing.RoutingConfig
import com.system.location.service.designsystem.components.PortalActionRow
import com.system.location.service.designsystem.components.PortalSettingGroup
import com.system.location.service.designsystem.components.PortalSwitchRow
import com.system.location.service.designsystem.theme.PortalTheme
import com.system.location.service.ext.accuracy
import com.system.location.service.ext.altitude
import com.system.location.service.ext.antiDetectEnabled
import com.system.location.service.ext.debug
import com.system.location.service.ext.disableFusedProvider
import com.system.location.service.ext.hideMock
import com.system.location.service.ext.stackTraceCleanEnabled
import com.system.location.service.ext.disableGetCurrentLocation
import com.system.location.service.ext.disableRegisterLocationListener
import com.system.location.service.ext.mockWifiEnabled
import com.system.location.service.ext.experimentalClearMockFlag
import com.system.location.service.ext.gmsMockEnabled
import com.system.location.service.ext.loopBroadcastlocation
import com.system.location.service.ext.minSatelliteCount
import com.system.location.service.ext.needDowngradeToCdma
import com.system.location.service.ext.physicalNoise
import com.system.location.service.ext.platformFusedEnabled
import com.system.location.service.ext.reportDuration
import com.system.location.service.ext.routingJson
import com.system.location.service.ext.simReady
import com.system.location.service.ext.smoothRouteMotion
import com.system.location.service.ext.speed
import com.system.location.service.ext.sensorTargetPackages
import com.system.location.service.ext.startupBurstEnabled
import com.system.location.service.maps.mapProvider
import com.system.location.service.privacy.SdkPrivacy
import com.system.location.service.runtime.ScenarioRuntime
import com.system.location.service.ui.preferenceRevision
import com.system.location.service.update.UpdateChannel
import com.system.location.service.update.UpdateChecker
import com.system.location.service.update.UpdateDialogFragment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/** Settings presentation; preference writes continue through the existing configuration API. */
class SettingsComposeFragment : Fragment() {
    private val captureModel by viewModels<GnssCaptureViewModel>()
    private val revision = MutableStateFlow(0)
    private var updateRequest = 0L
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        parentFragmentManager.setFragmentResultListener(AppSelectionFragment.RESULT, this) { _, result ->
            val selected = result.getStringArrayList("selected")?.toSet() ?: return@setFragmentResultListener
            val context = requireContext()
            when (result.getString("purpose")) {
                "sensors" -> {
                    if (selected.size > 128) { showToast("最多选择 128 个应用"); return@setFragmentResultListener }
                    context.sensorTargetPackages = selected
                }
                "excluded", "point" -> {
                    val existing = runCatching {
                        readRoutingSettings(context.routingJson)
                    }.getOrElse { showToast("原有应用规则无法读取，未覆盖配置"); return@setFragmentResultListener }
                    val next = if (result.getString("purpose") == "excluded") existing.withDeniedPackages(selected)
                    else existing.withPointAssignments(
                        com.system.location.service.core.geo.Wgs84(result.getDouble("latitude"), result.getDouble("longitude")), selected)
                    context.routingJson = Json.encodeToString(RoutingConfig.serializer(), next)
                }
                else -> return@setFragmentResultListener
            }
            refresh()
            applyConfigNowWithToast()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = ComposeView(requireContext()).apply {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        setContent {
            val revisionState = revision.collectAsStateWithLifecycle()
            var numberSetting by rememberSaveable { mutableStateOf<NumberSetting?>(null) }
            PortalTheme {
                SettingsScreen(
                    category = arguments?.getString("category")?.let(SettingsCategory::valueOf),
                    onCategory = { findNavController().navigate(it.destination) },
                    captureModel = captureModel,
                    revision = revisionState.value,
                    onMapProvider = ::showMapProvider,
                    onEditAltitude = { numberSetting = NumberSetting.ALTITUDE },
                    onEditAccuracy = { numberSetting = NumberSetting.ACCURACY },
                    onEditReportInterval = { numberSetting = NumberSetting.INTERVAL },
                    onEditSpeed = { numberSetting = NumberSetting.SPEED },
                    onEditSatelliteCount = { numberSetting = NumberSetting.SATELLITES },
                    onAppRouting = ::showAppRouting,
                    onSensorTargets = {
                        val context = requireContext()
                        openAppSelection("sensors", "传感器目标应用", context.sensorTargetPackages)
                    },
                    onEditSensorPackages = {
                        findNavController().navigate(R.id.nav_sensor_packages, Bundle().apply {
                            putStringArrayList("selected", ArrayList(requireContext().sensorTargetPackages.sorted()))
                        })
                    },
                    onAppPoint = ::showAppPoint,
                    onEnvironment = { findNavController().navigate(R.id.nav_environment) },
                    onBackgroundGuidance = { com.system.location.service.runtime.BackgroundGuidance.show(requireContext()) },
                    onCrashReporting = { enabled -> setCrashReporting(enabled) },
                    onUpdateChannel = ::showUpdateChannel,
                    onCheckUpdate = ::checkForUpdate,
                    onConfigChanged = ::applyConfigNowWithToast,
                    onRestartRequired = { showToast("已保存，停止后重新启动场景生效") },
                    refresh = ::refresh,
                )
                numberSetting?.let { setting ->
                    val initial = when (setting) {
                        NumberSetting.ALTITUDE -> context.altitude.toString()
                        NumberSetting.ACCURACY -> context.accuracy.toString()
                        NumberSetting.INTERVAL -> context.reportDuration.toString()
                        NumberSetting.SPEED -> context.speed.toString()
                        NumberSetting.SATELLITES -> context.minSatelliteCount.toString()
                    }
                    NumberSettingDialog(setting, initial, onDismiss = { numberSetting = null },
                        onSave = { raw -> saveNumber(setting, raw) })
                }
            }
        }
    }

    private fun refresh() {
        revision.value += 1
    }

    private fun showToast(message: String) {
        if (!isAdded) return
        Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
    }

    private fun saveNumber(setting: NumberSetting, raw: String): String? = runCatching {
        val context = requireContext()
        when (setting) {
            NumberSetting.ALTITUDE -> {
                val value = raw.toDoubleOrNull() ?: error("海拔高度不合法")
                require(value in 0.0..10_000.0) { "海拔需在 0–10000 米之间" }
                context.altitude = value
                refresh()
            }
            NumberSetting.ACCURACY -> {
                val value = raw.toFloatOrNull() ?: error("精度不合法")
                require(value > 0f && value <= 1_000f) { "精度需大于 0 且不超过 1000 米" }
                context.accuracy = value
                refresh()
            }
            NumberSetting.INTERVAL -> {
                val value = raw.toIntOrNull() ?: error("上报间隔不合法")
                require(value in 50..1_000) { "上报间隔需在 50–1000 ms 之间" }
                context.reportDuration = value
                refresh()
            }
            NumberSetting.SPEED -> {
                val value = raw.toDoubleOrNull() ?: error("速度不合法")
                require(value in 0.0..1_000.0) { "速度需在 0–1000 米/秒之间" }
                lifecycleScope.launch {
                    val accepted = ScenarioRuntime.setSpeed(value).await()
                    showToast(if (accepted) "速度已更新" else "速度更新失败，请重试")
                }
            }
            NumberSetting.SATELLITES -> {
                val value = raw.toIntOrNull() ?: error("数量不合法")
                require(value in 0..128) { "卫星数量需在 0–128 之间；0 表示自动生成" }
                context.minSatelliteCount = value
                refresh()
                applyConfigNowWithToast()
            }
        }
    }.exceptionOrNull()?.let { it.message ?: "输入不合法" }

    private fun showMapProvider() {
        val context = requireContext()
        val selected = context.mapProvider
        MaterialAlertDialogBuilder(context)
            .setTitle("地图服务")
            .setSingleChoiceItems(com.system.location.service.maps.MapProvider.entries.map { it.label }.toTypedArray(), selected.ordinal) { dialog, which ->
                dialog.dismiss()
                val next = com.system.location.service.maps.MapProvider.entries[which]
                if (next != selected) {
                    val message = if (next == com.system.location.service.maps.MapProvider.TENCENT) {
                        getString(R.string.tencent_privacy_message)
                    } else getString(R.string.map_privacy_message)
                    MaterialAlertDialogBuilder(context)
                        .setTitle("切换到${next.label}")
                        .setMessage(message)
                        .setNeutralButton("查看政策") { _, _ ->
                            runCatching {
                                startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(
                                    if (next == com.system.location.service.maps.MapProvider.TENCENT) {
                                        "https://lbs.qq.com/userAgreements/agreements/privacy"
                                    } else "https://lbs.amap.com/pages/privacy/",
                                )))
                            }
                        }
                        .setNegativeButton("取消", null)
                        .setPositiveButton("同意并切换") { _, _ ->
                            runCatching {
                                if (next == com.system.location.service.maps.MapProvider.TENCENT) {
                                    com.system.location.service.privacy.TencentPrivacy.accept(context)
                                } else SdkPrivacy.acceptMaps()
                                context.mapProvider = next
                                requireActivity().recreate()
                            }.onFailure { showToast(it.message ?: "切换失败") }
                        }.show()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showAppRouting() {
        val context = requireContext()
        val existing = runCatching {
            readRoutingSettings(context.routingJson)
        }.getOrNull()
        if (existing == null) { showToast("原有应用规则无法读取，未覆盖配置"); return }
        openAppSelection("excluded", "分应用排除", existing.deniedPackages)
    }

    private fun showAppPoint() {
        findNavController().navigate(R.id.nav_point_selection)
    }

    private fun openAppSelection(purpose: String, title: String, selected: Set<String>, extra: Bundle = Bundle()) {
        findNavController().navigate(R.id.nav_app_selection, extra.apply {
            putString("purpose", purpose)
            putString("title", title)
            putStringArrayList("selected", ArrayList(selected.sorted()))
        })
    }

    private fun setCrashReporting(enabled: Boolean) {
        runCatching { SdkPrivacy.setCrashReporting(enabled) }
            .onSuccess {
                refresh()
                showToast(when {
                    SdkPrivacy.crashReportingPendingRestart -> "重启应用后开启崩溃报告"
                    enabled -> "崩溃报告已开启"
                    else -> "崩溃报告已关闭"
                })
            }
            .onFailure { showToast(it.message ?: "无法修改崩溃报告设置") }
    }

    private fun showUpdateChannel() {
        val current = UpdateChecker.currentChannel()
        val options = arrayOf(getString(R.string.update_channel_stable), getString(R.string.update_channel_beta))
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.update_channel_label)
            .setSingleChoiceItems(options, if (current == UpdateChannel.BETA) 1 else 0) { dialog, which ->
                dialog.dismiss()
                val next = if (which == 1) UpdateChannel.BETA else UpdateChannel.STABLE
                runCatching { UpdateChecker.setChannel(next) }
                    .onSuccess {
                        refresh()
                        showToast("更新通道已切换")
                        (activity as? MainActivity)?.onUpdateChannelChanged()
                    }
                    .onFailure { showToast(it.message ?: "无法保存更新通道") }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun checkForUpdate() {
        val request = ++updateRequest
        val channel = UpdateChecker.currentChannel()
        lifecycleScope.launch {
            val result = UpdateChecker.check(channel)
            if (!isAdded || request != updateRequest) return@launch
            when (result) {
                is UpdateChecker.Result.UpdateAvailable -> UpdateDialogFragment.show(requireActivity().supportFragmentManager, result.info)
                is UpdateChecker.Result.UpToDate -> showToast("当前已是最新版本")
                is UpdateChecker.Result.Error -> showToast("检查更新失败：${result.message}")
            }
        }
    }

    private fun applyConfigNowWithToast() {
        lifecycleScope.launch {
            val applied = withContext(Dispatchers.IO) { ScenarioRuntime.applyConfigNow() }
            showToast(if (applied) "配置已应用" else "已保存，下次启动场景生效")
        }
    }
}

private const val NEXT_SCENE = "下次启动场景生效"
private const val APPLY_WHILE_RUNNING = "运行中尝试立即应用"

private data class SettingsValues(
    val altitude: Double,
    val accuracy: Float,
    val reportDuration: Int,
    val speed: Double,
    val minSatelliteCount: Int,
    val mapProviderLabel: String,
    val updateChannelLabel: String,
    val smoothRouteMotion: Boolean,
    val physicalNoise: Boolean,
    val gmsMockEnabled: Boolean,
    val platformFusedEnabled: Boolean,
    val startupBurstEnabled: Boolean,
    val experimentalClearMockFlag: Boolean,
    val simReady: Boolean,
    val debug: Boolean,
    val disableGetCurrentLocation: Boolean,
    val disableRegisterLocationListener: Boolean,
    val disableFusedProvider: Boolean,
    val needDowngradeToCdma: Boolean,
    val hideMock: Boolean,
    val antiDetectEnabled: Boolean,
    val stackTraceCleanEnabled: Boolean,
    val mockWifiEnabled: Boolean,
    val loopBroadcastlocation: Boolean,
    val crashReporting: Boolean,
    val crashReportingEnabled: Boolean,
    val crashReportingPendingRestart: Boolean,
)

@Composable
private fun SettingsScreen(
    category: SettingsCategory?,
    onCategory: (SettingsCategory) -> Unit,
    captureModel: GnssCaptureViewModel,
    revision: Int,
    onMapProvider: () -> Unit,
    onEditAltitude: () -> Unit,
    onEditAccuracy: () -> Unit,
    onEditReportInterval: () -> Unit,
    onEditSpeed: () -> Unit,
    onEditSatelliteCount: () -> Unit,
    onAppRouting: () -> Unit,
    onSensorTargets: () -> Unit,
    onEditSensorPackages: () -> Unit,
    onAppPoint: () -> Unit,
    onEnvironment: () -> Unit,
    onBackgroundGuidance: () -> Unit,
    onCrashReporting: (Boolean) -> Unit,
    onUpdateChannel: () -> Unit,
    onCheckUpdate: () -> Unit,
    onConfigChanged: () -> Unit,
    onRestartRequired: () -> Unit,
    refresh: () -> Unit,
) {
    val context = LocalContext.current
    // Lazy item lambdas are memoized. Capture immutable values that change with the
    // revision, rather than reading non-observable preferences inside skipped items.
    val preferencesRevision = preferenceRevision()
    val values = remember(context, revision, preferencesRevision) {
        SettingsValues(
            altitude = context.altitude,
            accuracy = context.accuracy,
            reportDuration = context.reportDuration,
            speed = context.speed,
            minSatelliteCount = context.minSatelliteCount,
            mapProviderLabel = context.mapProvider.label,
            updateChannelLabel = if (UpdateChecker.currentChannel() == UpdateChannel.BETA) "Beta" else "正式版",
            smoothRouteMotion = context.smoothRouteMotion,
            physicalNoise = context.physicalNoise,
            gmsMockEnabled = context.gmsMockEnabled,
            platformFusedEnabled = context.platformFusedEnabled,
            startupBurstEnabled = context.startupBurstEnabled,
            experimentalClearMockFlag = context.experimentalClearMockFlag,
            simReady = context.simReady,
            debug = context.debug,
            disableGetCurrentLocation = context.disableGetCurrentLocation,
            disableRegisterLocationListener = context.disableRegisterLocationListener,
            disableFusedProvider = context.disableFusedProvider,
            needDowngradeToCdma = context.needDowngradeToCdma,
            hideMock = context.hideMock,
            antiDetectEnabled = context.antiDetectEnabled,
            stackTraceCleanEnabled = context.stackTraceCleanEnabled,
            mockWifiEnabled = context.mockWifiEnabled,
            loopBroadcastlocation = context.loopBroadcastlocation,
            crashReporting = SdkPrivacy.crashReportingRequested,
            crashReportingEnabled = SdkPrivacy.crashReportingEnabled,
            crashReportingPendingRestart = SdkPrivacy.crashReportingPendingRestart,
        )
    }
    val backendFlow = remember { ScenarioRuntime.state.map { it.backend }.distinctUntilChanged() }
    val backend by backendFlow.collectAsStateWithLifecycle(initialValue = ScenarioRuntime.state.value.backend)
    val sensorTargets = remember(context, revision, preferencesRevision) { context.sensorTargetPackages }
    val routingSummary by produceState("正在读取应用规则" to "正在读取应用规则", preferencesRevision, category) {
        if (category == SettingsCategory.APPS) value = withContext(Dispatchers.IO) {
            runCatching { readRoutingSettings(context.routingJson) }.fold(
                onSuccess = { rules ->
                    "已排除 ${rules.deniedPackages.size} 个应用" to
                        "已固定 ${rules.routes.count { it.point != null }} 个应用"
                },
                onFailure = { "无法读取已有规则" to "无法读取已有规则" },
            )
        }
    }
    val hooksEnabled = backend != BackendType.MOCK_PROVIDER
    fun hookAction(block: (Boolean) -> Unit): ((Boolean) -> Unit)? = if (hooksEnabled) {
        { checked -> block(checked); refresh(); onConfigChanged() }
    } else null

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            LazyColumn(
                modifier = Modifier.widthIn(max = 720.dp).fillMaxSize(),
                contentPadding = PaddingValues(20.dp, 16.dp, 20.dp, 32.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                if (category == null) {
                    item(key = "directory") {
                        SettingsDirectory(onCategory = onCategory, hooksEnabled = hooksEnabled,
                            mapProvider = values.mapProviderLabel, updateChannel = values.updateChannelLabel)
                    }
                }
                if (category == SettingsCategory.LOCATION) {
                    item(key = "maps") {
                        PortalSettingGroup("地图服务", 1) { _, rowModifier ->
                            PortalActionRow("地图提供方", values.mapProviderLabel, onMapProvider, modifier = rowModifier, status = "切换后重建地图")
                        }
                    }
                    item(key = "location") {
                        val parameters = listOf(
                            Triple("定位海拔", "%.2f 米".format(values.altitude), onEditAltitude),
                            Triple("定位精度", "%.2f 米".format(values.accuracy), onEditAccuracy),
                            Triple("位置上报间隔", "${values.reportDuration} ms", onEditReportInterval),
                        )
                        Column {
                            PortalSettingGroup("定位参数", parameters.size) { index, rowModifier ->
                                val (title, value, edit) = parameters[index]
                                PortalActionRow(title, value, edit, modifier = rowModifier, status = NEXT_SCENE)
                            }
                        }
                    }
                }
                if (category == SettingsCategory.MOTION) {
                    item(key = "motion") {
                        PortalSettingGroup("运动与摇杆", 3) { index, rowModifier ->
                            when (index) {
                                0 -> PortalActionRow("摇杆速度", "%.2f 米/秒".format(values.speed), onEditSpeed, status = "保存后立即更新",
                                    modifier = rowModifier,
                                )
                                1 -> PortalSwitchRow(
                                    title = "路线平滑运动",
                                    status = NEXT_SCENE,
                                    subtitle = "弯道减速、平滑加减速。",
                                    checked = values.smoothRouteMotion,
                                    onCheckedChange = { context.smoothRouteMotion = it; refresh() },
                                    modifier = rowModifier,
                                )
                                2 -> PortalSwitchRow(
                                    title = "物理漂移噪声",
                                    status = APPLY_WHILE_RUNNING,
                                    subtitle = "仅 Xposed/Native：为位置、海拔和精度添加有界微漂移。",
                                    checked = values.physicalNoise,
                                    onCheckedChange = hookAction { context.physicalNoise = it },
                                    modifier = rowModifier,
                                )
                            }
                        }
                    }
                }
                if (category == SettingsCategory.ADVANCED) {
                    item(key = "channels") {
                        PortalSettingGroup("定位通道与实验", 4) { index, rowModifier ->
                            when (index) {
                                0 -> PortalSwitchRow(
                                    title = "Google 融合定位（实验）",
                                    status = NEXT_SCENE,
                                    subtitle = "需 Google Play 服务；失败时继续使用 GPS/Network。",
                                    checked = values.gmsMockEnabled,
                                    onCheckedChange = { context.gmsMockEnabled = it; onRestartRequired(); refresh() },
                                    modifier = rowModifier,
                                )
                                1 -> PortalSwitchRow(
                                    title = "系统 Fused 通道（实验）",
                                    status = NEXT_SCENE,
                                    subtitle = "额外注册系统 fused 模拟 Provider。",
                                    checked = values.platformFusedEnabled,
                                    onCheckedChange = { context.platformFusedEnabled = it; onRestartRequired(); refresh() },
                                    modifier = rowModifier,
                                )
                                2 -> PortalSwitchRow(
                                    title = "启动突发",
                                    status = NEXT_SCENE,
                                    subtitle = "启动时密集上报定位，适用于当前后端。",
                                    checked = values.startupBurstEnabled,
                                    onCheckedChange = { context.startupBurstEnabled = it; refresh() },
                                    modifier = rowModifier,
                                )
                                3 -> PortalSwitchRow(
                                    title = "提交前反射清除模拟标记",
                                    status = NEXT_SCENE,
                                    subtitle = "仅标准 Mock 模式；系统可能重新标记。",
                                    checked = values.experimentalClearMockFlag,
                                    onCheckedChange = { context.experimentalClearMockFlag = it; refresh() },
                                    modifier = rowModifier,
                                )
                            }
                        }
                    }
                }
                if (category == SettingsCategory.APPS) {
                    item(key = "sensors") {
                        SensorSettingsControls(sensorTargets, onSensorTargets, onEditSensorPackages)
                    }
                }
                if (category == SettingsCategory.SYSTEM) {
                    item(key = "environment-tools") {
                        PortalSettingGroup("环境配置", 1) { _, rowModifier ->
                            PortalActionRow("环境工具", "采集、导入、回放与 Wi-Fi 指纹；模拟输出需 Xposed / Native",
                                onEnvironment, modifier = rowModifier)
                        }
                    }
                    item(key = "system-hooks") {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("仅 Xposed/Native 后端有效；未运行或应用失败时，下次启动场景生效。", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            val controls: List<@Composable (Modifier) -> Unit> = listOf(
                                { rowModifier ->
                                    PortalSwitchRow(
                                        title = "SIM 就绪",
                                        status = APPLY_WHILE_RUNNING,
                                        subtitle = "关闭时模拟无 SIM / 仅 Wi-Fi。",
                                        checked = values.simReady,
                                        onCheckedChange = hookAction { context.simReady = it },
                                        modifier = rowModifier,
                                    )
                                },
                                { rowModifier ->
                                    PortalSwitchRow(
                                        title = "网络降级为 CDMA",
                                        status = APPLY_WHILE_RUNNING,
                                        checked = values.needDowngradeToCdma,
                                        onCheckedChange = hookAction { context.needDowngradeToCdma = it },
                                        modifier = rowModifier,
                                    )
                                },
                                { rowModifier ->
                                    PortalSwitchRow(
                                        title = "模拟 Wi-Fi 环境",
                                        status = APPLY_WHILE_RUNNING,
                                        subtitle = "向第三方应用提供模拟的扫描结果与连接信息。",
                                        checked = values.mockWifiEnabled,
                                        onCheckedChange = hookAction { context.mockWifiEnabled = it },
                                        modifier = rowModifier,
                                    )
                                },
                            )
                            PortalSettingGroup(category.title, controls.size) { index, rowModifier ->
                                controls[index](rowModifier)
                            }
                        }
                    }
                }
                if (category == SettingsCategory.ADVANCED) {
                    item(key = "advanced-hooks") {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("仅 Xposed/Native 后端有效；未运行或应用失败时，下次启动场景生效。", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            val controls: List<@Composable (Modifier) -> Unit> = listOf(
                                { rowModifier ->
                                    PortalSwitchRow(
                                        title = "调试模式",
                                        status = APPLY_WHILE_RUNNING,
                                        checked = values.debug,
                                        onCheckedChange = hookAction { context.debug = it },
                                        modifier = rowModifier,
                                    )
                                },
                                { rowModifier ->
                                    PortalSwitchRow(
                                        title = "允许单次定位请求",
                                        status = APPLY_WHILE_RUNNING,
                                        checked = !values.disableGetCurrentLocation,
                                        onCheckedChange = hookAction { context.disableGetCurrentLocation = !it },
                                        modifier = rowModifier,
                                    )
                                },
                                { rowModifier ->
                                    PortalSwitchRow(
                                        title = "允许注册位置监听器",
                                        status = APPLY_WHILE_RUNNING,
                                        checked = !values.disableRegisterLocationListener,
                                        onCheckedChange = hookAction { context.disableRegisterLocationListener = !it },
                                        modifier = rowModifier,
                                    )
                                },
                                { rowModifier ->
                                    PortalSwitchRow(
                                        title = "禁用融合定位",
                                        status = APPLY_WHILE_RUNNING,
                                        checked = values.disableFusedProvider,
                                        onCheckedChange = hookAction { context.disableFusedProvider = it },
                                        modifier = rowModifier,
                                    )
                                },
                                { rowModifier ->
                                    PortalSwitchRow(
                                        title = "隐藏模拟标记",
                                        status = APPLY_WHILE_RUNNING,
                                        subtitle = "开启后不向应用暴露 isMock；关闭可用于验证应用的降级逻辑。",
                                        checked = values.hideMock,
                                        onCheckedChange = hookAction { context.hideMock = it },
                                        modifier = rowModifier,
                                    )
                                },
                                { rowModifier ->
                                    PortalSwitchRow(
                                        title = "实验性反检测（原生路径改写）",
                                        status = "需重启目标应用",
                                        subtitle = "默认关闭；仅在已选择的应用进程内改写 /proc、su 等敏感路径，需重启目标应用。",
                                        checked = values.antiDetectEnabled,
                                        onCheckedChange = hookAction { context.antiDetectEnabled = it },
                                        modifier = rowModifier,
                                    )
                                },
                                { rowModifier ->
                                    PortalSwitchRow(
                                        title = "实验性堆栈清洗",
                                        status = APPLY_WHILE_RUNNING,
                                        subtitle = "默认关闭；从 getStackTrace 中剔除本模块与框架帧，会改变栈长度这一可观测特征。",
                                        checked = values.stackTraceCleanEnabled,
                                        onCheckedChange = hookAction { context.stackTraceCleanEnabled = it },
                                        modifier = rowModifier,
                                    )
                                },
                                { rowModifier ->
                                    PortalSwitchRow(
                                        title = "按设定间隔广播",
                                        status = APPLY_WHILE_RUNNING,
                                        subtitle = "仅 Xposed/Native：开启时采用设定间隔，关闭时广播间隔至少为 500 ms。",
                                        checked = values.loopBroadcastlocation,
                                        onCheckedChange = hookAction { context.loopBroadcastlocation = it },
                                        modifier = rowModifier,
                                    )
                                },
                            )
                            PortalSettingGroup(category.title, controls.size) { index, rowModifier ->
                                controls[index](rowModifier)
                            }
                        }
                    }
                }
                if (category == SettingsCategory.GNSS) {
                    item(key = "gnss-policy") {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text("仅 Xposed/Native 后端有效；未运行或应用失败时，下次启动场景生效。", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            GnssPolicyControls(enabled = hooksEnabled, onChanged = { refresh(); onConfigChanged() })
                            PortalSettingGroup("卫星数量", 1) { _, rowModifier ->
                                PortalActionRow(
                                    title = "最少卫星数",
                                    status = APPLY_WHILE_RUNNING,
                                    subtitle = if (values.minSatelliteCount == 0) "自动数量：按所选模式和目标天空生成"
                                        else "可见卫星下限 ${values.minSatelliteCount} 颗；按所选数量策略处理不足",
                                    onClick = onEditSatelliteCount,
                                    enabled = hooksEnabled,
                                    modifier = rowModifier,
                                )
                            }
                            GnssCaptureStatus(captureModel, values.minSatelliteCount)
                        }
                    }
                }
                if (category == SettingsCategory.APPS) {
                    item(key = "apps") {
                        PortalSettingGroup("分应用规则", 2) { index, rowModifier ->
                            when (index) {
                                0 -> PortalActionRow("分应用路由（实验）", "${routingSummary.first}\n仅 Xposed/Native：选择不接收模拟定位的应用。", onAppRouting, enabled = hooksEnabled, status = APPLY_WHILE_RUNNING,
                                    modifier = rowModifier,
                                )
                                1 -> PortalActionRow("分应用固定点（实验）", "${routingSummary.second}\n仅 Xposed/Native：为选定应用指定已保存位置。", onAppPoint, enabled = hooksEnabled, status = APPLY_WHILE_RUNNING,
                                    modifier = rowModifier,
                                )
                            }
                        }
                    }
                }
                if (category == SettingsCategory.PRIVACY) {
                    item(key = "privacy") {
                        PortalSettingGroup("后台与隐私", 2) { index, rowModifier ->
                            when (index) {
                                0 -> PortalSwitchRow(
                                    title = "发送崩溃报告",
                                    status = when {
                                        values.crashReportingPendingRestart -> "等待重启 Portal 后开启"
                                        values.crashReportingEnabled -> "已开启"
                                        values.crashReporting -> "已保存开启选择"
                                        else -> "已关闭"
                                    },
                                    subtitle = "默认关闭；通过 Bugly 发送崩溃堆栈。关闭后重新开启需重启，不撤回已发送或待上传的报告。",
                                    checked = values.crashReporting,
                                    onCheckedChange = onCrashReporting,
                                    modifier = rowModifier,
                                )
                                1 -> PortalActionRow("后台运行", "权限与电池优化检查", onBackgroundGuidance,
                                    modifier = rowModifier,
                                )
                            }
                        }
                    }
                }
                if (category == SettingsCategory.UPDATES) {
                    item(key = "appearance") {
                        PortalSettingGroup("外观", 1) { _, rowModifier ->
                            LauncherIconSetting(rowModifier)
                        }
                    }
                    item(key = "updates") {
                        PortalSettingGroup("版本更新 · ${UpdateChecker.parseLocalVersion().versionName}", 2) { index, rowModifier ->
                            when (index) {
                                0 -> PortalActionRow("检查更新", onClick = onCheckUpdate,
                                    modifier = rowModifier,
                                )
                                1 -> PortalActionRow("更新通道", values.updateChannelLabel, onUpdateChannel, status = "立即用于后续更新检查",
                                    modifier = rowModifier,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
