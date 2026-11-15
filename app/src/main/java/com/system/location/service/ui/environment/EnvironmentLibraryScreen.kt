package com.system.location.service.ui.environment

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.system.location.service.R
import com.system.location.service.core.environment.BleBeacon
import com.system.location.service.core.environment.CellTower
import com.system.location.service.core.environment.CellType
import com.system.location.service.core.environment.CdmaTower
import com.system.location.service.core.environment.EnvironmentProfile
import com.system.location.service.core.environment.WifiAp
import com.system.location.service.designsystem.components.PortalActionRow
import com.system.location.service.designsystem.components.PortalSettingGroup

internal data class EnvironmentLibraryState(
    val names: List<String> = emptyList(),
    val loading: Boolean = true,
    val error: String? = null,
    val editing: Pair<String, EnvironmentProfile>? = null,
    val editingKind: EnvironmentDataKind = EnvironmentDataKind.BLE,
)


private const val ADD_NEW_ITEM = -1

@Composable
internal fun EnvironmentLibraryScreen(
    state: EnvironmentLibraryState,
    onRetry: () -> Unit,
    onExport: (String) -> Unit,
    onReplay: (String) -> Unit,
    onDelete: (String) -> Unit,
    onRename: (String, String) -> Unit,
    onEditProfile: (String, EnvironmentDataKind) -> Unit,
    onSaveProfile: (String, EnvironmentProfile) -> Unit,
    onCloseProfileEditor: () -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var action by rememberSaveable { mutableStateOf<String?>(null) }
    var deleting by rememberSaveable { mutableStateOf<String?>(null) }
    var renaming by rememberSaveable { mutableStateOf<String?>(null) }
    var nameInput by rememberSaveable { mutableStateOf("") }
    val visible = remember(state.names, query) { state.names.filter { it.contains(query, true) } }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(query, { query = it }, label = { Text("搜索已保存环境") },
                singleLine = true, modifier = Modifier.fillMaxWidth())
            Text(when {
                state.loading -> "正在读取环境列表"
                state.error != null -> "列表读取失败"
                else -> "${state.names.size} 个环境 · 选择后可回放、导出、重命名或删除"
            },
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when {
                    state.loading -> item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                    state.error != null -> item {
                        Text(state.error, color = MaterialTheme.colorScheme.error)
                        TextButton(onRetry) { Text("重新加载") }
                    }
                    state.names.isEmpty() -> item {
                        Icon(painterResource(R.drawable.ic_portal_info), contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(24.dp))
                        Text("还没有保存环境")
                        Text("返回环境工具，采集当前环境或从文件导入。", style = MaterialTheme.typography.bodyMedium)
                    }
                    visible.isEmpty() -> item {
                        Icon(painterResource(R.drawable.ic_portal_info), contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(24.dp))
                        Text("没有符合条件的环境")
                    }
                    else -> items(visible, key = { it }) { name ->
                        PortalActionRow(name, onClick = { action = name })
                    }
                }
            }
        }
    }
    action?.let { name ->
        AlertDialog(onDismissRequest = { action = null }, title = { Text(name) }, text = {
            PortalSettingGroup("环境操作", 8) { index, modifier ->
                when (index) {
                    0 -> PortalActionRow("设置为回放环境", onClick = { action = null; onReplay(name) }, modifier = modifier)
                    1 -> PortalActionRow("导出环境 JSON", onClick = { action = null; onExport(name) }, modifier = modifier)
                    2 -> PortalActionRow("维护 BLE 信标", onClick = { action = null; onEditProfile(name, EnvironmentDataKind.BLE) }, modifier = modifier)
                    3 -> PortalActionRow("维护 Wi-Fi AP", onClick = { action = null; onEditProfile(name, EnvironmentDataKind.WIFI) }, modifier = modifier)
                    4 -> PortalActionRow("维护蜂窝小区", onClick = { action = null; onEditProfile(name, EnvironmentDataKind.CELL) }, modifier = modifier)
                    5 -> PortalActionRow("维护 CDMA 小区", onClick = { action = null; onEditProfile(name, EnvironmentDataKind.CDMA) }, modifier = modifier)
                    6 -> PortalActionRow("重命名", onClick = { action = null; renaming = name; nameInput = name }, modifier = modifier)
                    7 -> PortalActionRow("删除", onClick = { action = null; deleting = name }, modifier = modifier,
                        leadingContent = {
                            Icon(painterResource(R.drawable.ic_portal_delete), contentDescription = null,
                                tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(24.dp))
                        })
                }
            }
        }, confirmButton = { TextButton({ action = null }) { Text("取消") } })
    }
    deleting?.let { name ->
        AlertDialog(onDismissRequest = { deleting = null }, title = { Text("删除环境？") },
            text = { Text("将删除“$name”，此操作无法撤销。已设置的回放配置会保留。") },
            confirmButton = { TextButton({ deleting = null; onDelete(name) },
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("确认删除") } },
            dismissButton = { TextButton({ deleting = null }) { Text("取消") } })
    }
    renaming?.let { name ->
        AlertDialog(onDismissRequest = { renaming = null }, title = { Text("重命名环境") }, text = {
            OutlinedTextField(nameInput, { nameInput = it }, singleLine = true, label = { Text("环境名称") },
                supportingText = { Text("最多 64 个字母、数字或 . _ -；其他字符将替换为下划线") })
        }, confirmButton = { TextButton({ renaming = null; onRename(name, nameInput.trim()) },
            enabled = nameInput.isNotBlank() && nameInput.trim() != name) { Text("保存") } },
            dismissButton = { TextButton({ renaming = null }) { Text("取消") } })
    }
    state.editing?.let { (name, profile) ->
        when (state.editingKind) {
            EnvironmentDataKind.BLE -> EnvironmentBleEditorDialog(
                name = name, profile = profile, onDismiss = onCloseProfileEditor,
                onSave = { onSaveProfile(name, it) },
            )
            EnvironmentDataKind.WIFI -> EnvironmentWifiEditorDialog(
                name = name, profile = profile, onDismiss = onCloseProfileEditor,
                onSave = { onSaveProfile(name, it) },
            )
            EnvironmentDataKind.CELL -> EnvironmentCellEditorDialog(
                name = name, profile = profile, onDismiss = onCloseProfileEditor,
                onSave = { onSaveProfile(name, it) },
            )
            EnvironmentDataKind.CDMA -> EnvironmentCdmaEditorDialog(
                name = name, profile = profile, onDismiss = onCloseProfileEditor,
                onSave = { onSaveProfile(name, it) },
            )
        }
    }
}
@Composable
private fun EnvironmentBleEditorDialog(
    name: String,
    profile: EnvironmentProfile,
    onDismiss: () -> Unit,
    onSave: (EnvironmentProfile) -> Unit,
) {
    var edited by remember(name) { mutableStateOf(profile) }
    var editIndex by remember { mutableStateOf<Int?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("$name · BLE 信标") },
        text = {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${edited.ble.size} / ${EnvironmentProfile.MAX_BLE} 个信标")
                LazyColumn(Modifier.heightIn(max = 360.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(edited.ble.size) { index ->
                        val beacon = edited.ble[index]
                        Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            Text("${beacon.name.ifBlank { "BLE 信标" }} · ${beacon.address}", Modifier.weight(1f),
                                style = MaterialTheme.typography.bodyMedium)
                            TextButton(onClick = { editIndex = index }) { Text("编辑") }
                            IconButton(onClick = {
                                edited = edited.copy(ble = edited.ble.filterIndexed { item, _ -> item != index })
                            }) {
                                Icon(painterResource(R.drawable.ic_portal_delete), contentDescription = "删除信标")
                            }
                        }
                    }
                }
                TextButton(onClick = { editIndex = ADD_NEW_ITEM }, enabled = edited.ble.size < EnvironmentProfile.MAX_BLE) {
                    Text("新增信标")
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = { TextButton({ onSave(edited) }) { Text("保存") } },
        dismissButton = { TextButton(onDismiss) { Text("取消") } },
    )
    editIndex?.let { index ->
        key(index) {
            val existing = edited.ble.getOrNull(index)
            EnvironmentBleBeaconPage(
                initial = existing,
                onDismiss = { editIndex = null },
                onSave = { beacon ->
                    edited = edited.copy(ble = if (existing == null) edited.ble + beacon
                        else edited.ble.mapIndexed { item, value -> if (item == index) beacon else value })
                    editIndex = null
                    error = null
                },
                onError = { error = it },
            )
        }
    }
}


@Composable
private fun EnvironmentWifiEditorDialog(
    name: String,
    profile: EnvironmentProfile,
    onDismiss: () -> Unit,
    onSave: (EnvironmentProfile) -> Unit,
) {
    var edited by remember(name) { mutableStateOf(profile) }
    var editIndex by remember { mutableStateOf<Int?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("$name · Wi-Fi AP") },
        text = {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${edited.wifi.size} / ${EnvironmentProfile.MAX_WIFI} 个 AP")
                LazyColumn(Modifier.heightIn(max = 360.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(edited.wifi.size) { index ->
                        val ap = edited.wifi[index]
                        Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            Text("${ap.ssid} · ${ap.bssid}", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                            TextButton(onClick = { editIndex = index }) { Text("编辑") }
                            IconButton(onClick = {
                                edited = edited.copy(wifi = edited.wifi.filterIndexed { item, _ -> item != index })
                            }) {
                                Icon(painterResource(R.drawable.ic_portal_delete), contentDescription = "删除 Wi-Fi AP")
                            }
                        }
                    }
                }
                TextButton(onClick = { editIndex = ADD_NEW_ITEM }, enabled = edited.wifi.size < EnvironmentProfile.MAX_WIFI) {
                    Text("新增 AP")
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = { TextButton({ onSave(edited) }) { Text("保存") } },
        dismissButton = { TextButton(onDismiss) { Text("取消") } },
    )
    editIndex?.let { index ->
        key(index) {
            val existing = edited.wifi.getOrNull(index)
            EnvironmentWifiApPage(
                initial = existing,
                onDismiss = { editIndex = null },
                onSave = { ap ->
                    val updated = if (existing == null) edited.wifi + ap
                        else edited.wifi.mapIndexed { item, value -> if (item == index) ap else value }
                    val selectedIndex = if (existing == null) updated.lastIndex else index
                    edited = edited.copy(wifi = if (ap.connected) updated.mapIndexed { item, value ->
                        if (item == selectedIndex) value else value.copy(connected = false)
                    } else updated)
                    editIndex = null
                    error = null
                },
                onError = { error = it },
            )
        }
    }
}


@Composable
private fun EnvironmentCellEditorDialog(
    name: String,
    profile: EnvironmentProfile,
    onDismiss: () -> Unit,
    onSave: (EnvironmentProfile) -> Unit,
) {
    var edited by remember(name) { mutableStateOf(profile) }
    var editIndex by remember { mutableStateOf<Int?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("$name · 蜂窝小区") },
        text = {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${edited.cells.size + edited.cdmaCells.size} / ${EnvironmentProfile.MAX_CELLS} 个小区")
                LazyColumn(Modifier.heightIn(max = 360.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(edited.cells.size) { index ->
                        val cell = edited.cells[index]
                        Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            Text("${cell.type} · ${cell.operatorNumeric ?: "${cell.mcc}-${cell.mnc} (MNC 位数未知)"} · ${cell.cellId}", Modifier.weight(1f),
                                style = MaterialTheme.typography.bodyMedium)
                            TextButton(onClick = { editIndex = index }) { Text("编辑") }
                            IconButton(onClick = {
                                edited = edited.copy(cells = edited.cells.filterIndexed { item, _ -> item != index })
                            }) {
                                Icon(painterResource(R.drawable.ic_portal_delete), contentDescription = "删除小区")
                            }
                        }
                    }
                }
                TextButton(onClick = { editIndex = ADD_NEW_ITEM },
                    enabled = edited.cells.size + edited.cdmaCells.size < EnvironmentProfile.MAX_CELLS) {
                    Text("新增小区")
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = { TextButton({ onSave(edited) }) { Text("保存") } },
        dismissButton = { TextButton(onDismiss) { Text("取消") } },
    )
    editIndex?.let { index ->
        key(index) {
            val existing = edited.cells.getOrNull(index)
            EnvironmentCellTowerPage(
                initial = existing,
                onDismiss = { editIndex = null },
                onSave = { cell ->
                    val updated = if (existing == null) edited.cells + cell
                        else edited.cells.mapIndexed { item, value -> if (item == index) cell else value }
                    val selectedIndex = if (existing == null) updated.lastIndex else index
                    edited = edited.copy(
                        cells = if (cell.registered) updated.mapIndexed { item, value ->
                            if (item == selectedIndex) value else value.copy(registered = false)
                        } else updated,
                        cdmaCells = if (cell.registered) edited.cdmaCells.map { it.copy(registered = false) }
                            else edited.cdmaCells,
                    )
                    editIndex = null
                    error = null
                },
                onError = { error = it },
            )
        }
    }
}

@Composable
private fun EnvironmentCdmaEditorDialog(
    name: String,
    profile: EnvironmentProfile,
    onDismiss: () -> Unit,
    onSave: (EnvironmentProfile) -> Unit,
) {
    var edited by remember(name) { mutableStateOf(profile) }
    var editIndex by remember { mutableStateOf<Int?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("$name · CDMA 小区") },
        text = {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${edited.cells.size + edited.cdmaCells.size} / ${EnvironmentProfile.MAX_CELLS} 个小区")
                LazyColumn(Modifier.heightIn(max = 360.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(edited.cdmaCells.size) { index ->
                        val cell = edited.cdmaCells[index]
                        Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            Text("SID ${cell.systemId} · NID ${cell.networkId} · BID ${cell.baseStationId}",
                                Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                            TextButton(onClick = { editIndex = index }) { Text("编辑") }
                            IconButton(onClick = {
                                edited = edited.copy(cdmaCells = edited.cdmaCells.filterIndexed { item, _ -> item != index })
                            }) {
                                Icon(painterResource(R.drawable.ic_portal_delete), contentDescription = "删除 CDMA 小区")
                            }
                        }
                    }
                }
                TextButton(onClick = { editIndex = ADD_NEW_ITEM },
                    enabled = edited.cells.size + edited.cdmaCells.size < EnvironmentProfile.MAX_CELLS) {
                    Text("新增 CDMA 小区")
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = { TextButton({ onSave(edited) }) { Text("保存") } },
        dismissButton = { TextButton(onDismiss) { Text("取消") } },
    )
    editIndex?.let { index ->
        key(index) {
            val existing = edited.cdmaCells.getOrNull(index)
            EnvironmentCdmaTowerPage(
                initial = existing,
                onDismiss = { editIndex = null },
                onSave = { cell ->
                    val updated = if (existing == null) edited.cdmaCells + cell
                        else edited.cdmaCells.mapIndexed { item, value -> if (item == index) cell else value }
                    val selectedIndex = if (existing == null) updated.lastIndex else index
                    edited = edited.copy(
                        cells = if (cell.registered) edited.cells.map { it.copy(registered = false) } else edited.cells,
                        cdmaCells = if (cell.registered) updated.mapIndexed { item, value ->
                            if (item == selectedIndex) value else value.copy(registered = false)
                        } else updated,
                    )
                    editIndex = null
                    error = null
                },
                onError = { error = it },
            )
        }
    }
}
