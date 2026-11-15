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

internal enum class EnvironmentDataKind { BLE, WIFI, CELL, CDMA }

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
            EnvironmentBleBeaconDialog(
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
private fun EnvironmentBleBeaconDialog(
    initial: BleBeacon?,
    onDismiss: () -> Unit,
    onSave: (BleBeacon) -> Unit,
    onError: (String) -> Unit,
) {
    var address by rememberSaveable(initial?.address) { mutableStateOf(initial?.address.orEmpty()) }
    var name by rememberSaveable(initial?.address) { mutableStateOf(initial?.name.orEmpty()) }
    var rssi by rememberSaveable(initial?.address) { mutableStateOf(initial?.rssi?.toString() ?: "-65") }
    var scanRecord by rememberSaveable(initial?.address) { mutableStateOf(initial?.scanRecordHex.orEmpty()) }
    var txPower by rememberSaveable(initial?.address) { mutableStateOf(initial?.txPowerDbm?.toString().orEmpty()) }
    var validationError by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "新增 BLE 信标" else "编辑 BLE 信标") },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 440.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    item { OutlinedTextField(address, { address = it }, label = { Text("地址") }, singleLine = true) }
                    item { OutlinedTextField(name, { name = it }, label = { Text("广播名称") }, singleLine = true) }
                    item { OutlinedTextField(rssi, { rssi = it }, label = { Text("RSSI (dBm)") }, singleLine = true) }
                    item { OutlinedTextField(scanRecord, { scanRecord = it }, label = { Text("广播数据 Hex（可选）") }) }
                    item { OutlinedTextField(txPower, { txPower = it }, label = { Text("Tx Power dBm（可选）") }, singleLine = true) }
                    validationError?.let { message -> item { Text(message, color = MaterialTheme.colorScheme.error) } }
                }
            }
        },
        confirmButton = {
            TextButton({
                runCatching {
                    val rawAdvertisement = scanRecord.trim().ifEmpty { null }
                    require(rawAdvertisement == null || BleBeacon.isValidAdvertisementHex(rawAdvertisement)) {
                        "广播数据 Hex 无效或 AD 结构不完整"
                    }
                    BleBeacon(address.trim(), name.trim(), rssi.toInt(), rawAdvertisement,
                        txPower.trim().takeIf(String::isNotEmpty)?.toInt())
                }.onSuccess(onSave).onFailure {
                    val message = it.message ?: "信标字段无效"
                    validationError = message
                    onError(message)
                }
            }) { Text("保存信标") }
        },
        dismissButton = { TextButton(onDismiss) { Text("取消") } },
    )
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
            EnvironmentWifiApDialog(
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
private fun EnvironmentWifiApDialog(
    initial: WifiAp?,
    onDismiss: () -> Unit,
    onSave: (WifiAp) -> Unit,
    onError: (String) -> Unit,
) {
    var ssid by rememberSaveable(initial?.bssid) { mutableStateOf(initial?.ssid.orEmpty()) }
    var bssid by rememberSaveable(initial?.bssid) { mutableStateOf(initial?.bssid.orEmpty()) }
    var rssi by rememberSaveable(initial?.bssid) { mutableStateOf(initial?.rssi?.toString() ?: "-60") }
    var frequency by rememberSaveable(initial?.bssid) { mutableStateOf(initial?.frequencyMhz?.toString() ?: "2412") }
    var capabilities by rememberSaveable(initial?.bssid) { mutableStateOf(initial?.capabilities ?: "[WPA2-PSK-CCMP][ESS]") }
    var standard by rememberSaveable(initial?.bssid) { mutableStateOf(initial?.standard?.toString() ?: "4") }
    var connected by rememberSaveable(initial?.bssid) { mutableStateOf(initial?.connected ?: false) }
    var validationError by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "新增 Wi-Fi AP" else "编辑 Wi-Fi AP") },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 440.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    item { OutlinedTextField(ssid, { ssid = it }, label = { Text("SSID") }, singleLine = true) }
                    item { OutlinedTextField(bssid, { bssid = it }, label = { Text("BSSID") }, singleLine = true) }
                    item { OutlinedTextField(rssi, { rssi = it }, label = { Text("RSSI (dBm)") }, singleLine = true) }
                    item { OutlinedTextField(frequency, { frequency = it }, label = { Text("频率 (MHz)") }, singleLine = true) }
                    item { OutlinedTextField(capabilities, { capabilities = it }, label = { Text("Capabilities") }) }
                    item { OutlinedTextField(standard, { standard = it }, label = { Text("Wi-Fi 标准编号") }, singleLine = true) }
                    item {
                        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            Checkbox(checked = connected, onCheckedChange = { connected = it })
                            Text("连接中的 AP")
                        }
                    }
                    validationError?.let { message -> item { Text(message, color = MaterialTheme.colorScheme.error) } }
                }
            }
        },
        confirmButton = {
            TextButton({
                runCatching {
                    val mhz = frequency.toInt()
                    val channel = requireNotNull(WifiAp.channelForFrequencyMhz(mhz)) {
                        "频率不是有效的 Wi-Fi 信道中心频率"
                    }
                    WifiAp(ssid.trim(), bssid.trim(), rssi.toInt(), mhz, channel,
                        capabilities.trim(), standard.toInt(), connected)
                }.onSuccess(onSave).onFailure {
                    val message = it.message ?: "Wi-Fi AP 字段无效"
                    validationError = message
                    onError(message)
                }
            }) { Text("保存 AP") }
        },
        dismissButton = { TextButton(onDismiss) { Text("取消") } },
    )
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
            EnvironmentCellTowerDialog(
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
            EnvironmentCdmaTowerDialog(
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

@Composable
private fun EnvironmentCdmaTowerDialog(
    initial: CdmaTower?,
    onDismiss: () -> Unit,
    onSave: (CdmaTower) -> Unit,
    onError: (String) -> Unit,
) {
    var systemId by rememberSaveable(initial?.baseStationId) { mutableStateOf(initial?.systemId?.toString() ?: "1") }
    var networkId by rememberSaveable(initial?.baseStationId) { mutableStateOf(initial?.networkId?.toString() ?: "1") }
    var baseStationId by rememberSaveable(initial?.baseStationId) { mutableStateOf(initial?.baseStationId?.toString() ?: "1") }
    var latitude by rememberSaveable(initial?.baseStationId) { mutableStateOf(initial?.latitudeQuarterSeconds?.toString().orEmpty()) }
    var longitude by rememberSaveable(initial?.baseStationId) { mutableStateOf(initial?.longitudeQuarterSeconds?.toString().orEmpty()) }
    var cdmaDbm by rememberSaveable(initial?.baseStationId) { mutableStateOf(initial?.cdmaDbm?.toString().orEmpty()) }
    var cdmaEcio by rememberSaveable(initial?.baseStationId) { mutableStateOf(initial?.cdmaEcio?.toString().orEmpty()) }
    var evdoDbm by rememberSaveable(initial?.baseStationId) { mutableStateOf(initial?.evdoDbm?.toString().orEmpty()) }
    var evdoEcio by rememberSaveable(initial?.baseStationId) { mutableStateOf(initial?.evdoEcio?.toString().orEmpty()) }
    var evdoSnr by rememberSaveable(initial?.baseStationId) { mutableStateOf(initial?.evdoSnr?.toString().orEmpty()) }
    var registered by rememberSaveable(initial?.baseStationId) { mutableStateOf(initial?.registered ?: false) }
    var validationError by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "新增 CDMA 小区" else "编辑 CDMA 小区") },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 460.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    item { OutlinedTextField(systemId, { systemId = it }, label = { Text("SID") }, singleLine = true) }
                    item { OutlinedTextField(networkId, { networkId = it }, label = { Text("NID") }, singleLine = true) }
                    item { OutlinedTextField(baseStationId, { baseStationId = it }, label = { Text("BID") }, singleLine = true) }
                    item { OutlinedTextField(latitude, { latitude = it }, label = { Text("基站纬度（1/4 秒，可选）") }, singleLine = true) }
                    item { OutlinedTextField(longitude, { longitude = it }, label = { Text("基站经度（1/4 秒，可选）") }, singleLine = true) }
                    item { OutlinedTextField(cdmaDbm, { cdmaDbm = it }, label = { Text("CDMA dBm（可选）") }, singleLine = true) }
                    item { OutlinedTextField(cdmaEcio, { cdmaEcio = it }, label = { Text("CDMA Ec/Io（可选）") }, singleLine = true) }
                    item { OutlinedTextField(evdoDbm, { evdoDbm = it }, label = { Text("EVDO dBm（可选）") }, singleLine = true) }
                    item { OutlinedTextField(evdoEcio, { evdoEcio = it }, label = { Text("EVDO Ec/Io（可选）") }, singleLine = true) }
                    item { OutlinedTextField(evdoSnr, { evdoSnr = it }, label = { Text("EVDO SNR（可选）") }, singleLine = true) }
                    item {
                        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            Checkbox(checked = registered, onCheckedChange = { registered = it })
                            Text("当前注册小区")
                        }
                    }
                    validationError?.let { message -> item { Text(message, color = MaterialTheme.colorScheme.error) } }
                }
            }
        },
        confirmButton = {
            TextButton({
                runCatching {
                    fun optionalInt(value: String) = value.trim().takeIf(String::isNotEmpty)?.toInt()
                    CdmaTower(
                        systemId = systemId.toInt(),
                        networkId = networkId.toInt(),
                        baseStationId = baseStationId.toInt(),
                        latitudeQuarterSeconds = optionalInt(latitude),
                        longitudeQuarterSeconds = optionalInt(longitude),
                        cdmaDbm = optionalInt(cdmaDbm),
                        cdmaEcio = optionalInt(cdmaEcio),
                        evdoDbm = optionalInt(evdoDbm),
                        evdoEcio = optionalInt(evdoEcio),
                        evdoSnr = optionalInt(evdoSnr),
                        registered = registered,
                    )
                }.onSuccess(onSave).onFailure {
                    val message = it.message ?: "CDMA 小区字段无效"
                    validationError = message
                    onError(message)
                }
            }) { Text("保存 CDMA 小区") }
        },
        dismissButton = { TextButton(onDismiss) { Text("取消") } },
    )
}

