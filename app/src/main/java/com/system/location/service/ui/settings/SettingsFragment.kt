package com.system.location.service.ui.settings

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CompoundButton
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.system.location.service.maps.*
import com.system.location.service.R
import com.system.location.service.databinding.FragmentSettingsBinding
import com.system.location.service.ext.routingJson
import com.system.location.service.core.routing.AppRoute
import com.system.location.service.core.routing.RoutingConfig
import com.system.location.service.core.routing.RoutingMode
import com.system.location.service.core.repository.SavedLocation
import com.system.location.service.data.repository.LibraryRepositories
import kotlinx.serialization.json.Json
import com.system.location.service.ext.accuracy
import com.system.location.service.ext.altitude
import com.system.location.service.ext.debug
import com.system.location.service.ext.disableFusedProvider
import com.system.location.service.ext.disableGetCurrentLocation
import com.system.location.service.ext.disableRegisterLocationListener
import com.system.location.service.ext.disableWifiScan
import com.system.location.service.ext.loopBroadcastlocation
import com.system.location.service.ext.minSatelliteCount
import com.system.location.service.ext.needDowngradeToCdma
import com.system.location.service.ext.physicalNoise
import com.system.location.service.ext.simReady
import com.system.location.service.ext.gnssOrbital
import com.system.location.service.ext.reportDuration
import com.system.location.service.ext.experimentalClearMockFlag
import com.system.location.service.ext.speed
import com.system.location.service.ext.smoothRouteMotion
import com.system.location.service.ext.gmsMockEnabled
import com.system.location.service.ext.platformFusedEnabled
import com.system.location.service.ext.startupBurstEnabled
import com.system.location.service.update.UpdateChecker
import com.system.location.service.update.UpdateDialogFragment
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged

class SettingsFragment : Fragment() {
    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!
    private var updateRequest = 0L
    private fun launchableApps(): List<android.content.pm.ApplicationInfo> {
        val pm = requireContext().packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(intent, 0)
            .map { it.activityInfo.applicationInfo }
            .filter { it.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM == 0 }
            .distinctBy { it.packageName }
            .sortedBy { pm.getApplicationLabel(it).toString().lowercase() }
    }

    @SuppressLint("SetTextI18n")
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        val root: View = binding.root

