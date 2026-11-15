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

@Composable
internal fun EnvironmentBleBeaconPage(
    initial: BleBeacon?,
    onDismiss: () -> Unit,
    onSave: (BleBeacon) -> Unit,
) {
    var address by rememberSaveable(initial?.address) { mutableStateOf(initial?.address.orEmpty()) }
    var name by rememberSaveable(initial?.address) { mutableStateOf(initial?.name.orEmpty()) }
    var rssi by rememberSaveable(initial?.address) { mutableStateOf(initial?.rssi?.toString() ?: "-65") }
    var scanRecord by rememberSaveable(initial?.address) { mutableStateOf(initial?.scanRecordHex.orEmpty()) }
    var txPower by rememberSaveable(initial?.address) { mutableStateOf(initial?.txPowerDbm?.toString().orEmpty()) }
    var validationError by remember { mutableStateOf<String?>(null) }
    EnvironmentEditorPage(
        onBack = onDismiss,
        title = { Text(if (initial == null) "新增 BLE 信标" else "编辑 BLE 信标") },
        text = {
            Column(Modifier.fillMaxWidth().fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    item { OutlinedTextField(address, { address = it }, modifier = Modifier.fillMaxWidth(), label = { Text("地址") }, singleLine = true) }
                    item { OutlinedTextField(name, { name = it }, modifier = Modifier.fillMaxWidth(), label = { Text("广播名称") }, singleLine = true) }
                    item { OutlinedTextField(rssi, { rssi = it }, modifier = Modifier.fillMaxWidth(), label = { Text("RSSI (dBm)") }, singleLine = true) }
                    item { OutlinedTextField(scanRecord, { scanRecord = it }, modifier = Modifier.fillMaxWidth(), label = { Text("广播数据 Hex（可选）") }) }
                    item { OutlinedTextField(txPower, { txPower = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Tx Power dBm（可选）") }, singleLine = true) }
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
                }.mapCatching { onSave(it) }.onFailure {
                    val message = it.message ?: "信标字段无效"
                    validationError = message
                }
            }) { Text("保存信标") }
        },
        dismissButton = { TextButton(onDismiss) { Text("取消") } },
    )
}
@Composable
internal fun EnvironmentWifiApPage(
    initial: WifiAp?,
    onDismiss: () -> Unit,
    onSave: (WifiAp) -> Unit,
) {
    var ssid by rememberSaveable(initial?.bssid) { mutableStateOf(initial?.ssid.orEmpty()) }
    var bssid by rememberSaveable(initial?.bssid) { mutableStateOf(initial?.bssid.orEmpty()) }
    var rssi by rememberSaveable(initial?.bssid) { mutableStateOf(initial?.rssi?.toString() ?: "-60") }
    var frequency by rememberSaveable(initial?.bssid) { mutableStateOf(initial?.frequencyMhz?.toString() ?: "2412") }
    var capabilities by rememberSaveable(initial?.bssid) { mutableStateOf(initial?.capabilities ?: "[WPA2-PSK-CCMP][ESS]") }
    var standard by rememberSaveable(initial?.bssid) { mutableStateOf(initial?.standard?.toString() ?: "4") }
    var connected by rememberSaveable(initial?.bssid) { mutableStateOf(initial?.connected ?: false) }
    var validationError by remember { mutableStateOf<String?>(null) }
    EnvironmentEditorPage(
        onBack = onDismiss,
        title = { Text(if (initial == null) "新增 Wi-Fi AP" else "编辑 Wi-Fi AP") },
        text = {
            Column(Modifier.fillMaxWidth().fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    item { OutlinedTextField(ssid, { ssid = it }, modifier = Modifier.fillMaxWidth(), label = { Text("SSID") }, singleLine = true) }
                    item { OutlinedTextField(bssid, { bssid = it }, modifier = Modifier.fillMaxWidth(), label = { Text("BSSID") }, singleLine = true) }
                    item { OutlinedTextField(rssi, { rssi = it }, modifier = Modifier.fillMaxWidth(), label = { Text("RSSI (dBm)") }, singleLine = true) }
                    item { OutlinedTextField(frequency, { frequency = it }, modifier = Modifier.fillMaxWidth(), label = { Text("频率 (MHz)") }, singleLine = true) }
                    item { OutlinedTextField(capabilities, { capabilities = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Capabilities") }) }
                    item { OutlinedTextField(standard, { standard = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Wi-Fi 标准编号") }, singleLine = true) }
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
                }.mapCatching { onSave(it) }.onFailure {
                    val message = it.message ?: "Wi-Fi AP 字段无效"
                    validationError = message
                }
            }) { Text("保存 AP") }
        },
        dismissButton = { TextButton(onDismiss) { Text("取消") } },
    )
}

@Composable
internal fun EnvironmentCdmaTowerPage(
    initial: CdmaTower?,
    onDismiss: () -> Unit,
    onSave: (CdmaTower) -> Unit,
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
    EnvironmentEditorPage(
        onBack = onDismiss,
        title = { Text(if (initial == null) "新增 CDMA 小区" else "编辑 CDMA 小区") },
        text = {
            Column(Modifier.fillMaxWidth().fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    item { OutlinedTextField(systemId, { systemId = it }, modifier = Modifier.fillMaxWidth(), label = { Text("SID") }, singleLine = true) }
                    item { OutlinedTextField(networkId, { networkId = it }, modifier = Modifier.fillMaxWidth(), label = { Text("NID") }, singleLine = true) }
                    item { OutlinedTextField(baseStationId, { baseStationId = it }, modifier = Modifier.fillMaxWidth(), label = { Text("BID") }, singleLine = true) }
                    item { OutlinedTextField(latitude, { latitude = it }, modifier = Modifier.fillMaxWidth(), label = { Text("基站纬度（1/4 秒，可选）") }, singleLine = true) }
                    item { OutlinedTextField(longitude, { longitude = it }, modifier = Modifier.fillMaxWidth(), label = { Text("基站经度（1/4 秒，可选）") }, singleLine = true) }
                    item { OutlinedTextField(cdmaDbm, { cdmaDbm = it }, modifier = Modifier.fillMaxWidth(), label = { Text("CDMA dBm（可选）") }, singleLine = true) }
                    item { OutlinedTextField(cdmaEcio, { cdmaEcio = it }, modifier = Modifier.fillMaxWidth(), label = { Text("CDMA Ec/Io（可选）") }, singleLine = true) }
                    item { OutlinedTextField(evdoDbm, { evdoDbm = it }, modifier = Modifier.fillMaxWidth(), label = { Text("EVDO dBm（可选）") }, singleLine = true) }
                    item { OutlinedTextField(evdoEcio, { evdoEcio = it }, modifier = Modifier.fillMaxWidth(), label = { Text("EVDO Ec/Io（可选）") }, singleLine = true) }
                    item { OutlinedTextField(evdoSnr, { evdoSnr = it }, modifier = Modifier.fillMaxWidth(), label = { Text("EVDO SNR（可选）") }, singleLine = true) }
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
                }.mapCatching { onSave(it) }.onFailure {
                    val message = it.message ?: "CDMA 小区字段无效"
                    validationError = message
                }
            }) { Text("保存 CDMA 小区") }
        },
        dismissButton = { TextButton(onDismiss) { Text("取消") } },
    )
}

@Composable
internal fun EnvironmentCellTowerPage(
    initial: CellTower?,
    onDismiss: () -> Unit,
    onSave: (CellTower) -> Unit,
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
    EnvironmentEditorPage(
        onBack = onDismiss,
        title = { Text(if (initial == null) "新增蜂窝小区" else "编辑蜂窝小区") },
        text = {
            Column(Modifier.fillMaxWidth().fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
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
                    item { OutlinedTextField(mcc, { mcc = it }, modifier = Modifier.fillMaxWidth(), label = { Text("MCC") }, singleLine = true) }
                    item { OutlinedTextField(mnc, { mnc = it }, modifier = Modifier.fillMaxWidth(), label = { Text("MNC") }, singleLine = true) }
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
                    item { OutlinedTextField(operatorName, { operatorName = it }, modifier = Modifier.fillMaxWidth(), label = { Text("运营商名称（可选）") }, singleLine = true) }
                    item { OutlinedTextField(areaCode, { areaCode = it }, modifier = Modifier.fillMaxWidth(), label = { Text("LAC / TAC") }, singleLine = true) }
                    item { OutlinedTextField(cellId, { cellId = it }, modifier = Modifier.fillMaxWidth(), label = { Text("CID / NCI") }, singleLine = true) }
                    item { OutlinedTextField(pci, { pci = it }, modifier = Modifier.fillMaxWidth(), label = { Text("PCI / PSC（可选）") }, singleLine = true) }
                    item { OutlinedTextField(arfcn, { arfcn = it }, modifier = Modifier.fillMaxWidth(), label = { Text("ARFCN（可选）") }, singleLine = true) }
                    item { OutlinedTextField(rsrp, { rsrp = it }, modifier = Modifier.fillMaxWidth(), label = { Text("RSRP / RSSI (dBm)") }, singleLine = true) }
                    item { OutlinedTextField(rsrq, { rsrq = it }, modifier = Modifier.fillMaxWidth(), label = { Text("RSRQ（可选）") }, singleLine = true) }
                    item { OutlinedTextField(rssnr, { rssnr = it }, modifier = Modifier.fillMaxWidth(), label = { Text("RSSNR / SINR（可选）") }, singleLine = true) }
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
                }.mapCatching { onSave(it) }.onFailure {
                    val message = it.message ?: "蜂窝小区字段无效"
                    validationError = message
                }
            }) { Text("保存小区") }
        },
        dismissButton = { TextButton(onDismiss) { Text("取消") } },
    )
}