@Composable
private fun EnvironmentCellTowerDialog(
    initial: CellTower?,
    onDismiss: () -> Unit,
    onSave: (CellTower) -> Unit,
    onError: (String) -> Unit,
) {
    var type by rememberSaveable(initial?.cellId) { mutableStateOf(initial?.type ?: CellType.LTE) }
    var typeMenu by remember { mutableStateOf(false) }
    var mncDigits by rememberSaveable(initial?.cellId) { mutableStateOf(initial?.mncDigits) }
    var mncDigitsMenu by remember { mutableStateOf(false) }
    var mcc by rememberSaveable(initial?.cellId) { mutableStateOf(initial?.mcc?.toString() ?: "460") }
    var mnc by rememberSaveable(initial?.cellId) {
        mutableStateOf(initial?.mnc?.toString()?.padStart(initial.mncDigits ?: 1, '0') ?: "0")
    }
    var operatorName by rememberSaveable(initial?.cellId) { mutableStateOf(initial?.operatorName.orEmpty()) }
    var areaCode by rememberSaveable(initial?.cellId) { mutableStateOf(initial?.areaCode?.toString() ?: "1") }
    var cellId by rememberSaveable(initial?.cellId) { mutableStateOf(initial?.cellId?.toString() ?: "1") }
    var pci by rememberSaveable(initial?.cellId) { mutableStateOf(initial?.pci?.toString().orEmpty()) }
    var arfcn by rememberSaveable(initial?.cellId) { mutableStateOf(initial?.arfcn?.toString().orEmpty()) }
    var rsrp by rememberSaveable(initial?.cellId) { mutableStateOf(initial?.rsrp?.toString() ?: "-90") }
    var rsrq by rememberSaveable(initial?.cellId) { mutableStateOf(initial?.rsrq?.toString().orEmpty()) }
    var rssnr by rememberSaveable(initial?.cellId) { mutableStateOf(initial?.rssnr?.toString().orEmpty()) }
    var registered by rememberSaveable(initial?.cellId) { mutableStateOf(initial?.registered ?: false) }
    var validationError by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "新增蜂窝小区" else "编辑蜂窝小区") },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 460.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    item {
                        Box {
                            OutlinedButton(onClick = { typeMenu = true }) { Text("制式：$type") }
                            DropdownMenu(expanded = typeMenu, onDismissRequest = { typeMenu = false }) {
                                CellType.entries.forEach { option ->
                                    DropdownMenuItem(text = { Text(option.name) }, onClick = {
                                        type = option
                                        typeMenu = false
                                    })
                                }
                            }
                        }
                    }
                    item { OutlinedTextField(mcc, { mcc = it }, label = { Text("MCC") }, singleLine = true) }
                    item { OutlinedTextField(mnc, { mnc = it }, label = { Text("MNC") }, singleLine = true) }
                    item {
                        Box {
                            OutlinedButton(onClick = { mncDigitsMenu = true }) {
                                Text("MNC 位数：${mncDigits?.toString() ?: "未知"}")
                            }
                            DropdownMenu(expanded = mncDigitsMenu, onDismissRequest = { mncDigitsMenu = false }) {
                                DropdownMenuItem(text = { Text("未知") }, onClick = {
                                    mncDigits = null
                                    mncDigitsMenu = false
                                })
                                listOf(2, 3).forEach { digits ->
                                    DropdownMenuItem(text = { Text("$digits 位") }, onClick = {
                                        mnc = mnc.trim().filter(Char::isDigit).takeLast(digits).padStart(digits, '0')
                                        mncDigits = digits
                                        mncDigitsMenu = false
                                    })
                                }
                            }
                        }
                    }
                    item { OutlinedTextField(operatorName, { operatorName = it }, label = { Text("运营商名称（可选）") }, singleLine = true) }
                    item { OutlinedTextField(areaCode, { areaCode = it }, label = { Text("LAC / TAC") }, singleLine = true) }
                    item { OutlinedTextField(cellId, { cellId = it }, label = { Text("CID / NCI") }, singleLine = true) }
                    item { OutlinedTextField(pci, { pci = it }, label = { Text("PCI / PSC（可选）") }, singleLine = true) }
                    item { OutlinedTextField(arfcn, { arfcn = it }, label = { Text("ARFCN（可选）") }, singleLine = true) }
                    item { OutlinedTextField(rsrp, { rsrp = it }, label = { Text("RSRP / RSSI (dBm)") }, singleLine = true) }
                    item { OutlinedTextField(rsrq, { rsrq = it }, label = { Text("RSRQ（可选）") }, singleLine = true) }
                    item { OutlinedTextField(rssnr, { rssnr = it }, label = { Text("RSSNR / SINR（可选）") }, singleLine = true) }
                    item {
                        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            Checkbox(checked = registered, onCheckedChange = { registered = it })
                            Text("当前注册小区")
                        }
                    }
                    validationError?.let { message -> item { Text(message, color = MaterialTheme.colorScheme.error) } }
                }
            }
        },
        confirmButton = {
            TextButton({
                runCatching {
                    val mncText = mnc.trim()
                    require(mncText.isNotEmpty() && mncText.all(Char::isDigit)) { "MNC 必须是数字" }
                    require(mncDigits == null || mncText.length == mncDigits) {
                        "MNC 必须是 ${mncDigits} 位数字"
                    }
                    CellTower(
                        type = type,
                        mcc = mcc.toInt(),
                        mnc = mncText.toInt(),
                        areaCode = areaCode.toInt(),
                        cellId = cellId.toLong(),
                        pci = pci.trim().takeIf(String::isNotEmpty)?.toInt(),
                        arfcn = arfcn.trim().takeIf(String::isNotEmpty)?.toInt(),
                        rsrp = rsrp.toInt(),
                        rsrq = rsrq.trim().takeIf(String::isNotEmpty)?.toInt(),
                        rssnr = rssnr.trim().takeIf(String::isNotEmpty)?.toInt(),
                        registered = registered,
                        mncDigits = mncDigits,
                        operatorName = operatorName.trim().takeIf(String::isNotEmpty),
                    )
                }.onSuccess(onSave).onFailure {
                    val message = it.message ?: "蜂窝小区字段无效"
                    validationError = message
                    onError(message)
                }
            }) { Text("保存小区") }
        },
        dismissButton = { TextButton(onDismiss) { Text("取消") } },
    )
}
