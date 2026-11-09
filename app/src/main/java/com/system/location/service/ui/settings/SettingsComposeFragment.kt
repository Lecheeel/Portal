package com.system.location.service.ui.settings

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.dp
import androidx.fragment.app.Fragment
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.system.location.service.MainActivity
import com.system.location.service.R
import com.system.location.service.core.backend.BackendType
import com.system.location.service.core.repository.SavedLocation
import com.system.location.service.core.routing.AppRoute
import com.system.location.service.core.routing.RoutingConfig
import com.system.location.service.core.routing.RoutingMode
import com.system.location.service.data.repository.LibraryRepositories
import com.system.location.service.designsystem.components.PortalActionRow
import com.system.location.service.designsystem.components.PortalCard
import com.system.location.service.designsystem.components.PortalSectionTitle
import com.system.location.service.designsystem.components.PortalSwitchRow
import com.system.location.service.designsystem.theme.PortalSpacing
import com.system.location.service.designsystem.theme.PortalTheme
import com.system.location.service.ext.accuracy
import com.system.location.service.ext.altitude
import com.system.location.service.ext.debug
import com.system.location.service.ext.disableFusedProvider
import com.system.location.service.ext.disableGetCurrentLocation
import com.system.location.service.ext.disableRegisterLocationListener
import com.system.location.service.ext.disableWifiScan
import com.system.location.service.ext.experimentalClearMockFlag
import com.system.location.service.ext.gmsMockEnabled
import com.system.location.service.ext.gnssOrbital
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
import com.system.location.service.maps.MapProvider
import com.system.location.service.privacy.SdkPrivacy
import com.system.location.service.runtime.ScenarioRuntime
import com.system.location.service.update.UpdateChannel
import com.system.location.service.update.UpdateChecker
import com.system.location.service.update.UpdateDialogFragment
import com.system.location.service.ui.displayLabel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * Compose settings page, phase 2.
 *
 * Complex pickers/update dialogs remain in this Fragment host for now; the presentation layer is
 * Compose and every preference still goes through the existing extension properties.
 */
