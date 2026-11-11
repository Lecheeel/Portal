package com.system.location.service.ui.environment

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
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
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.semantics.Role
import androidx.compose.runtime.LaunchedEffect
import com.system.location.service.ui.preferenceRevision
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.system.location.service.R
import com.system.location.service.core.environment.EnvironmentProfile
import com.system.location.service.core.environment.EnvironmentProfileCodec
import com.system.location.service.core.environment.WifiFingerprintImporter
import com.system.location.service.designsystem.components.PortalActionRow
import com.system.location.service.designsystem.components.PortalSettingGroup
import com.system.location.service.designsystem.components.PortalSwitchRow
import com.system.location.service.designsystem.theme.PortalSpacing
import com.system.location.service.designsystem.theme.PortalTheme
import com.system.location.service.environment.EnvironmentCollector
import com.system.location.service.environment.EnvironmentStore
import com.system.location.service.ext.cellMode
import com.system.location.service.ext.environmentApCount
import com.system.location.service.ext.environmentReplayJson
import com.system.location.service.ext.environmentSourceMode
import com.system.location.service.ext.wifiFingerprintCatalogJson
import com.system.location.service.ext.wifiFingerprintEnabled
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Compose replacement for the environment tools leaf screen.
 *
 * SAF launchers stay in the Fragment and Compose owns presentation/state.
 */
class EnvironmentComposeFragment : Fragment() {
    private val library = MutableStateFlow(EnvironmentLibraryState())
    private var libraryJob: Job? = null
    private var pendingExport: Pair<String, EnvironmentProfile>? = null

    private val locationPermission = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        if (grants[Manifest.permission.ACCESS_FINE_LOCATION] == true) requestEnvironmentCapture()
        else showToast(getString(R.string.environment_capture_requires_location))
    }

    private val bleScanPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (!granted) showToast(getString(R.string.environment_ble_permission_denied))
        captureEnvironment()
    }

    private val createEnvironmentDocument = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        val pending = pendingExport
        pendingExport = null
        if (uri == null || pending == null) return@registerForActivityResult
        runCatching {
            requireContext().contentResolver.openOutputStream(uri)?.use { stream ->
                stream.write(EnvironmentProfileCodec.encode(pending.second).toByteArray(Charsets.UTF_8))
            } ?: error("无法写入文件")
        }.onSuccess {
            showToast(getString(R.string.environment_export_success))
        }.onFailure { error ->
            showToast(getString(R.string.environment_export_failed, error.message ?: "unknown"))
        }
    }

    private val importEnvironmentDocument = registerForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
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
                refreshNames()
                showToast(getString(R.string.environment_import_success, name))
            }.onFailure { error ->
                showToast(getString(R.string.environment_import_failed, error.message ?: "unknown"))
            }
        }
    }

    private val importWifiFingerprintDocument = registerForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        val appContext = requireContext().applicationContext
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val raw = appContext.contentResolver.openInputStream(uri)
                        ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                        ?: error("无法读取文件")
                    val imported = WifiFingerprintImporter.importJson(raw)
                    appContext.wifiFingerprintCatalogJson =
                        WifiFingerprintImporter.encodeJson(imported.catalog)
                    imported
                }
            }
            result.onSuccess { imported ->
                showToast(getString(R.string.settings_wifi_imported, imported.imported))
            }.onFailure { error ->
                showToast(getString(R.string.settings_wifi_import_failed, error.message ?: "unknown"))
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
                val state by library.collectAsStateWithLifecycle()
                if (arguments?.getBoolean("library") == true) {
                    EnvironmentLibraryScreen(state, ::refreshNames, ::exportEnvironment,
                        ::replayEnvironment, ::deleteEnvironment, ::renameEnvironment)
                } else EnvironmentScreen(
                    library = state,
                    onOpenLibrary = { findNavController().navigate(R.id.nav_environment_library) },
                    onCapture = ::requestEnvironmentCapture,
                    onImport = { importEnvironmentDocument.launch(arrayOf("application/json", "text/plain", "*/*")) },
                    onImportFingerprint = { importWifiFingerprintDocument.launch(arrayOf("application/json", "text/plain", "*/*")) },
                    onApplyConfig = ::applyConfigNowWithToast,
                )
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        refreshNames()
    }

    private fun refreshNames() {
        val appContext = requireContext().applicationContext
        libraryJob?.cancel()
        libraryJob = lifecycleScope.launch {
            library.value = library.value.copy(loading = true, error = null)
            runCatching { withContext(Dispatchers.IO) { EnvironmentStore(appContext).list() } }
                .onSuccess { library.value = EnvironmentLibraryState(it, loading = false) }
                .onFailure {
                    if (it is CancellationException) throw it
                    library.value = EnvironmentLibraryState(loading = false, error = "无法读取环境列表，原有文件未修改")
                }
        }
    }

    private fun requestEnvironmentCapture() {
        val context = requireContext()
        if (context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            locationPermission.launch(arrayOf(
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.ACCESS_FINE_LOCATION,
            ))
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            context.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED
        ) {
            bleScanPermission.launch(Manifest.permission.BLUETOOTH_SCAN)
            return
        }
        captureEnvironment()
    }

    private fun captureEnvironment() {
        val appContext = requireContext().applicationContext
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val profile = EnvironmentCollector(appContext).capture(origin = null, seed = System.currentTimeMillis())
                    val name = "capture-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
                    EnvironmentStore(appContext).save(name, profile)
                    Triple(name, profile.wifi.size, profile.cells.size)
                }
            }
            result.onSuccess { (name, wifi, cells) ->
                refreshNames()
                showToast(getString(R.string.environment_capture_saved, name, wifi, cells))
            }.onFailure { error ->
                showToast(getString(R.string.environment_capture_failed, error.message ?: "unknown"))
            }
        }
    }

    private fun exportEnvironment(name: String) {
        val appContext = requireContext().applicationContext
        lifecycleScope.launch {
            val profile = withContext(Dispatchers.IO) {
                runCatching { EnvironmentStore(appContext).load(name) }.getOrNull()
            }
            if (profile == null) {
                showToast(getString(R.string.environment_export_failed, "无法读取环境"))
                return@launch
            }
            pendingExport = name to profile
            createEnvironmentDocument.launch("$name.json")
        }
    }

    private fun replayEnvironment(name: String) {
        val appContext = requireContext().applicationContext
        lifecycleScope.launch {
            val profile = withContext(Dispatchers.IO) {
                runCatching { EnvironmentStore(appContext).load(name) }.getOrNull()
            }
            if (profile == null) {
                showToast(getString(R.string.environment_export_failed, "无法读取环境"))
                return@launch
            }
            appContext.environmentReplayJson = EnvironmentProfileCodec.encode(profile)
            appContext.environmentSourceMode = "REPLAY"
            val applied = withContext(Dispatchers.IO) {
                com.system.location.service.runtime.ScenarioRuntime.applyConfigNow()
            }
            showToast(if (applied) "回放环境已应用" else getString(R.string.environment_replay_set))
            if (isAdded && arguments?.getBoolean("library") == true) findNavController().popBackStack()
        }
    }

    private fun deleteEnvironment(name: String) {
        val appContext = requireContext().applicationContext
        lifecycleScope.launch {
            val deleted = withContext(Dispatchers.IO) {
                runCatching { EnvironmentStore(appContext).delete(name) }.getOrDefault(false)
            }
            refreshNames()
            showToast(
                if (deleted) getString(R.string.environment_deleted)
                else getString(R.string.environment_delete_failed),
            )
        }
    }

    private fun renameEnvironment(name: String, nextName: String) {
        val appContext = requireContext().applicationContext
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { EnvironmentStore(appContext).rename(name, nextName.trim()) }
            }
            refreshNames()
            result.onSuccess { showToast(getString(R.string.environment_renamed)) }
                .onFailure { showToast(getString(R.string.environment_rename_failed, it.message ?: "unknown")) }
        }
    }

    private fun applyConfigNowWithToast() {
        lifecycleScope.launch {
            val applied = withContext(Dispatchers.IO) {
                com.system.location.service.runtime.ScenarioRuntime.applyConfigNow()
            }
            showToast(if (applied) "配置已应用" else "已保存，下次启动场景生效")
        }
    }

    private fun showToast(message: String) {
        if (isAdded) Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
    }
}

