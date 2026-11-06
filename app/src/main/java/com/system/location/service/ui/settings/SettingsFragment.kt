package com.system.location.service.ui.settings

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CompoundButton
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.system.location.service.R
import com.system.location.service.databinding.FragmentSettingsBinding
import com.system.location.service.environment.EnvironmentCollector
import com.system.location.service.environment.EnvironmentStore
import com.system.location.service.ext.environmentReplayJson
import com.system.location.service.ext.routingJson
import com.system.location.service.core.environment.EnvironmentProfile
import com.system.location.service.core.environment.EnvironmentProfileCodec
import com.system.location.service.core.routing.AppRoute
import com.system.location.service.core.routing.RoutingConfig
import com.system.location.service.core.routing.RoutingMode
import com.system.location.service.core.repository.SavedLocation
import com.system.location.service.data.repository.LibraryRepositories
import kotlinx.serialization.json.Json
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.system.location.service.ext.accuracy
import com.system.location.service.ext.altitude
import com.system.location.service.ext.debug
import com.system.location.service.ext.disableFusedProvider
import com.system.location.service.ext.disableGetCurrentLocation
import com.system.location.service.ext.disableRegisterLocationListener
import com.system.location.service.ext.disableWifiScan
import com.system.location.service.ext.hookSensor
import com.system.location.service.ext.loopBroadcastlocation
import com.system.location.service.ext.minSatelliteCount
import com.system.location.service.ext.needDowngradeToCdma
import com.system.location.service.ext.needOpenSELinux
import com.system.location.service.ext.environmentApCount
import com.system.location.service.ext.cellMode
import com.system.location.service.ext.environmentSourceMode
import com.system.location.service.ext.physicalNoise
import com.system.location.service.ext.simReady
import com.system.location.service.ext.gnssOrbital
import com.system.location.service.ext.reportDuration
import com.system.location.service.ext.experimentalClearMockFlag
import com.system.location.service.ext.experimentalOrbitMotion
import com.system.location.service.ext.experimentalOrbitRadius
import com.system.location.service.ext.speed
import com.system.location.service.ext.smoothRouteMotion
import com.system.location.service.ext.orbitPeriodSeconds
import com.system.location.service.ext.gmsMockEnabled
import com.system.location.service.ext.platformFusedEnabled
import com.system.location.service.ext.startupBurstEnabled
import com.system.location.service.service.MockServiceHelper
import com.system.location.service.update.UpdateChecker
import com.system.location.service.update.UpdateDialogFragment
import com.system.location.service.ui.viewmodel.MockServiceViewModel
import com.system.location.service.ui.viewmodel.SettingsViewModel
import kotlin.getValue
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged

class SettingsFragment : Fragment() {
    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!
    private var updateRequest = 0L
    private var environmentExport: Pair<String, EnvironmentProfile>? = null
    private var pendingEnvironmentCapture: (() -> Unit)? = null