        val context = requireContext()
        binding.mapProviderButton.text = "地图服务 · ${context.mapProvider.label}"
        binding.mapProviderButton.setOnClickListener {
            MaterialAlertDialogBuilder(requireContext()).setTitle("地图服务")
                .setSingleChoiceItems(MapProvider.entries.map { it.label }.toTypedArray(), context.mapProvider.ordinal) { dialog, which ->
                    val selected = MapProvider.entries[which]
                    dialog.dismiss()
                    if (selected != context.mapProvider) {
                        val message = if (selected == MapProvider.TENCENT) getString(R.string.tencent_privacy_message)
                            else getString(R.string.map_privacy_message)
                        MaterialAlertDialogBuilder(requireContext()).setTitle("切换到${selected.label}")
                            .setMessage(message)
                            .setNeutralButton("查看政策") { _, _ ->
                                runCatching { startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(
                                    if (selected == MapProvider.TENCENT) "https://lbs.qq.com/userAgreements/agreements/privacy" else "https://lbs.amap.com/pages/privacy/"))) }
                            }
                            .setNegativeButton("取消", null)
                            .setPositiveButton("同意并切换") { _, _ ->
                                runCatching {
                                    if (selected == MapProvider.TENCENT) com.system.location.service.privacy.TencentPrivacy.accept(context)
                                    else com.system.location.service.privacy.SdkPrivacy.acceptMaps()
                                    context.mapProvider = selected
                                    requireActivity().recreate()
                                }.onFailure { showToast(it.message ?: "切换失败") }
                            }.show()
                    }
                }.setNegativeButton("取消", null).show()
        }
        val privacy = com.system.location.service.privacy.SdkPrivacy
        binding.crashReportingSwitch.isChecked = privacy.crashReportingRequested
        binding.crashReportingSwitch.contentDescription = getString(
            if (privacy.crashReportingPendingRestart) R.string.crash_reporting_pending_restart else R.string.crash_reporting_desc
        )
        binding.crashReportingDescription.text = if (privacy.crashReportingPendingRestart)
            getString(R.string.crash_reporting_pending_restart) + "\n" + getString(R.string.crash_reporting_desc)
        else getString(R.string.crash_reporting_desc)
        var updatingCrashSwitch = false
        binding.crashReportingSwitch.setOnCheckedChangeListener { _, enabled ->
            if (!updatingCrashSwitch) {
                runCatching { com.system.location.service.privacy.SdkPrivacy.setCrashReporting(enabled) }
                    .onSuccess {
                        val message = when {
                            privacy.crashReportingPendingRestart -> R.string.crash_reporting_pending_restart
                            privacy.crashReportingEnabled -> R.string.crash_reporting_on
                            else -> R.string.crash_reporting_off
                        }
                        binding.crashReportingSwitch.contentDescription = getString(message)
                        binding.crashReportingDescription.text = getString(message) + "\n" + getString(R.string.crash_reporting_desc)
                        showToast(getString(message))
                    }
                    .onFailure { _ ->
                        updatingCrashSwitch = true
                        binding.crashReportingSwitch.isChecked = privacy.crashReportingRequested
                        updatingCrashSwitch = false
                        binding.crashReportingSwitch.contentDescription = getString(R.string.crash_reporting_desc)
                        binding.crashReportingDescription.text = getString(
                            if (!enabled) R.string.crash_reporting_withdraw_failed else R.string.crash_reporting_enable_failed
                        ) + "\n" + getString(R.string.crash_reporting_desc)
                        showToast(getString(if (!enabled) R.string.crash_reporting_withdraw_failed else R.string.crash_reporting_enable_failed))
                    }
            }
        }
        binding.gmsMockSwitch.isChecked = context.gmsMockEnabled
        binding.gmsMockSwitch.setOnCheckedChangeListener { _, enabled -> context.gmsMockEnabled = enabled; applyConfigNowWithToast() }
        binding.platformFusedSwitch.isChecked = context.platformFusedEnabled
        binding.platformFusedSwitch.setOnCheckedChangeListener { _, enabled -> context.platformFusedEnabled = enabled; applyConfigNowWithToast() }
        binding.startupBurstSwitch.isChecked = context.startupBurstEnabled
        binding.startupBurstSwitch.setOnCheckedChangeListener { _, enabled -> context.startupBurstEnabled = enabled }
        binding.backgroundGuidance.setOnClickListener { com.system.location.service.runtime.BackgroundGuidance.show(requireContext()) }
        fun showAppPicker(title: String, initiallySelected: Set<String>, onDone: (Set<String>) -> Unit) {
            val pm = requireContext().packageManager
            val apps = launchableApps().sortedBy { pm.getApplicationLabel(it).toString().lowercase() }
            val selected = initiallySelected.toMutableSet()

            fun open(query: String = "") {
                val filtered = if (query.isBlank()) apps else apps.filter {
                    pm.getApplicationLabel(it).toString().contains(query, ignoreCase = true) ||
                        it.packageName.contains(query, ignoreCase = true)
                }
                if (filtered.isEmpty()) {
                    showToast(getString(R.string.per_app_no_match))
                    return
                }
                val labels = filtered.map { pm.getApplicationLabel(it).toString() }.toTypedArray()
                val checked = BooleanArray(filtered.size) { filtered[it].packageName in selected }
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle(title)
                    .setMultiChoiceItems(labels, checked) { _, which, isChecked ->
                        if (isChecked) selected.add(filtered[which].packageName)
                        else selected.remove(filtered[which].packageName)
                    }
                    .setPositiveButton(R.string.per_app_done) { _, _ -> onDone(selected.toSet()) }
                    .setNeutralButton(R.string.per_app_search) { _, _ ->
                        val input = TextInputEditText(requireContext()).apply {
                            hint = getString(R.string.per_app_search_hint)
                            setSingleLine()
                        }
                        MaterialAlertDialogBuilder(requireContext())
                            .setTitle(R.string.per_app_search)
                            .setView(input)
                            .setPositiveButton(android.R.string.ok) { _, _ ->
                                val value = input.text?.toString()?.trim().orEmpty()
                                if (value.isEmpty()) open() else open(value)
                            }
                            .setNegativeButton(android.R.string.cancel, null)
                            .show()
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
            open()
        }

        fun assignPointToApps(location: SavedLocation) {
            val existing = runCatching {
                Json.decodeFromString(RoutingConfig.serializer(), context.routingJson)
            }.getOrNull() ?: RoutingConfig()
            val initial = existing.routes
                .filter { it.point == location.coordinate }
                .map { it.packageName }
                .toSet()
            showAppPicker(getString(R.string.per_app_point), initial) { selected ->
                val selectedLower = selected.map { it.lowercase() }.toSet()
                val routes = existing.routes.filterNot { it.packageName.lowercase() in selectedLower } +
                    selected.map { AppRoute(it, point = location.coordinate) }
                val config = existing.copy(mode = RoutingMode.PER_APP, routes = routes)
                context.routingJson = Json.encodeToString(RoutingConfig.serializer(), config)
                lifecycleScope.launch {
                    val applied = withContext(Dispatchers.IO) {
                        com.system.location.service.runtime.ScenarioRuntime.applyConfigNow()
                    }
                    showToast(if (applied) "固定点已应用" else "已保存，下次启动场景生效")
                }
            }
        }
        binding.perAppPointButton.setOnClickListener {
            lifecycleScope.launch {
                val locations = withContext(Dispatchers.IO) {
                    runCatching {
                        LibraryRepositories.ensureMigrated()
                        LibraryRepositories.locations.list()
                    }.getOrDefault(emptyList())
                }
                if (locations.isEmpty()) {
                    showToast(getString(R.string.per_app_no_locations))
                    return@launch
                }
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.per_app_point_title)
                    .setItems(locations.map { it.name }.toTypedArray()) { _, index ->
                        assignPointToApps(locations[index])
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
        }
        binding.perAppRoutingButton.setOnClickListener {
            val existing = runCatching {
                Json.decodeFromString(RoutingConfig.serializer(), context.routingJson)
            }.getOrNull()
            showAppPicker(getString(R.string.per_app_routing_title), existing?.deniedPackages ?: emptySet()) { denied ->
                val config = if (denied.isEmpty()) {
                    RoutingConfig(mode = RoutingMode.GLOBAL)
                } else {
                    RoutingConfig(mode = RoutingMode.PER_APP, deniedPackages = denied)
                }
                context.routingJson = Json.encodeToString(RoutingConfig.serializer(), config)
                lifecycleScope.launch {
                    val applied = withContext(Dispatchers.IO) {
                        com.system.location.service.runtime.ScenarioRuntime.applyConfigNow()
                    }
                    showToast(if (applied) "配置已应用" else "已保存，下次启动场景生效")
                }
            }
        }
        binding.restoreConfigButton.setOnClickListener {
            lifecycleScope.launch {
                val restored = withContext(Dispatchers.IO) {
                    com.system.location.service.runtime.ScenarioRuntime.restoreLastConfig()
                }
                showToast(if (restored) getString(R.string.restore_last_config_success)
                    else getString(R.string.restore_last_config_unavailable))
            }
        }
        binding.smoothRouteSwitch.isChecked = context.smoothRouteMotion
        binding.smoothRouteSwitch.setOnCheckedChangeListener { _, enabled -> context.smoothRouteMotion = enabled }
        binding.physicalNoiseSwitch.isChecked = context.physicalNoise
        binding.physicalNoiseSwitch.setOnCheckedChangeListener { _, enabled ->
            context.physicalNoise = enabled
            applyConfigNowWithToast()
        }
        binding.simReadySwitch.isChecked = context.simReady
        binding.simReadySwitch.setOnCheckedChangeListener { _, ready ->
            context.simReady = ready
            applyConfigNowWithToast()
        }
        binding.gnssOrbitalSwitch.isChecked = context.gnssOrbital
        binding.gnssOrbitalSwitch.setOnCheckedChangeListener { _, orbital ->
            context.gnssOrbital = orbital
            applyConfigNowWithToast()
        }
        binding.altitudeValue.text = "%.2f米".format(context.altitude)
        binding.speedValue.text = "%.2f米/秒".format(context.speed)
        binding.accuracyValue.text = "%.2f米".format(context.accuracy)
        binding.reportDurationValue.text = "%dms".format(context.reportDuration)
        binding.clearMockFlagSwitch.isChecked = context.experimentalClearMockFlag
        binding.clearMockFlagSwitch.setOnCheckedChangeListener { _, enabled ->
            context.experimentalClearMockFlag = enabled
            showToast(if (enabled) "反射清除实验已开启，下次提交生效；系统可能重新标记" else "反射清除实验已关闭")
            com.system.location.service.runtime.ScenarioRuntime.refreshDiagnostics()
        }
        binding.satelliteCountValue.text = "%d颗".format(context.minSatelliteCount)

        binding.altitudeLayout.setOnClickListener {
            showDialog("设置海拔高度", binding.altitudeValue.text.toString().let { it.substring(0, it.length - 1) }) {
                val value = it.toDoubleOrNull()
                if (value == null || !value.isFinite() || value < 0.0) {
                    showToast("海拔高度不合法")
                    return@showDialog
                } else if (value > 10000) {
                    showToast("海拔高度不能超过10000米")
                    return@showDialog
                }
                context.altitude = value
                binding.altitudeValue.text = "%.2f米".format(value)
            }
        }

        binding.speedLayout.setOnClickListener {
            showDialog("设置速度", binding.speedValue.text.toString().let { it.substring(0, it.length - 3) }) {
                val value = it.toDoubleOrNull()
                if (value == null || !value.isFinite() || value < 0.0) {
                    showToast("速度不合法")
                    return@showDialog
                } else if (value > 1000) {
                    showToast("速度不能超过1000米/秒")
                    return@showDialog
                }
                context.speed = value
                binding.speedValue.text = "%.2f米/秒".format(value)
                updateRemoteConfig()
            }
        }

        binding.accuracyLayout.setOnClickListener {
            showDialog("设置精度", binding.accuracyValue.text.toString().let { it.substring(0, it.length - 1) }) {
                val value = it.toFloatOrNull()
                if (value == null || !value.isFinite() || value <= 0.0) {
                    Toast.makeText(context, "精度不合法", Toast.LENGTH_SHORT).show()
                    return@showDialog
                } else if (value > 1000) {
                    Toast.makeText(context, "精度不能超过1000米", Toast.LENGTH_SHORT).show()
                    return@showDialog
                }
                context.accuracy = value
                binding.accuracyValue.text = "%.2f米".format(value)
                updateRemoteConfig()
            }
        }

        binding.debugSwitch.isChecked = context.debug
        binding.debugSwitch.setOnCheckedChangeListener(object: CompoundButton.OnCheckedChangeListener {
            override fun onCheckedChanged(
                buttonView: CompoundButton,
                isChecked: Boolean
            ) {
                context.debug = isChecked
                showToast(if (isChecked) "已开启调试模式" else "已关闭调试模式")
                updateRemoteConfig()
            }
        })

        binding.dgcSwitch.isChecked = !context.disableGetCurrentLocation
        binding.dgcSwitch.setOnCheckedChangeListener(object: CompoundButton.OnCheckedChangeListener {
            override fun onCheckedChanged(
                buttonView: CompoundButton,
                isChecked: Boolean
            ) {
                context.disableGetCurrentLocation = !isChecked
                showToast(if (!isChecked) "禁止应用使用该方法" else "已允许应用使用该方法")
                updateRemoteConfig()
            }
        })

        binding.rllSwitch.isChecked = !context.disableRegisterLocationListener
        binding.rllSwitch.setOnCheckedChangeListener(object: CompoundButton.OnCheckedChangeListener {
            override fun onCheckedChanged(
                buttonView: CompoundButton,
                isChecked: Boolean
            ) {
                context.disableRegisterLocationListener = !isChecked
                showToast(if (!isChecked) "禁止应用使用该方法" else "已允许应用使用该方法")
                updateRemoteConfig()
            }
        })

        binding.dfusedSwitch.isChecked = context.disableFusedProvider
        binding.dfusedSwitch.setOnCheckedChangeListener(object: CompoundButton.OnCheckedChangeListener {
            override fun onCheckedChanged(
                buttonView: CompoundButton,
                isChecked: Boolean
            ) {
                context.disableFusedProvider = isChecked
                showToast(if (isChecked) "已禁用FusedProvider" else "已启用FusedProvider")
                updateRemoteConfig()
            }
        })

        binding.cdmaSwitch.isChecked = context.needDowngradeToCdma
        binding.cdmaSwitch.setOnCheckedChangeListener(object: CompoundButton.OnCheckedChangeListener {
            override fun onCheckedChanged(
                buttonView: CompoundButton,
                isChecked: Boolean
            ) {
                context.needDowngradeToCdma = isChecked
                showToast(if (isChecked) "已降级为CDMA" else "已取消降级为CDMA")
                updateRemoteConfig()
            }
        })

        binding.reportDurationLayout.setOnClickListener {
            showDialog("设置上报间隔", binding.reportDurationValue.text.toString().let {
                it.substring(0, it.length - 2)
            }) {
                val value = it.toIntOrNull()
                if (value == null || value < 50) {
                    Toast.makeText(context, "上报间隔不能小于50ms", Toast.LENGTH_SHORT).show()
                    return@showDialog
                } else if (value > 1000) {
                    Toast.makeText(context, "上报间隔不能大于1s", Toast.LENGTH_SHORT).show()
                    return@showDialog
                }
                context.reportDuration = value
                binding.reportDurationValue.text = "%dms".format(value)
                updateRemoteConfig()
            }
        }

        binding.satelliteCountLayout.setOnClickListener {
            showDialog("设置最小模拟卫星数量", binding.satelliteCountValue.text.toString().let {
                it.substring(0, it.length - 1)
            }) {
                val value = it.toIntOrNull()
                if (value == null || value < 0) {
                    Toast.makeText(context, "数量不合法", Toast.LENGTH_SHORT).show()
                    return@showDialog
                } else if (value > 35) {
                    Toast.makeText(context, "卫星数量不能超过35", Toast.LENGTH_SHORT).show()
                    return@showDialog
                }
                context.minSatelliteCount = value
                binding.satelliteCountValue.text = "%d颗".format(value)
                showToast("重新启动模拟生效")
                updateRemoteConfig()
            }
        }

        binding.disableWlanScanSwitch.isChecked = requireContext().disableWifiScan
        binding.disableWlanScanSwitch.setOnCheckedChangeListener { _, isChecked ->
            requireContext().disableWifiScan = isChecked
            updateRemoteConfig()
        }

        binding.loopBroadcastLocationSwitch.isChecked = requireContext().loopBroadcastlocation
        binding.loopBroadcastLocationSwitch.setOnCheckedChangeListener { _, isChecked ->
            requireContext().loopBroadcastlocation = isChecked
            updateRemoteConfig()
        }

        binding.checkUpdateDesc.text = "当前版本 ${UpdateChecker.parseLocalVersion().versionName}"
        var settingChannel = UpdateChecker.currentChannel()
        binding.updateChannel.adapter = android.widget.ArrayAdapter(requireContext(),
            android.R.layout.simple_spinner_dropdown_item, listOf(getString(R.string.update_channel_stable), getString(R.string.update_channel_beta)))
        binding.updateChannel.setSelection(if (settingChannel == com.system.location.service.update.UpdateChannel.BETA) 1 else 0)
        binding.updateChannel.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                val selected = if (position == 1) com.system.location.service.update.UpdateChannel.BETA
                    else com.system.location.service.update.UpdateChannel.STABLE
                if (selected != settingChannel) {
                    runCatching { UpdateChecker.setChannel(selected) }
                        .onSuccess {
                            settingChannel = selected
                            updateRequest++
                            binding.checkUpdateButton.isEnabled = true
                            binding.checkUpdateDesc.text = "当前版本 ${UpdateChecker.parseLocalVersion().versionName}"
                            showToast(if (selected == com.system.location.service.update.UpdateChannel.BETA)
                                "已切换到 Beta 通道；切回正式版不会自动降级已安装的高版本" else "已切换到正式通道")
                            (activity as? com.system.location.service.MainActivity)?.onUpdateChannelChanged()
                        }
                        .onFailure { error ->
                            binding.updateChannel.setSelection(if (settingChannel == com.system.location.service.update.UpdateChannel.BETA) 1 else 0)
                            showToast(error.message ?: "无法保存更新通道")
                        }
                }
            }
        }
        binding.checkUpdateButton.setOnClickListener { checkForUpdate() }

        return root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
                com.system.location.service.runtime.ScenarioRuntime.state
                    .map { it.backend != com.system.location.service.core.backend.BackendType.MOCK_PROVIDER }
                    .distinctUntilChanged().collect { hooks ->
                    listOf(binding.debugSwitch, binding.dgcSwitch, binding.rllSwitch, binding.dfusedSwitch,
                        binding.cdmaSwitch, binding.disableWlanScanSwitch, binding.loopBroadcastLocationSwitch).forEach {
                        it.isEnabled = hooks
                        it.contentDescription = if (hooks) "下次启动 Xposed 场景生效" else "标准 Mock 模式不支持系统 Hook 设置"
                    }
                    binding.satelliteCountLayout.isEnabled = hooks
                }
            }
        }
    }

    private fun checkForUpdate() {
        val request = ++updateRequest
        val channel = UpdateChecker.currentChannel()
        binding.checkUpdateButton.isEnabled = false
        binding.checkUpdateDesc.text = "正在检查更新…"
        viewLifecycleOwner.lifecycleScope.launch {
            val result = UpdateChecker.check(channel)
            if (_binding == null || request != updateRequest || channel != UpdateChecker.currentChannel()) return@launch
            binding.checkUpdateButton.isEnabled = true
            when (result) {
                is UpdateChecker.Result.UpdateAvailable -> {
                    val info = result.info
                    binding.checkUpdateDesc.text = "发现新版本 ${info.versionName}"
                    UpdateDialogFragment.show(requireActivity().supportFragmentManager, info)
                }
                is UpdateChecker.Result.UpToDate -> {
                    binding.checkUpdateDesc.text =
                        "已是最新版本 ${UpdateChecker.parseLocalVersion().versionName}"
                    showToast("当前已是最新版本")
                }
                is UpdateChecker.Result.Error -> {
                    binding.checkUpdateDesc.text = "检查失败：${result.message}"
                    showToast("检查更新失败：${result.message}")
                }
            }
        }
    }

    private fun showToast(message: String) {
        lifecycleScope.launch(Dispatchers.Main) {
            Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateRemoteConfig() {
        showToast("配置已保存，下次启动场景生效")
    }

    @SuppressLint("MissingInflatedId")
    private fun applyConfigNowWithToast() {
        lifecycleScope.launch {
            val applied = withContext(Dispatchers.IO) {
                com.system.location.service.runtime.ScenarioRuntime.applyConfigNow()
            }
            if (isAdded) showToast(if (applied) "配置已应用" else "已保存，下次启动场景生效")
        }
    }

    private fun showDialog(titleText: String, valueText: String, handler: (String) -> Unit) {
        val inflater = LayoutInflater.from(requireContext())
        val dialogView = inflater.inflate(R.layout.dialog_input, null)

        val title = dialogView.findViewById<TextView>(R.id.title)
        title.text = titleText

        val value = dialogView.findViewById<TextInputEditText>(R.id.value)
        value.setText(valueText)

        val builder = MaterialAlertDialogBuilder(requireContext())
        builder.setTitle(null)
        builder
            .setCancelable(false)
            .setView(dialogView)
            .setPositiveButton("保存") { _, _ ->
                handler(value.text.toString())
            }
            .setNegativeButton("取消", null)
            .show()
    }

    override fun onDestroyView() {
        updateRequest++
        super.onDestroyView()
        _binding = null
    }
}