@Composable
private fun EnvironmentScreen(
    library: EnvironmentLibraryState,
    onOpenLibrary: () -> Unit,
    onCapture: () -> Unit,
    onImport: () -> Unit,
    onImportFingerprint: () -> Unit,
    onApplyConfig: () -> Unit,
) {
    val context = LocalContext.current
    val revision = preferenceRevision()
    val apCount = remember(revision) { context.environmentApCount }
    val cellMode = remember(revision) { context.cellMode }
    val sourceMode = remember(revision) { context.environmentSourceMode }
    val fingerprintEnabled = remember(revision) { context.wifiFingerprintEnabled }
    var apDialog by rememberSaveable { mutableStateOf(false) }
    var apInput by rememberSaveable { mutableStateOf(apCount.toString()) }
    var cellDialog by rememberSaveable { mutableStateOf(false) }
    var sourceDialog by rememberSaveable { mutableStateOf(false) }

    fun apply() {
        onApplyConfig()
    }

    if (apDialog) {
        val apRangeMessage = stringResource(R.string.settings_environment_ap_range)
        AlertDialog(
            onDismissRequest = { apDialog = false },
            title = { Text(stringResource(R.string.environment_ap_title)) },
            text = {
                OutlinedTextField(
                    value = apInput,
                    onValueChange = { apInput = it.filter(Char::isDigit).take(1) },
                    singleLine = true,
                    label = { Text("3–8") },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val count = apInput.toIntOrNull()
                    if (count == null || count !in 3..8) {
                        Toast.makeText(context, apRangeMessage, Toast.LENGTH_SHORT).show()
                    } else {
                        context.environmentApCount = count
                        apDialog = false
                        apply()
                    }
                }) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { apDialog = false }) { Text("取消") } },
        )
    }

    ChoiceDialog(
        visible = cellDialog,
        title = stringResource(R.string.cell_mode_title),
        options = listOf("AUTO", "FULL_REPLAY", "NO_SIM_WIFI_ONLY", "LEGACY_CDMA"),
        selected = cellMode,
        label = ::cellModeLabel,
        onSelect = {
            context.cellMode = it
            cellDialog = false
            apply()
        },
        onDismiss = { cellDialog = false },
    )
    ChoiceDialog(
        visible = sourceDialog,
        title = stringResource(R.string.environment_source_title),
        options = listOf("AUTO", "PROCEDURAL", "REPLAY"),
        selected = sourceMode,
        label = ::environmentSourceLabel,
        onSelect = {
            context.environmentSourceMode = it
            sourceDialog = false
            apply()
        },
        onDismiss = { sourceDialog = false },
    )

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        LazyColumn(
            contentPadding = PaddingValues(20.dp, 24.dp, 20.dp, 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                Text(
                    stringResource(R.string.environment_capture_desc),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item {
                PortalSettingGroup(stringResource(R.string.environment_capture_title), 3) { index, rowModifier ->
                    when (index) {
                        0 -> PortalActionRow(stringResource(R.string.environment_capture_action),
                            "采集手机当前的无线环境，保存为独立配置", onCapture, modifier = rowModifier)
                        1 -> PortalActionRow(stringResource(R.string.environment_list_action),
                            when {
                                library.loading -> "正在读取环境列表"
                                library.error != null -> "列表读取失败，进入后可重试"
                                else -> "共 ${library.names.size} 个环境；可回放、导出、重命名或删除"
                            },
                            onOpenLibrary, modifier = rowModifier)
                        2 -> PortalActionRow(stringResource(R.string.environment_import_action),
                            "从文件导入环境配置", onImport, modifier = rowModifier)
                    }
                }
            }
            item {
                PortalSettingGroup(stringResource(R.string.settings_environment_fingerprint), 2) { index, rowModifier ->
                    when (index) {
                        0 -> PortalSwitchRow(
                            title = stringResource(R.string.wifi_fingerprint_enable),
                            subtitle = "仅 Xposed / Native 模拟输出有效",
                            status = "运行中尝试立即应用",
                            checked = fingerprintEnabled,
                            onCheckedChange = {
                                context.wifiFingerprintEnabled = it
                                onApplyConfig()
                            },
                            modifier = rowModifier,
                        )
                        1 -> PortalActionRow(stringResource(R.string.wifi_fingerprint_import),
                            "从文件导入 Wi-Fi 指纹库", onImportFingerprint, modifier = rowModifier)
                    }
                }
            }
            item {
                PortalSettingGroup(stringResource(R.string.settings_environment_replay), 3) { index, rowModifier ->
                    when (index) {
                        0 -> PortalActionRow(
                            title = stringResource(R.string.environment_ap_value, apCount),
                            status = "运行中尝试立即应用",
                            onClick = { apInput = apCount.toString(); apDialog = true },
                            modifier = rowModifier,
                        )
                        1 -> PortalActionRow(
                            title = stringResource(R.string.cell_mode_value, cellModeLabel(cellMode)),
                            status = "运行中尝试立即应用",
                            onClick = { cellDialog = true },
                            modifier = rowModifier,
                        )
                        2 -> PortalActionRow(
                            title = stringResource(R.string.environment_source_value, environmentSourceLabel(sourceMode)),
                            status = "运行中尝试立即应用",
                            onClick = { sourceDialog = true },
                            modifier = rowModifier,
                        )
                    }
                }
            }
            item {
                Text(
                    "环境回放和 WiFi 指纹属于实验能力，失败时会回退到程序化环境。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ChoiceDialog(
    visible: Boolean,
    title: String,
    options: List<String>,
    selected: String,
    label: (String) -> String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    if (!visible) return
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                options.forEach { option ->
                    Row(
                        modifier = Modifier.fillMaxWidth().selectable(
                            selected = option == selected, role = Role.RadioButton,
                            onClick = { onSelect(option) },
                        ).padding(vertical = 8.dp),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = option == selected, onClick = null)
                        Text(label(option), Modifier.padding(start = 8.dp))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

private fun cellModeLabel(value: String) = when (value) {
    "AUTO" -> "自动选择"
    "FULL_REPLAY" -> "完整回放"
    "NO_SIM_WIFI_ONLY" -> "无 SIM / 仅 Wi-Fi"
    "LEGACY_CDMA" -> "CDMA 网络"
    else -> "未知模式（$value）"
}

private fun environmentSourceLabel(value: String) = when (value) {
    "AUTO" -> "自动选择"
    "PROCEDURAL" -> "按位置生成"
    "REPLAY" -> "已采集环境回放"
    else -> "未知来源（$value）"
}