    private val bleScanPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val pending = pendingEnvironmentCapture
        pendingEnvironmentCapture = null
        if (granted) {
            pending?.invoke()
        } else if (isAdded) {
            showToast(getString(R.string.environment_ble_permission_denied))
        }
    }

    private fun launchableApps(): List<android.content.pm.ApplicationInfo> {
        val pm = requireContext().packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(intent, 0)
            .map { it.activityInfo.applicationInfo }
            .filter { it.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM == 0 }
            .distinctBy { it.packageName }
            .sortedBy { pm.getApplicationLabel(it).toString().lowercase() }
    }

    private val createEnvironmentDocument = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val pending = environmentExport
        environmentExport = null
        if (uri == null || pending == null) return@registerForActivityResult
        runCatching {
            requireContext().contentResolver.openOutputStream(uri)?.use { stream ->
                stream.write(EnvironmentProfileCodec.encode(pending.second).toByteArray(Charsets.UTF_8))
            } ?: error("无法写入文件")
        }.onSuccess {
            if (isAdded) showToast(getString(R.string.environment_export_success))
        }.onFailure { error ->
            if (isAdded) showToast(getString(R.string.environment_export_failed, error.message ?: "unknown"))
        }
    }

    private val importEnvironmentDocument = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        val appContext = requireContext().applicationContext
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val raw = appContext.contentResolver.openInputStream(uri)
                        ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                        ?: error("无法读取文件")
                    val profile = EnvironmentProfileCodec.decode(raw)
                    val name = "import-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
                    EnvironmentStore(appContext).save(name, profile)
                    name
                }
            }
            result.onSuccess { name ->
                if (isAdded) showToast(getString(R.string.environment_import_success, name))
            }.onFailure { error ->
                if (isAdded) showToast(getString(R.string.environment_import_failed, error.message ?: "unknown"))
            }
        }
    }

    private val mockServiceViewModel by activityViewModels<MockServiceViewModel>()

    @SuppressLint("SetTextI18n")
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val settingsViewModel =
            ViewModelProvider(this)[SettingsViewModel::class.java]

        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        val root: View = binding.root

        val context = requireContext()
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
        binding.environmentListButton.setOnClickListener {
            val appContext = context.applicationContext
            val names = EnvironmentStore(appContext).list()
            if (names.isEmpty()) {
                showToast(getString(R.string.environment_list_empty))
                return@setOnClickListener
            }
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.environment_list_title)
                .setItems(names.toTypedArray()) { _, index ->
                    val name = names[index]
                    MaterialAlertDialogBuilder(requireContext())
                        .setTitle(name)
                        .setItems(arrayOf(
                            getString(R.string.environment_export),
                            getString(R.string.environment_replay),
                        )) { _, action ->
                            runCatching { EnvironmentStore(appContext).load(name) }
                                .onSuccess { profile ->
                                    if (action == 0) {
                                        environmentExport = name to profile
                                        createEnvironmentDocument.launch("$name.json")
                                    } else {
                                        context.environmentReplayJson = EnvironmentProfileCodec.encode(profile)
                                        lifecycleScope.launch {
                                            val applied = withContext(Dispatchers.IO) {
                                                com.system.location.service.runtime.ScenarioRuntime.applyConfigNow()
                                            }
                                            showToast(if (applied) "回放环境已应用" else getString(R.string.environment_replay_set))
                                        }
                                    }
                                }
                                .onFailure { error ->
                                    showToast(getString(R.string.environment_export_failed, error.message ?: "unknown"))
                                }
                        }
                        .show()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
        fun captureEnvironment() {
            binding.environmentCaptureButton.isEnabled = false
            val appContext = context.applicationContext
            lifecycleScope.launch {
                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        val profile = EnvironmentCollector(appContext)
                            .capture(origin = null, seed = System.currentTimeMillis())
                        val name = "capture-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
                        EnvironmentStore(appContext).save(name, profile)
                        Triple(name, profile.wifi.size, profile.cells.size)
                    }
                }
                if (_binding != null) binding.environmentCaptureButton.isEnabled = true
                result.onSuccess { (name, wifi, cells) ->
                    if (isAdded) showToast(getString(R.string.environment_capture_saved, name, wifi, cells))
                }.onFailure { error ->
                    if (isAdded) showToast(getString(R.string.environment_capture_failed, error.message ?: "unknown"))
                }
            }
        }
        fun assignPointToApps(location: SavedLocation) {
            val pm = requireContext().packageManager
            val apps = launchableApps()
            val labels = apps.map { pm.getApplicationLabel(it).toString() }.toTypedArray()
            val existing = runCatching {
                Json.decodeFromString(RoutingConfig.serializer(), context.routingJson)
            }.getOrNull() ?: RoutingConfig()
            val checked = BooleanArray(apps.size) { index ->
                existing.routes.any {
                    it.packageName.equals(apps[index].packageName, ignoreCase = true) && it.point == location.coordinate
                }
            }
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.per_app_point)
                .setMultiChoiceItems(labels, checked) { _, which, isChecked -> checked[which] = isChecked }
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    val selected = apps.filterIndexed { index, _ -> checked[index] }.map { it.packageName }.toSet()
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
                .setNegativeButton(android.R.string.cancel, null)
                .show()
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
            val pm = requireContext().packageManager
            val apps = launchableApps()
            val labels = apps.map { pm.getApplicationLabel(it).toString() }.toTypedArray()
            val existing = runCatching {
                Json.decodeFromString(RoutingConfig.serializer(), context.routingJson)
            }.getOrNull()
            val checked = BooleanArray(apps.size) { apps[it].packageName in (existing?.deniedPackages ?: emptySet()) }
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.per_app_routing_title)
                .setMultiChoiceItems(labels, checked) { _, which, isChecked -> checked[which] = isChecked }
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    val denied = apps.filterIndexed { index, _ -> checked[index] }.map { it.packageName }.toSet()
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
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
        binding.environmentImportButton.setOnClickListener {
            importEnvironmentDocument.launch(arrayOf("application/json", "text/plain", "*/*"))
        }
        fun apLabel() {
            binding.environmentApButton.text = getString(R.string.environment_ap_value, context.environmentApCount)
        }
        apLabel()
        binding.environmentApButton.setOnClickListener {
            showDialog(getString(R.string.environment_ap_title), context.environmentApCount.toString()) { value ->
                val count = value.toIntOrNull()
                if (count == null || count !in 1..8) {
                    showToast("AP 数需在 1 至 8 之间")
                    return@showDialog
                }
                context.environmentApCount = count
                apLabel()
                applyConfigNowWithToast()
            }
        }
        fun cellLabel() {
            binding.cellModeButton.text = getString(R.string.cell_mode_value, context.cellMode)
        }
        cellLabel()
        binding.cellModeButton.setOnClickListener {
            val modes = arrayOf("AUTO", "FULL_REPLAY", "NO_SIM_WIFI_ONLY", "LEGACY_CDMA")
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.cell_mode_title)
                .setSingleChoiceItems(modes, modes.indexOf(context.cellMode).coerceAtLeast(0)) { dialog, which ->
                    context.cellMode = modes[which]
                    cellLabel()
                    dialog.dismiss()
                    applyConfigNowWithToast()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
        fun sourceLabel() {
            binding.environmentSourceButton.text =
                getString(R.string.environment_source_value, context.environmentSourceMode)
        }
        sourceLabel()
        binding.environmentSourceButton.setOnClickListener {
            val modes = arrayOf("AUTO", "PROCEDURAL", "REPLAY")
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.environment_source_title)
                .setSingleChoiceItems(modes, modes.indexOf(context.environmentSourceMode).coerceAtLeast(0)) { dialog, which ->
                    context.environmentSourceMode = modes[which]
                    sourceLabel()
                    dialog.dismiss()
                    applyConfigNowWithToast()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
        binding.environmentCaptureButton.setOnClickListener {
            if (context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                showToast(getString(R.string.environment_capture_requires_location))
                return@setOnClickListener
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                context.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED
            ) {
                pendingEnvironmentCapture = { captureEnvironment() }
                bleScanPermission.launch(Manifest.permission.BLUETOOTH_SCAN)
                return@setOnClickListener
            }
            captureEnvironment()
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
        fun periodLabel() { binding.orbitPeriodButton.text = getString(R.string.orbit_period) + " · " + getString(R.string.orbit_period_value, context.orbitPeriodSeconds) }
        periodLabel()
        binding.orbitPeriodButton.setOnClickListener {
            showDialog(getString(R.string.orbit_period), context.orbitPeriodSeconds.toString()) {
                val value = it.toDoubleOrNull()
                if (value == null || !value.isFinite() || value !in 5.0..120.0) { showToast("周期需在5至120秒之间"); return@showDialog }
                context.orbitPeriodSeconds = value
                periodLabel()
                showToast("已保存，下次启动生效")
            }
        }
        binding.selinuxLayout.visibility = View.GONE
        binding.sensorHookLayout.visibility = View.GONE
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
        binding.orbitMotionSwitch.isChecked = context.experimentalOrbitMotion
        binding.orbitMotionSwitch.setOnCheckedChangeListener { _, enabled ->
            context.experimentalOrbitMotion = enabled
            showToast(if (enabled) "圆周运动实验已开启，下次启动场景生效" else "圆周运动实验已关闭")
        }
        binding.orbitRadiusValue.text = "%.2f米".format(context.experimentalOrbitRadius)
        binding.orbitRadiusLayout.setOnClickListener {
            showDialog("设置圆周半径（米）", "%.2f".format(context.experimentalOrbitRadius)) {
                val value = it.toDoubleOrNull()
                if (value == null || !value.isFinite() || value < 0.05 || value > 5.0) {
                    showToast("半径需在0.05至5米之间")
                    return@showDialog
                }
                context.experimentalOrbitRadius = value
                binding.orbitRadiusValue.text = "%.2f米".format(value)
                showToast("圆周半径已保存，下次启动场景生效")
            }
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
