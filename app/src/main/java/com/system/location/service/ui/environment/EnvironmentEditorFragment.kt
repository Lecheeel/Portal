package com.system.location.service.ui.environment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.fragment.findNavController
import com.system.location.service.R
import com.system.location.service.core.environment.*
import com.system.location.service.designsystem.components.PortalActionRow
import com.system.location.service.designsystem.components.PortalCard
import com.system.location.service.designsystem.theme.PortalTheme


class EnvironmentEditorFragment : Fragment() {
    private val model by viewModels<EnvironmentEditorViewModel>()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                PortalTheme {
                    val state by model.state.collectAsStateWithLifecycle()
                    LaunchedEffect(state.saved) {
                        if (state.saved) findNavController().popBackStack()
                    }
                    val profile = state.profile
                    if (profile == null) {
                        Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                            TextButton(model::load, enabled = !state.busy) { Text("重新加载") }
                        }
                    } else EnvironmentProfileEditor(profile, state, model.originalName, model::update,
                        model::save, { findNavController().popBackStack() })
                }
            }
        }
}

@Composable
private fun EnvironmentProfileEditor(
    profile: EnvironmentProfile,
    state: EnvironmentEditorState,
    originalName: String,
    onUpdate: (EnvironmentProfile) -> Unit,
    onSave: (String, String, String, String) -> Unit,
    onBack: () -> Unit,
) {
    var name by rememberSaveable { mutableStateOf(originalName) }
    var latitude by rememberSaveable { mutableStateOf(profile.origin?.latitude?.toString().orEmpty()) }
    var longitude by rememberSaveable { mutableStateOf(profile.origin?.longitude?.toString().orEmpty()) }
    var accuracy by rememberSaveable { mutableStateOf(profile.originAccuracyMeters?.toString().orEmpty()) }
    var kind by rememberSaveable { mutableStateOf(EnvironmentDataKind.WIFI) }
    var editIndex by rememberSaveable { mutableStateOf<Int?>(null) }
    val scroll = rememberLazyListState()
    val labels = when (kind) {
        EnvironmentDataKind.WIFI -> profile.wifi.map { it.ssid to "${it.bssid} · ${it.rssi} dBm${if (it.connected) " · 已连接" else ""}" }
        EnvironmentDataKind.BLE -> profile.ble.map { it.name.ifBlank { "BLE 信标" } to "${it.address} · ${it.rssi} dBm" }
        EnvironmentDataKind.CELL -> profile.cells.map { "${it.type} · ${it.cellId}" to
            "${it.operatorNumeric ?: "${it.mcc}-${it.mnc}"} · ${it.rsrp} dBm${if (it.registered) " · 已注册" else ""}" }
        EnvironmentDataKind.CDMA -> profile.cdmaCells.map { "SID ${it.systemId} · BID ${it.baseStationId}" to
            "NID ${it.networkId}${if (it.registered) " · 已注册" else ""}" }
    }
    val limit = when (kind) {
        EnvironmentDataKind.WIFI -> EnvironmentProfile.MAX_WIFI
        EnvironmentDataKind.BLE -> EnvironmentProfile.MAX_BLE
        else -> EnvironmentProfile.MAX_CELLS
    }
    val count = if (kind == EnvironmentDataKind.CELL || kind == EnvironmentDataKind.CDMA)
        profile.cells.size + profile.cdmaCells.size else labels.size

    Box {
        if (editIndex == null) EnvironmentEditorPage(onBack, { Text(if (originalName.isBlank()) "新建环境" else "编辑环境") }, text = {
            LazyColumn(Modifier.fillMaxSize(), state = scroll, verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(bottom = 16.dp)) {
                item(key = "identity") {
                    PortalCard {
                        OutlinedTextField(name, { name = it }, label = { Text("环境名称") },
                            enabled = originalName.isBlank() && !state.busy, singleLine = true, modifier = Modifier.fillMaxWidth(),
                            supportingText = { Text("名称用于文件保存；支持字母、数字、点、下划线和连字符") })
                        Text("回放位置（WGS84）", style = MaterialTheme.typography.titleSmall)
                        Text("坐标与精度可留空暂存；用于回放时需填写，且模拟位置应在此坐标附近。",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        OutlinedTextField(latitude, { latitude = it }, label = { Text("纬度") },
                            singleLine = true, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(longitude, { longitude = it }, label = { Text("经度") },
                            singleLine = true, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(accuracy, { accuracy = it }, label = { Text("精度（米）") },
                            singleLine = true, modifier = Modifier.fillMaxWidth())
                    }
                }
                item(key = "kind") {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        EnvironmentDataKind.entries.forEach { option ->
                            FilterChip(kind == option, { kind = option }, label = { Text(option.label) })
                        }
                    }
                    Text("${kind.label} · ${labels.size} 条", style = MaterialTheme.typography.titleMedium)
                    Text("Wi-Fi ${profile.wifi.size} · 蓝牙 ${profile.ble.size} · 小区 ${profile.cells.size + profile.cdmaCells.size}",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                item(key = "add") {
                    OutlinedButton({ editIndex = -1 }, enabled = !state.busy && count < limit,
                        modifier = Modifier.fillMaxWidth()) { Text("添加${kind.label}") }
                }
                if (labels.isEmpty()) item(key = "empty") {
                    Text("还没有${kind.label}数据，可直接手动添加。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                items(labels.size, key = { "${kind.name}-$it" }) { index ->
                    PortalCard {
                        PortalActionRow(labels[index].first, labels[index].second,
                            onClick = { editIndex = index })
                        TextButton(onClick = {
                            onUpdate(when (kind) {
                                EnvironmentDataKind.WIFI -> profile.copy(wifi = profile.wifi.filterIndexed { i, _ -> i != index })
                                EnvironmentDataKind.BLE -> profile.copy(ble = profile.ble.filterIndexed { i, _ -> i != index })
                                EnvironmentDataKind.CELL -> profile.copy(cells = profile.cells.filterIndexed { i, _ -> i != index })
                                EnvironmentDataKind.CDMA -> profile.copy(cdmaCells = profile.cdmaCells.filterIndexed { i, _ -> i != index })
                            })
                        }, enabled = !state.busy) {
                            Icon(painterResource(R.drawable.ic_portal_delete), null, Modifier.size(18.dp))
                            Text("移除", Modifier.padding(start = 6.dp))
                        }
                    }
                }
                state.error?.let { item(key = "error") { Text(it, color = MaterialTheme.colorScheme.error) } }
            }
        }, confirmButton = {
            if (state.busy) CircularProgressIndicator(Modifier.size(22.dp))
            TextButton({ onSave(name, latitude, longitude, accuracy) }, enabled = !state.busy) { Text("保存环境") }
        }, dismissButton = { TextButton(onBack, enabled = !state.busy) { Text("返回") } })

        editIndex?.let { index ->
            key(kind, index) {
                val close = { editIndex = null }
                fun apply(change: () -> EnvironmentProfile) {
                    onUpdate(change())
                    close()
                }
                when (kind) {
                    EnvironmentDataKind.WIFI -> EnvironmentWifiApPage(profile.wifi.getOrNull(index), close, { ap -> apply {
                        require(profile.wifi.withIndex().none { it.index != index && it.value.bssid.equals(ap.bssid, true) }) { "BSSID 已存在" }
                        val list = profile.wifi.mapIndexed { i, old -> if (i == index) ap else if (ap.connected) old.copy(connected = false) else old }
                        profile.copy(wifi = if (index < 0) list + ap else list)
                    } })
                    EnvironmentDataKind.BLE -> EnvironmentBleBeaconPage(profile.ble.getOrNull(index), close, { beacon -> apply {
                        require(profile.ble.withIndex().none { it.index != index && it.value.address.equals(beacon.address, true) }) { "蓝牙地址已存在" }
                        profile.copy(ble = if (index < 0) profile.ble + beacon else profile.ble.mapIndexed { i, old -> if (i == index) beacon else old })
                    } })
                    EnvironmentDataKind.CELL -> EnvironmentCellTowerPage(profile.cells.getOrNull(index), close, { cell -> apply {
                        require(profile.cells.withIndex().none { it.index != index && it.value.let { old ->
                            old.type == cell.type && old.mcc == cell.mcc && old.mnc == cell.mnc && old.areaCode == cell.areaCode && old.cellId == cell.cellId
                        } }) { "蜂窝小区已存在" }
                        val list = profile.cells.mapIndexed { i, old -> if (i == index) cell else if (cell.registered) old.copy(registered = false) else old }
                        profile.copy(cells = if (index < 0) list + cell else list,
                            cdmaCells = if (cell.registered) profile.cdmaCells.map { it.copy(registered = false) } else profile.cdmaCells)
                    } })
                    EnvironmentDataKind.CDMA -> EnvironmentCdmaTowerPage(profile.cdmaCells.getOrNull(index), close, { cell -> apply {
                        require(profile.cdmaCells.withIndex().none { it.index != index && it.value.let { old ->
                            old.systemId == cell.systemId && old.networkId == cell.networkId && old.baseStationId == cell.baseStationId
                        } }) { "CDMA 小区已存在" }
                        val list = profile.cdmaCells.mapIndexed { i, old -> if (i == index) cell else if (cell.registered) old.copy(registered = false) else old }
                        profile.copy(cdmaCells = if (index < 0) list + cell else list,
                            cells = if (cell.registered) profile.cells.map { it.copy(registered = false) } else profile.cells)
                    } })
                }

            }
        }
    }
}
