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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.dp
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import com.system.location.service.ui.gnss.GnssCaptureStatus
import com.system.location.service.ui.viewmodel.GnssCaptureViewModel
import com.system.location.service.ui.viewmodel.SettingsViewModel
import com.system.location.service.ui.viewmodel.RestoreConfigState
import com.system.location.service.core.runtime.RuntimePhase
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.system.location.service.MainActivity
import com.system.location.service.R
import com.system.location.service.core.backend.BackendType
import com.system.location.service.core.repository.SavedLocation
import com.system.location.service.core.routing.withPointAssignments
import com.system.location.service.core.routing.withDeniedPackages
import com.system.location.service.core.routing.RoutingConfig
import com.system.location.service.data.repository.LibraryRepositories
import com.system.location.service.designsystem.components.PortalActionRow
import com.system.location.service.designsystem.components.PortalCard
import com.system.location.service.designsystem.components.PortalSectionTitle
import com.system.location.service.designsystem.components.PortalSwitchRow
import com.system.location.service.designsystem.theme.PortalTheme
import com.system.location.service.ext.accuracy
import com.system.location.service.ext.altitude
import com.system.location.service.ext.debug
import com.system.location.service.ext.disableFusedProvider
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/** Settings presentation; preference writes continue through the existing configuration API. */
class SettingsComposeFragment : Fragment() {
    private val model by viewModels<SettingsViewModel>()
    private val captureModel by viewModels<GnssCaptureViewModel>()
    private val revision = MutableStateFlow(0)
    private var updateRequest = 0L
    private var numberDialog: androidx.appcompat.app.AlertDialog? = null
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = ComposeView(requireContext()).apply {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        setContent {
            val revisionState = revision.collectAsStateWithLifecycle()
            val restoreState by model.restore.collectAsStateWithLifecycle()
            PortalTheme {
                SettingsScreen(
                    captureModel = captureModel,
                    revision = revisionState.value,
                    restoreState = restoreState,
                    onMapProvider = ::showMapProvider,
                    onEditAltitude = {
                        editNumber("设置海拔高度", context.altitude.toString()) { raw ->
                            val value = raw.toDoubleOrNull() ?: error("海拔高度不合法")
                            require(value in 0.0..10_000.0) { "海拔需在 0–10000 米之间" }
                            context.altitude = value
                            refresh()
                        }
                    },
                    onEditAccuracy = {
                        editNumber("设置精度", context.accuracy.toString()) { raw ->
                            val value = raw.toFloatOrNull() ?: error("精度不合法")
                            require(value > 0f && value <= 1_000f) { "精度需在 0–1000 米之间" }
                            context.accuracy = value
                            refresh()
                        }
                    },
                    onEditReportInterval = {
                        editNumber("设置上报间隔", context.reportDuration.toString(), decimal = false) { raw ->
                            val value = raw.toIntOrNull() ?: error("上报间隔不合法")
                            require(value in 50..1_000) { "上报间隔需在 50–1000 ms 之间" }
                            context.reportDuration = value
                            refresh()
                        }
                    },
                    onEditSpeed = {
                        editNumber("设置速度", context.speed.toString()) { raw ->
                            val value = raw.toDoubleOrNull() ?: error("速度不合法")
                            require(value in 0.0..1_000.0) { "速度需在 0–1000 米/秒之间" }
                            lifecycleScope.launch {
                                val accepted = ScenarioRuntime.setSpeed(value).await()
                                showToast(if (accepted) "速度已更新" else "速度更新失败，请重试")
                            }
                        }
                    },
                    onEditSatelliteCount = {
                        editNumber("可见卫星下限（0 为自动）", context.minSatelliteCount.toString(), decimal = false) { raw ->
                            val value = raw.toIntOrNull() ?: error("数量不合法")
                            require(value in 0..128) { "卫星数量需在 0–128 之间；0 表示按真实画像自动生成" }
                            context.minSatelliteCount = value
                            refresh()
                            applyConfigNowWithToast()
                        }
                    },
                    onAppRouting = ::showAppRouting,
                    onAppPoint = ::showAppPoint,
                    onRestoreConfig = model::restoreLastConfig,
                    onBackgroundGuidance = { com.system.location.service.runtime.BackgroundGuidance.show(requireContext()) },
                    onCrashReporting = { enabled -> setCrashReporting(enabled) },
                    onUpdateChannel = ::showUpdateChannel,
                    onCheckUpdate = ::checkForUpdate,
                    onConfigChanged = ::applyConfigNowWithToast,
                    onRestartRequired = { showToast("已保存，停止后重新启动场景生效") },
                    refresh = ::refresh,
                )
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

    override fun onDestroyView() {
        numberDialog?.dismiss()
        numberDialog = null
        super.onDestroyView()
    }

    private fun editNumber(title: String, initial: String, decimal: Boolean = true, onValue: (String) -> Unit) {
        numberDialog?.dismiss()
        val input = android.widget.EditText(requireContext()).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                if (decimal) android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL else 0
            setText(initial)
            setSelectAllOnFocus(true)
            isSingleLine = true
        }
        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(title)
            .setView(input)
            .setPositiveButton("保存", null)
            .setNegativeButton("取消", null)
            .create()
        numberDialog = dialog
        dialog.setOnShowListener {
            dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                runCatching { onValue(input.text.toString().trim()) }
                    .onSuccess { dialog.dismiss() }
                    .onFailure { input.error = it.message ?: "输入不合法"; input.requestFocus() }
            }
        }
        dialog.show()
        input.requestFocus()
    }

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
            Json.decodeFromString(RoutingConfig.serializer(), context.routingJson)
        }.getOrNull()
        showAppPicker("分应用排除", existing?.deniedPackages ?: emptySet()) { denied ->
            val config = (existing ?: RoutingConfig()).withDeniedPackages(denied)
            context.routingJson = Json.encodeToString(RoutingConfig.serializer(), config)
            refresh()
            applyConfigNowWithToast()
        }
    }

    private fun showAppPoint() {
        lifecycleScope.launch {
            val locations = withContext(Dispatchers.IO) {
                runCatching {
                    LibraryRepositories.ensureMigrated()
                    LibraryRepositories.locations.list()
                }.getOrDefault(emptyList())
            }
            if (!isAdded) return@launch
            if (locations.isEmpty()) {
                showToast(getString(R.string.per_app_no_locations))
                return@launch
            }
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.per_app_point_title)
                .setItems(locations.map { it.name }.toTypedArray()) { _, index ->
                    assignPoint(locations[index])
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    private fun assignPoint(location: SavedLocation) {
        val context = requireContext()
        val existing = runCatching {
            Json.decodeFromString(RoutingConfig.serializer(), context.routingJson)
        }.getOrNull() ?: RoutingConfig()
        val initial = existing.routes.filter { it.point == location.coordinate }.map { it.packageName }.toSet()
        showAppPicker(getString(R.string.per_app_point), initial) { selected ->
            context.routingJson = Json.encodeToString(
                RoutingConfig.serializer(),
                existing.withPointAssignments(location.coordinate, selected),
            )
            refresh()
            applyConfigNowWithToast()
        }
    }

    private fun showAppPicker(title: String, initiallySelected: Set<String>, onDone: (Set<String>) -> Unit) {
        val pm = requireContext().packageManager
        val apps = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .map { it.activityInfo.applicationInfo }
            .filter { it.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM == 0 }
            .distinctBy { it.packageName }
            .sortedBy { pm.getApplicationLabel(it).toString().lowercase() }
        val selected = initiallySelected.toMutableSet()
        val labels = apps.map { pm.getApplicationLabel(it).toString() }.toTypedArray()
        val checked = BooleanArray(apps.size) { apps[it].packageName in selected }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(title)
            .setMultiChoiceItems(labels, checked) { _, which, isChecked ->
                if (isChecked) selected.add(apps[which].packageName) else selected.remove(apps[which].packageName)
            }
            .setPositiveButton(R.string.per_app_done) { _, _ -> onDone(selected.toSet()) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
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
    val mockWifiEnabled: Boolean,
    val loopBroadcastlocation: Boolean,
    val crashReporting: Boolean,
)

@Composable
private fun SettingsScreen(
    captureModel: GnssCaptureViewModel,
    revision: Int,
    restoreState: RestoreConfigState,
    onMapProvider: () -> Unit,
    onEditAltitude: () -> Unit,
    onEditAccuracy: () -> Unit,
    onEditReportInterval: () -> Unit,
    onEditSpeed: () -> Unit,
    onEditSatelliteCount: () -> Unit,
    onAppRouting: () -> Unit,
    onAppPoint: () -> Unit,
    onRestoreConfig: () -> Unit,
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
            mockWifiEnabled = context.mockWifiEnabled,
            loopBroadcastlocation = context.loopBroadcastlocation,
            crashReporting = SdkPrivacy.crashReportingRequested,
        )
    }
    val runtime by ScenarioRuntime.state.collectAsStateWithLifecycle()
    val hooksEnabled = runtime.backend != BackendType.MOCK_PROVIDER
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
                item(key = "overview") {
                    Surface(
                        shape = MaterialTheme.shapes.large,
                        color = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    ) {
                        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("偏好与服务", style = MaterialTheme.typography.titleLarge)
                            Text("设置保存在本机", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
                item(key = "maps") {
                    PortalCard {
                        PortalSectionTitle("地图服务")
                        PortalActionRow("地图提供方", values.mapProviderLabel, onMapProvider)
                    }
                }
                item(key = "location") {
                    PortalCard {
                        PortalSectionTitle("定位参数", "速度即时更新；海拔、精度和上报间隔下次启动场景生效。")
                        PortalActionRow("定位海拔", "%.2f 米".format(values.altitude), onEditAltitude)
                        PortalActionRow("定位精度", "%.2f 米".format(values.accuracy), onEditAccuracy)
                        PortalActionRow("位置上报间隔", "${values.reportDuration} ms", onEditReportInterval)
                        PortalActionRow("摇杆速度", "%.2f 米/秒".format(values.speed), onEditSpeed)
                    }
                }
                item(key = "motion") {
                    PortalCard {
                        PortalSectionTitle("运动与摇杆")
                        PortalSwitchRow(
                            title = "路线平滑运动",
                            subtitle = "弯道减速、平滑加减速，下次启动生效。",
                            checked = values.smoothRouteMotion,
                            onCheckedChange = { context.smoothRouteMotion = it; refresh() },
                        )
                        PortalSwitchRow(
                            title = "物理漂移噪声",
                            subtitle = "仅 Xposed/Native：为位置、海拔和精度添加有界微漂移。",
                            checked = values.physicalNoise,
                            onCheckedChange = hookAction { context.physicalNoise = it },
                        )
                    }
                }
                item(key = "channels") {
                    PortalCard {
                        PortalSectionTitle("定位通道与实验")
                        PortalSwitchRow(
                            title = "Google 融合定位（实验）",
                            subtitle = "需 Google Play 服务；下次启动场景生效，失败时继续使用 GPS/Network。",
                            checked = values.gmsMockEnabled,
                            onCheckedChange = { context.gmsMockEnabled = it; onRestartRequired(); refresh() },
                        )
                        PortalSwitchRow(
                            title = "系统 Fused 通道（实验）",
                            subtitle = "额外注册系统 fused 模拟 Provider，下次启动场景生效。",
                            checked = values.platformFusedEnabled,
                            onCheckedChange = { context.platformFusedEnabled = it; onRestartRequired(); refresh() },
                        )
                        PortalSwitchRow(
                            title = "启动突发",
                            subtitle = "仅标准 Mock Provider：启动时密集上报定位，下次启动场景生效。",
                            checked = values.startupBurstEnabled,
                            onCheckedChange = { context.startupBurstEnabled = it; refresh() },
                        )
                        PortalSwitchRow(
                            title = "清除模拟标记（实验）",
                            subtitle = "仅标准 Mock 模式；系统可能重新标记。",
                            checked = values.experimentalClearMockFlag,
                            onCheckedChange = { context.experimentalClearMockFlag = it; refresh() },
                        )
                    }
                }
                item(key = "hooks") {
                    PortalCard {
                        PortalSectionTitle("系统 Hook", "仅 Xposed/Native 后端有效")
                        PortalSwitchRow(
                            title = "SIM 就绪",
                            subtitle = "关闭时模拟无 SIM / 仅 Wi-Fi。",
                            checked = values.simReady,
                            onCheckedChange = hookAction { context.simReady = it },
                        )
                        PortalSwitchRow(
                            title = "调试模式",
                            checked = values.debug,
                            onCheckedChange = hookAction { context.debug = it },
                        )
                        PortalSwitchRow(
                            title = "允许获取当前位置",
                            checked = !values.disableGetCurrentLocation,
                            onCheckedChange = hookAction { context.disableGetCurrentLocation = !it },
                        )
                        PortalSwitchRow(
                            title = "允许注册位置监听器",
                            checked = !values.disableRegisterLocationListener,
                            onCheckedChange = hookAction { context.disableRegisterLocationListener = !it },
                        )
                        PortalSwitchRow(
                            title = "禁用融合定位",
                            checked = values.disableFusedProvider,
                            onCheckedChange = hookAction { context.disableFusedProvider = it },
                        )
                        PortalSwitchRow(
                            title = "网络降级为 CDMA",
                            checked = values.needDowngradeToCdma,
                            onCheckedChange = hookAction { context.needDowngradeToCdma = it },
                        )
                        PortalActionRow(
                            title = "最少卫星数",
                            subtitle = if (values.minSatelliteCount == 0) "自动：按本机真实画像生成"
                                else "可见卫星下限 ${values.minSatelliteCount} 颗；受有效实测池容量限制",
                            onClick = onEditSatelliteCount,
                            enabled = hooksEnabled,
                        )
                        GnssCaptureStatus(captureModel, values.minSatelliteCount)
                        PortalSwitchRow(
                            title = "模拟 Wi-Fi 环境",
                            subtitle = "向第三方应用提供模拟的扫描结果与连接信息。",
                            checked = values.mockWifiEnabled,
                            onCheckedChange = hookAction { context.mockWifiEnabled = it },
                        )
                        PortalSwitchRow(
                            title = "反定位拉回",
                            subtitle = "按上报间隔持续发送固定位置。",
                            checked = values.loopBroadcastlocation,
                            onCheckedChange = hookAction { context.loopBroadcastlocation = it },
                        )
                    }
                }
                item(key = "apps") {
                    PortalCard {
                        PortalSectionTitle("分应用与配置")
                        PortalActionRow("分应用路由（实验）", "仅 Xposed/Native：选择不接收模拟定位的应用。", onAppRouting, enabled = hooksEnabled)
                        PortalActionRow("分应用固定点（实验）", "仅 Xposed/Native：为选定应用指定已保存位置。", onAppPoint, enabled = hooksEnabled)
                        PortalActionRow("恢复并启动上次场景", "恢复上次保存的参数，并立即启动模拟。", onRestoreConfig,
                            enabled = runtime.phase in setOf(RuntimePhase.IDLE, RuntimePhase.STOPPED) && !restoreState.busy)
                        restoreState.message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    }
                }
                item(key = "privacy") {
                    PortalCard {
                        PortalSectionTitle("后台与隐私")
                        PortalSwitchRow(
                            title = "发送崩溃报告",
                            subtitle = "默认关闭；通过 Bugly 发送崩溃堆栈。关闭后重新开启需重启，不撤回已发送或待上传的报告。",
                            checked = values.crashReporting,
                            onCheckedChange = onCrashReporting,
                        )
                        PortalActionRow("后台运行", "权限与电池优化检查", onBackgroundGuidance)
                    }
                }
                item(key = "updates") {
                    PortalCard {
                        PortalSectionTitle("版本更新", "当前版本 ${UpdateChecker.parseLocalVersion().versionName}")
                        PortalActionRow("检查更新", onClick = onCheckUpdate)
                        PortalActionRow("更新通道", values.updateChannelLabel, onUpdateChannel)
                    }
                }
            }
        }
    }
}
