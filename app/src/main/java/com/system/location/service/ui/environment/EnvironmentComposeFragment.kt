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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.system.location.service.R
import com.system.location.service.core.environment.EnvironmentProfile
import com.system.location.service.core.environment.EnvironmentProfileCodec
import com.system.location.service.core.environment.WifiFingerprintImporter
import com.system.location.service.designsystem.components.PortalActionRow
import com.system.location.service.designsystem.components.PortalCard
import com.system.location.service.designsystem.components.PortalSectionTitle
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
    private val names = MutableStateFlow<List<String>>(emptyList())
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
                val environmentNames = names.collectAsStateWithLifecycle()
                EnvironmentScreen(
                    names = environmentNames.value,
                    onCapture = ::requestEnvironmentCapture,
                    onImport = { importEnvironmentDocument.launch(arrayOf("application/json", "text/plain", "*/*")) },
                    onImportFingerprint = { importWifiFingerprintDocument.launch(arrayOf("application/json", "text/plain", "*/*")) },
                    onApplyConfig = ::applyConfigNowWithToast,
                    onExport = ::exportEnvironment,
                    onReplay = ::replayEnvironment,
                    onDelete = ::deleteEnvironment,
                    onRename = ::renameEnvironment,
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
        lifecycleScope.launch {
            names.value = withContext(Dispatchers.IO) { EnvironmentStore(appContext).list() }
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
        }
    }

    private fun deleteEnvironment(name: String) {
        val appContext = requireContext().applicationContext
        lifecycleScope.launch {
            val deleted = withContext(Dispatchers.IO) {
                EnvironmentStore(appContext).delete(name)
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
    names: List<String>,
    onCapture: () -> Unit,
    onImport: () -> Unit,
    onImportFingerprint: () -> Unit,
    onApplyConfig: () -> Unit,
    onExport: (String) -> Unit,
    onReplay: (String) -> Unit,
    onDelete: (String) -> Unit,
    onRename: (String, String) -> Unit,
) {
    val context = LocalContext.current
    val revision = preferenceRevision()
    val apCount = remember(revision) { context.environmentApCount }
    val cellMode = remember(revision) { context.cellMode }
    val sourceMode = remember(revision) { context.environmentSourceMode }
    val fingerprintEnabled = remember(revision) { context.wifiFingerprintEnabled }
    var apDialog by remember { mutableStateOf(false) }
    var apInput by remember { mutableStateOf(apCount.toString()) }
    var cellDialog by remember { mutableStateOf(false) }
    var sourceDialog by remember { mutableStateOf(false) }
    var listDialog by remember { mutableStateOf(false) }
    var search by remember { mutableStateOf("") }
    var renameTarget by remember { mutableStateOf<String?>(null) }
    var renameInput by remember { mutableStateOf("") }
    var actionTarget by remember { mutableStateOf<String?>(null) }

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
        onSelect = {
            context.environmentSourceMode = it
            sourceDialog = false
            apply()
        },
        onDismiss = { sourceDialog = false },
    )

    if (listDialog) {
        AlertDialog(
            onDismissRequest = { listDialog = false },
            title = { Text(stringResource(R.string.environment_list_title)) },
            text = {
                Column {
                    OutlinedTextField(
                        value = search,
                        onValueChange = { search = it },
                        singleLine = true,
                        label = { Text(stringResource(R.string.environment_search_hint)) },
                    )
                    Spacer(Modifier.height(PortalSpacing.sm))
                    val filtered = names.filter { it.contains(search, ignoreCase = true) }
                    if (filtered.isEmpty()) {
                        Text(stringResource(R.string.environment_list_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        LazyColumn(Modifier.height(300.dp)) {
                            filtered.forEach { name ->
                                item {
                                    PortalActionRow(
                                        title = name,
                                        onClick = {
                                            listDialog = false
                                            actionTarget = name
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { listDialog = false }) { Text("关闭") } },
        )
    }

    val action = actionTarget
    if (action != null) {
        AlertDialog(
            onDismissRequest = { actionTarget = null },
            title = { Text(action) },
            text = {
                Column {
                    PortalActionRow(stringResource(R.string.environment_export), onClick = { actionTarget = null; onExport(action) })
                    PortalActionRow(stringResource(R.string.environment_replay), onClick = { actionTarget = null; onReplay(action) })
                    PortalActionRow(stringResource(R.string.environment_rename), onClick = {
                        actionTarget = null
                        renameTarget = action
                        renameInput = action
                    })
                    PortalActionRow(stringResource(R.string.environment_delete), onClick = {
                        actionTarget = null
                        onDelete(action)
                    })
                }
            },
            confirmButton = { TextButton(onClick = { actionTarget = null }) { Text("取消") } },
        )
    }

    val renaming = renameTarget
    if (renaming != null) {
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text(stringResource(R.string.environment_rename)) },
            text = {
                OutlinedTextField(
                    value = renameInput,
                    onValueChange = { renameInput = it },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val next = renameInput.trim()
                    renameTarget = null
                    if (next.isNotEmpty()) onRename(renaming, next)
                }) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { renameTarget = null }) { Text("取消") } },
        )
    }

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
                PortalCard {
                    PortalSectionTitle(stringResource(R.string.environment_capture_title))
                    Spacer(Modifier.height(PortalSpacing.sm))
                    Button(onClick = onCapture, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.environment_capture_action))
                    }
                    Spacer(Modifier.height(PortalSpacing.sm))
                    OutlinedButton(
                        onClick = { listDialog = true },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.environment_list_action)) }
                    Spacer(Modifier.height(PortalSpacing.sm))
                    OutlinedButton(onClick = onImport, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.environment_import_action))
                    }
                }
            }
            item {
                PortalCard {
                    PortalSectionTitle(stringResource(R.string.settings_environment_fingerprint))
                    PortalSwitchRow(
                        title = stringResource(R.string.wifi_fingerprint_enable),
                        checked = fingerprintEnabled,
                        onCheckedChange = {
                            context.wifiFingerprintEnabled = it
                            onApplyConfig()
                        },
                    )
                    OutlinedButton(onClick = onImportFingerprint, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.wifi_fingerprint_import))
                    }
                }
            }
            item {
                PortalCard {
                    PortalSectionTitle(stringResource(R.string.settings_environment_replay))
                    PortalActionRow(
                        title = stringResource(R.string.environment_ap_value, apCount),
                        onClick = {
                            apInput = apCount.toString()
                            apDialog = true
                        },
                    )
                    PortalActionRow(
                        title = stringResource(R.string.cell_mode_value, cellMode),
                        onClick = { cellDialog = true },
                    )
                    PortalActionRow(
                        title = stringResource(R.string.environment_source_value, sourceMode),
                        onClick = { sourceDialog = true },
                    )
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
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = option == selected, onClick = { onSelect(option) })
                        Text(option)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