class SettingsComposeFragment : Fragment() {
    private val revision = MutableStateFlow(0)
    private var updateRequest = 0L

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = ComposeView(requireContext()).apply {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        setContent {
            val revisionState = revision.collectAsStateWithLifecycle()
            PortalTheme {
                SettingsScreen(
                    revision = revisionState.value,
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
                        editNumber("设置上报间隔", context.reportDuration.toString()) { raw ->
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
                            context.speed = value
                            refresh()
                        }
                    },
                    onEditSatelliteCount = {
                        editNumber("设置最小模拟卫星数量", context.minSatelliteCount.toString()) { raw ->
                            val value = raw.toIntOrNull() ?: error("数量不合法")
                            require(value in 0..35) { "卫星数量需在 0–35 之间" }
                            context.minSatelliteCount = value
                            refresh()
                        }
                    },
                    onAppRouting = ::showAppRouting,
                    onAppPoint = ::showAppPoint,
                    onRestoreConfig = ::restoreConfig,
                    onBackgroundGuidance = { com.system.location.service.runtime.BackgroundGuidance.show(requireContext()) },
                    onCrashReporting = { enabled -> setCrashReporting(enabled) },
                    onUpdateChannel = ::showUpdateChannel,
                    onCheckUpdate = ::checkForUpdate,
                    onConfigChanged = ::applyConfigNowWithToast,
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

    private fun editNumber(title: String, initial: String, onValue: (String) -> Unit) {
        var input by mutableStateOf(initial)
        var error by mutableStateOf<String?>(null)
        val dialog = android.app.AlertDialog.Builder(requireContext())
            .setTitle(title)
            .setView(android.widget.EditText(requireContext()).apply {
                setText(initial)
                setSelectAllOnFocus(true)
                addTextChangedListener(object : android.text.TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                        input = s?.toString().orEmpty()
                    }
                    override fun afterTextChanged(s: android.text.Editable?) = Unit
                })
            })
            .setPositiveButton("保存") { _, _ -> runCatching { onValue(input) }.onFailure { error = it.message } }
            .setNegativeButton("取消", null)
            .create()
        dialog.setOnDismissListener { error?.let { showToast(it) } }
        dialog.show()
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
            val config = if (denied.isEmpty()) RoutingConfig(mode = RoutingMode.GLOBAL)
            else RoutingConfig(mode = RoutingMode.PER_APP, deniedPackages = denied)
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
            val selectedLower = selected.map { it.lowercase() }.toSet()
            val routes = existing.routes.filterNot { it.packageName.lowercase() in selectedLower } +
                selected.map { AppRoute(it, point = location.coordinate) }
            context.routingJson = Json.encodeToString(
                RoutingConfig.serializer(),
                existing.copy(mode = RoutingMode.PER_APP, routes = routes),
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

    private fun restoreConfig() {
        lifecycleScope.launch {
            val restored = withContext(Dispatchers.IO) { ScenarioRuntime.restoreLastConfig() }
            showToast(if (restored) getString(R.string.restore_last_config_success) else getString(R.string.restore_last_config_unavailable))
        }
    }

    private fun setCrashReporting(enabled: Boolean) {
        runCatching { SdkPrivacy.setCrashReporting(enabled) }
            .onSuccess { refresh(); showToast(if (enabled) "崩溃报告已开启" else "崩溃报告已关闭") }
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

@Composable
private fun SettingsScreen(
    revision: Int,
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
    refresh: () -> Unit,
) {
    val context = LocalContext.current
    // Read the host revision so preference writes trigger recomposition.
    @Suppress("UNUSED_VARIABLE") val currentRevision = revision
    val runtime by ScenarioRuntime.state.collectAsStateWithLifecycle()
    val hooksEnabled = runtime.backend != BackendType.MOCK_PROVIDER
    fun hookAction(block: (Boolean) -> Unit): ((Boolean) -> Unit)? = if (hooksEnabled) block else null

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        LazyColumn(
            contentPadding = PaddingValues(20.dp, 24.dp, 20.dp, 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                Text(
                    "偏好设置会保存到本地；系统 Hook 选项仅 Xposed/Native 后端有效。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item {
                PortalCard {
                    PortalSectionTitle("定位参数")
                    PortalActionRow("定位海拔", "%.2f 米".format(context.altitude), onEditAltitude)
                    PortalActionRow("定位精度", "%.2f 米".format(context.accuracy), onEditAccuracy)
                    PortalActionRow("位置上报间隔", "${context.reportDuration} ms", onEditReportInterval)
                    PortalActionRow("摇杆速度", "%.2f 米/秒".format(context.speed), onEditSpeed)
                }
            }
            item {
                PortalCard {
                    PortalSectionTitle("运动与摇杆")
                    PortalSwitchRow(
                        title = "路线平滑运动",
                        subtitle = "弯道减速、平滑加减速，下次启动生效。",
                        checked = context.smoothRouteMotion,
                        onCheckedChange = { context.smoothRouteMotion = it; refresh() },
                    )
                    PortalSwitchRow(
                        title = "物理漂移噪声",
                        subtitle = "为位置、海拔和精度添加有界微漂移。",
                        checked = context.physicalNoise,
                        onCheckedChange = { context.physicalNoise = it; onConfigChanged(); refresh() },
                    )
                }
            }
            item {
                PortalCard {
                    PortalSectionTitle("定位通道与实验")
                    PortalSwitchRow(
                        title = "Google 融合定位通道（实验）",
                        subtitle = "需 Google Play 服务；失败时继续使用 GPS/Network。",
                        checked = context.gmsMockEnabled,
                        onCheckedChange = { context.gmsMockEnabled = it; onConfigChanged(); refresh() },
                    )
                    PortalSwitchRow(
                        title = "系统 Fused 通道（实验）",
                        subtitle = "额外注册系统 fused 模拟 Provider。",
                        checked = context.platformFusedEnabled,
                        onCheckedChange = { context.platformFusedEnabled = it; onConfigChanged(); refresh() },
                    )
                    PortalSwitchRow(
                        title = "启动突发",
                        subtitle = "启动时快速补齐定位。",
                        checked = context.startupBurstEnabled,
                        onCheckedChange = { context.startupBurstEnabled = it; refresh() },
                    )
                    PortalSwitchRow(
                        title = "反射清除模拟标记（实验）",
                        subtitle = "仅标准 Mock 模式；系统可能重新标记。",
                        checked = context.experimentalClearMockFlag,
                        onCheckedChange = { context.experimentalClearMockFlag = it; refresh() },
                    )
                }
            }
            item {
                PortalCard {
                    PortalSectionTitle("系统 Hook", "仅 Xposed/Native 后端有效")
                    PortalSwitchRow(
                        title = "轨道 GNSS 模型（实验）",
                        checked = context.gnssOrbital,
                        onCheckedChange = hookAction { context.gnssOrbital = it; onConfigChanged(); refresh() },
                    )
                    PortalSwitchRow(
                        title = "SIM 就绪（关闭 = 无 SIM / WiFi-only）",
                        checked = context.simReady,
                        onCheckedChange = hookAction { context.simReady = it; onConfigChanged(); refresh() },
                    )
                    PortalSwitchRow(
                        title = "调试模式",
                        checked = context.debug,
                        onCheckedChange = hookAction { context.debug = it; refresh() },
                    )
                    PortalSwitchRow(
                        title = "允许获取当前位置（新）",
                        checked = !context.disableGetCurrentLocation,
                        onCheckedChange = hookAction { context.disableGetCurrentLocation = !it; refresh() },
                    )
                    PortalSwitchRow(
                        title = "允许注册位置监听器",
                        checked = !context.disableRegisterLocationListener,
                        onCheckedChange = hookAction { context.disableRegisterLocationListener = !it; refresh() },
                    )
                    PortalSwitchRow(
                        title = "禁用融合定位",
                        checked = context.disableFusedProvider,
                        onCheckedChange = hookAction { context.disableFusedProvider = it; refresh() },
                    )
                    PortalSwitchRow(
                        title = "网络降级为 CDMA",
                        checked = context.needDowngradeToCdma,
                        onCheckedChange = hookAction { context.needDowngradeToCdma = it; refresh() },
                    )
                    PortalActionRow(
                        title = "模拟最少卫星数量",
                        subtitle = "${context.minSatelliteCount} 颗（仅北斗）",
                        onClick = onEditSatelliteCount,
                        enabled = hooksEnabled,
                    )
                    PortalSwitchRow(
                        title = "禁用扫描 WiFi 列表",
                        subtitle = "一定程度解决位置拉回（测试）。",
                        checked = context.disableWifiScan,
                        onCheckedChange = hookAction { context.disableWifiScan = it; refresh() },
                    )
                    PortalSwitchRow(
                        title = "反定位拉回",
                        subtitle = "按上报间隔持续发送固定位置。",
                        checked = context.loopBroadcastlocation,
                        onCheckedChange = hookAction { context.loopBroadcastlocation = it; refresh() },
                    )
                }
            }
            item {
                PortalCard {
                    PortalSectionTitle("分应用与配置")
                    PortalActionRow("分应用路由（实验）", "选择不接收模拟定位的应用。", onAppRouting)
                    PortalActionRow("分应用固定点（实验）", "为选定应用指定已保存位置。", onAppPoint)
                    PortalActionRow("恢复上次配置", onClick = onRestoreConfig)
                }
            }
            item {
                PortalCard {
                    PortalSectionTitle("后台与隐私")
                    PortalSwitchRow(
                        title = "发送崩溃报告",
                        subtitle = "默认关闭；开启后通过 Bugly 发送崩溃堆栈。",
                        checked = SdkPrivacy.crashReportingRequested,
                        onCheckedChange = onCrashReporting,
                    )
                    OutlinedButton(onClick = onBackgroundGuidance, modifier = Modifier.fillMaxWidth()) {
                        Text("后台运行设置与检查")
                    }
                }
            }
            item {
                PortalCard {
                    PortalSectionTitle("版本更新", "当前版本 ${UpdateChecker.parseLocalVersion().versionName}")
                    PortalActionRow("检查更新", "从 Releases 获取最新版本。", onCheckUpdate)
                    PortalActionRow("更新通道", if (UpdateChecker.currentChannel() == UpdateChannel.BETA) "Beta" else "正式版", onUpdateChannel)
                }
            }
            item {
                PortalActionRow("地图服务", context.mapProvider.label, onMapProvider)
            }
            item {
                Text(
                    "Hook 选项在标准 Mock 后端下不会生效。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
