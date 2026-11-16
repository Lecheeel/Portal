package com.system.location.service.ui.settings

import android.content.Context
import android.os.Bundle
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshots.SnapshotStateList
import kotlinx.serialization.Serializable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.system.location.service.R
import com.system.location.service.core.carrier.CarrierAppRoute
import com.system.location.service.core.carrier.reconcileCarrierRoutes
import com.system.location.service.core.carrier.CarrierCountries
import com.system.location.service.core.carrier.CarrierCountry
import com.system.location.service.core.carrier.CarrierIdentityConfig
import com.system.location.service.core.carrier.CarrierIdentityMode
import com.system.location.service.core.carrier.CarrierPresetCatalog
import com.system.location.service.core.carrier.CarrierRadio
import com.system.location.service.core.carrier.CarrierTarget
import com.system.location.service.core.carrier.SimCarrierProfile
import com.system.location.service.designsystem.components.PortalCard
import com.system.location.service.designsystem.components.PortalSectionTitle
import com.system.location.service.designsystem.theme.PortalTheme
import com.system.location.service.ext.carrierIdentityEnabled
import com.system.location.service.ext.carrierIdentityJson
import com.system.location.service.ext.sharedPrefs
import com.system.location.service.service.MockServiceHelper
import kotlinx.serialization.json.Json
import java.util.Locale

@Serializable
private data class SimDraft(
    val slot: Int,
    val enabled: Boolean = false,
    val countryIso: String = "CN",
    val mcc: String = "",
    val mnc: String = "",
    val mncDigits: Int = 2,
    val carrierName: String = "",
    val operatorName: String = "",
    val radio: CarrierRadio = CarrierRadio.NR,
    val nrBand: Int = 78,
    val nrArfcn: String = "640000",
)

private val SimDraftSaver = listSaver<SnapshotStateList<SimDraft>, String>(
    save = { drafts -> drafts.map { Json.encodeToString(SimDraft.serializer(), it) } },
    restore = { rows -> rows.map { Json.decodeFromString(SimDraft.serializer(), it) }.toMutableStateList() },
)
private val CarrierRoutesSaver = listSaver<SnapshotStateList<CarrierAppRoute>, String>(
    save = { routes -> routes.map { Json.encodeToString(CarrierAppRoute.serializer(), it) } },
    restore = { rows -> rows.map { Json.decodeFromString(CarrierAppRoute.serializer(), it) }.toMutableStateList() },
)

/**
 * SIM/operator identity editor. The master switch is intentionally here and on the
 * system settings page; both write the same persisted boolean. GLOBAL is the default mode and
 * intentionally affects the system phone stack/status bar; PER_APP keeps system callers real.
 */
class CarrierIdentityComposeFragment : Fragment() {
    private var pendingAppSelection by mutableStateOf<List<String>?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingAppSelection = savedInstanceState?.getStringArrayList("pending_carrier_apps")
        parentFragmentManager.setFragmentResultListener(
            AppSelectionFragment.RESULT, this,
        ) { _, result ->
            if (result.getString("purpose") == "carrier") {
                pendingAppSelection = result.getStringArrayList("selected").orEmpty().toList()
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        pendingAppSelection?.let { outState.putStringArrayList("pending_carrier_apps", ArrayList(it)) }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = ComposeView(requireContext()).apply {
        id = R.id.nav_carrier_identity
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        setContent {
            PortalTheme {
                val context = LocalContext.current
                val initial = remember { loadConfig(context) }
                CarrierIdentityScreen(
                    context = context,
                    initialEnabled = context.carrierIdentityEnabled,
                    initial = initial,
                    selectedPackages = pendingAppSelection,
                    onAppSelectionConsumed = { pendingAppSelection = null },
                    onAddApps = { selected ->
                        findNavController().navigate(R.id.nav_app_selection, Bundle().apply {
                            putString("purpose", "carrier")
                            putString("title", "选择运营商身份覆盖应用")
                            putStringArrayList("selected", ArrayList(selected.sorted()))
                        })
                    },
                )
            }
        }
    }

    private fun loadConfig(context: Context): CarrierIdentityConfig {
        val saved = context.carrierIdentityJson
        if (saved.isNotBlank()) {
            runCatching { com.system.location.service.core.carrier.CarrierIdentityCodec.decode(saved) }
                .getOrNull()?.let { return it }
        }
        return CarrierIdentityConfig(
            mode = CarrierIdentityMode.GLOBAL,
            sims = realSimProfiles(context),
        )
    }

    private fun realSimProfiles(context: Context): List<SimCarrierProfile> {
        val telephony = context.getSystemService(TelephonyManager::class.java)
        val subscriptions = context.getSystemService(SubscriptionManager::class.java)
        val infos = runCatching { subscriptions?.activeSubscriptionInfoList.orEmpty() }
            .getOrDefault(emptyList())
        return infos.mapNotNull { info ->
            val slot = info.simSlotIndex + 1
            if (slot !in 1..2) return@mapNotNull null
            val perSub = runCatching { telephony?.createForSubscriptionId(info.subscriptionId) }
                .getOrNull() ?: return@mapNotNull null
            val numeric = perSub.simOperator?.takeIf { it.length >= 5 } ?: return@mapNotNull null
            val mcc = numeric.take(3).toIntOrNull() ?: return@mapNotNull null
            val mncDigits = numeric.length - 3
            if (mncDigits !in 2..3) return@mapNotNull null
            val mnc = numeric.drop(3).toIntOrNull() ?: return@mapNotNull null
            val iso = (perSub.simCountryIso ?: info.countryIso)
                ?.uppercase(Locale.ROOT)
                ?.takeIf { it.length == 2 }
                ?: CarrierCountries.byIso("CN")?.iso
                ?: return@mapNotNull null
            SimCarrierProfile(
                slot = slot,
                enabled = true,
                countryIso = iso,
                mcc = mcc,
                mnc = mnc,
                mncDigits = mncDigits,
                carrierName = perSub.simOperatorName?.takeIf { it.isNotBlank() }
                    ?: info.carrierName?.toString()?.takeIf { it.isNotBlank() } ?: "SIM $slot",
                operatorName = perSub.simOperatorName?.takeIf { it.isNotBlank() } ?: "SIM $slot",
                radio = CarrierRadio.NR,
            )
        }
    }
}

@Composable
private fun CarrierIdentityScreen(
    context: Context,
    initialEnabled: Boolean,
    initial: CarrierIdentityConfig,
    selectedPackages: List<String>?,
    onAppSelectionConsumed: () -> Unit,
    onAddApps: (List<String>) -> Unit,
) {
    var enabled by rememberSaveable { mutableStateOf(initialEnabled) }
    var mode by rememberSaveable { mutableStateOf(initial.mode) }
    val drafts = rememberSaveable(saver = SimDraftSaver) {
        val bySlot = initial.sims.associateBy { it.slot }
        (1..2).map { slot -> bySlot[slot]?.toDraft() ?: SimDraft(slot) }.toMutableStateList()
    }
    val routes = rememberSaveable(saver = CarrierRoutesSaver) { initial.appRoutes.toMutableStateList() }
    var selectedSlot by rememberSaveable { mutableStateOf(1) }
    var countryPickerSlot by remember { mutableStateOf<Int?>(null) }
    var carrierPickerSlot by remember { mutableStateOf<Int?>(null) }
    var status by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(selectedPackages) {
        selectedPackages?.let { packages ->
            runCatching { reconcileCarrierRoutes(routes.toList(), packages) }
                .onSuccess { updated ->
                    routes.clear()
                    routes.addAll(updated)
                }
                .onFailure { error = "应用规则超过上限，最多 ${CarrierIdentityConfig.MAX_ROUTES} 个" }
            onAppSelectionConsumed()
        }
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                PortalSectionTitle("运营商身份", "SIM/运营商覆盖；全局模式会影响系统电话栈与状态栏")
            }
            item {
                PortalCard {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("启用运营商身份", style = MaterialTheme.typography.titleMedium)
                                Text(
                                    "关闭时完全透传真实 SIM；配置会保留。",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Switch(checked = enabled, onCheckedChange = { enabled = it })
                        }
                        Text("模式", style = MaterialTheme.typography.labelLarge)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(
                                selected = mode == CarrierIdentityMode.GLOBAL,
                                onClick = { mode = CarrierIdentityMode.GLOBAL },
                                label = { Text("全局（含系统/状态栏）") },
                            )
                            FilterChip(
                                selected = mode == CarrierIdentityMode.PER_APP,
                                onClick = { mode = CarrierIdentityMode.PER_APP },
                                label = { Text("仅分应用") },
                            )
                        }
                        Text(
                            if (mode == CarrierIdentityMode.GLOBAL) {
                                "全局模式会同时覆盖系统电话栈、SystemUI 状态栏和第三方应用；需要 Root/Xposed。"
                            } else {
                                "分应用模式只覆盖下列应用；系统电话栈、状态栏和其他应用保持真实。"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    (1..2).forEach { slot ->
                        FilterChip(
                            selected = selectedSlot == slot,
                            onClick = { selectedSlot = slot },
                            label = { Text("SIM $slot") },
                        )
                    }
                }
            }
            item {
                val draft = drafts[selectedSlot - 1]
                PortalCard {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("SIM $selectedSlot", style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.weight(1f))
                            Switch(
                                checked = draft.enabled,
                                onCheckedChange = { drafts[selectedSlot - 1] = draft.copy(enabled = it) },
                            )
                        }
                        val countryLabel = CarrierCountries.byIso(draft.countryIso)?.let {
                            "${it.displayName} (${it.iso})"
                        } ?: draft.countryIso
                        TextButton(onClick = { countryPickerSlot = selectedSlot }) {
                            Text("国家/地区：$countryLabel")
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = draft.mcc,
                                onValueChange = { value ->
                                    if (value.length <= 3 && value.all(Char::isDigit)) {
                                        drafts[selectedSlot - 1] = draft.copy(mcc = value)
                                    }
                                },
                                label = { Text("MCC") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                singleLine = true,
                                modifier = Modifier.weight(1f),
                            )
                            OutlinedTextField(
                                value = draft.mnc,
                                onValueChange = { value ->
                                    if (value.length <= 3 && value.all(Char::isDigit)) {
                                        drafts[selectedSlot - 1] = draft.copy(
                                            mnc = value,
                                            mncDigits = value.length.coerceIn(2, 3),
                                        )
                                    }
                                },
                                label = { Text("MNC") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                singleLine = true,
                                modifier = Modifier.weight(1f),
                            )
                        }
                        OutlinedTextField(
                            value = draft.carrierName,
                            onValueChange = { drafts[selectedSlot - 1] = draft.copy(carrierName = it) },
                            label = { Text("运营商名称") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = draft.operatorName,
                            onValueChange = { drafts[selectedSlot - 1] = draft.copy(operatorName = it) },
                            label = { Text("显示名称（可选）") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(
                                selected = draft.radio == CarrierRadio.NR,
                                onClick = { drafts[selectedSlot - 1] = draft.copy(radio = CarrierRadio.NR) },
                                label = { Text("NR SA") },
                            )
                            FilterChip(
                                selected = draft.radio == CarrierRadio.LTE,
                                onClick = { drafts[selectedSlot - 1] = draft.copy(radio = CarrierRadio.LTE) },
                                label = { Text("LTE") },
                            )
                        }
                        if (draft.radio == CarrierRadio.NR) {
                            Text("NR 生成参数", style = MaterialTheme.typography.labelLarge)
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                listOf(41, 77, 78, 79).forEach { band ->
                                    FilterChip(selected = draft.nrBand == band,
                                        onClick = {
                                            drafts[selectedSlot - 1] = draft.copy(nrBand = band,
                                                nrArfcn = com.system.location.service.core.carrier.CarrierNrChannel
                                                    .defaultArfcn(band).toString())
                                        }, label = { Text("n$band") })
                                }
                            }
                            OutlinedTextField(value = draft.nrArfcn,
                                onValueChange = { value ->
                                    if (value.length <= 7 && value.all(Char::isDigit))
                                        drafts[selectedSlot - 1] = draft.copy(nrArfcn = value)
                                }, label = { Text("NR-ARFCN") }, singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.fillMaxWidth())
                            Text("没有 NR 回放数据时，按目标点和固定种子生成 NR SA 小区；LTE 仅参考相对信号。频段需按测试场景选择，不代表当地实际部署。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (draft.enabled && draft.mcc.isNotBlank() && draft.mnc.isNotBlank()) {
                            Text(
                                "PLMN：${draft.mcc.padStart(3, '0')}${draft.mnc.padStart(draft.mncDigits, '0')}",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        TextButton(onClick = { carrierPickerSlot = selectedSlot }) {
                            Text("从运营商预设填充")
                        }
                    }
                }
            }
            item {
                PortalCard {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("分应用路由", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "以下规则仅在分应用模式下生效。已选应用可使用对应卡槽配置、SIM1、SIM2 或真实值；未选应用保持真实。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Button(onClick = { onAddApps(routes.map { it.packageName }) }) {
                            Text("添加应用")
                        }
                        if (routes.isEmpty()) {
                            Text("还没有分应用覆盖", style = MaterialTheme.typography.bodySmall)
                        }
                        routes.forEachIndexed { index, route ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(route.packageName, Modifier.weight(1f),
                                    style = MaterialTheme.typography.bodyMedium)
                                CarrierTargetPicker(
                                    target = route.target,
                                    onTarget = { routes[index] = route.copy(target = it) },
                                )
                                TextButton(onClick = { routes.removeAt(index) }) { Text("移除") }
                            }
                        }
                    }
                }
            }
            item {
                Button(
                    onClick = {
                        runCatching {
                            val sims = drafts.mapNotNull { draft ->
                                if (!draft.enabled && draft.mcc.isBlank() && draft.mnc.isBlank() &&
                                    draft.carrierName.isBlank()) return@mapNotNull null
                                require(CarrierCountries.isValid(draft.countryIso)) {
                                    "SIM ${draft.slot} 国家/地区无效"
                                }
                                val mcc = draft.mcc.toIntOrNull() ?: error("SIM ${draft.slot} MCC 无效")
                                val mnc = draft.mnc.toIntOrNull() ?: error("SIM ${draft.slot} MNC 无效")
                                require(draft.carrierName.isNotBlank()) { "SIM ${draft.slot} 运营商名称不能为空" }
                                SimCarrierProfile(
                                    slot = draft.slot,
                                    enabled = draft.enabled,
                                    countryIso = draft.countryIso.uppercase(Locale.ROOT),
                                    mcc = mcc,
                                    mnc = mnc,
                                    mncDigits = draft.mncDigits,
                                    carrierName = draft.carrierName.trim(),
                                    operatorName = draft.operatorName.ifBlank { draft.carrierName }.trim(),
                                    radio = draft.radio,
                                    nrBand = draft.nrBand,
                                    nrArfcn = draft.nrArfcn.toIntOrNull() ?: error("SIM ${draft.slot} NR-ARFCN 无效"),
                                )
                            }
                            require(!enabled || sims.any { it.enabled }) { "开启后至少配置一张 SIM" }
                            val config = CarrierIdentityConfig(mode = mode, sims = sims, appRoutes = routes.toList())
                            val json = com.system.location.service.core.carrier.CarrierIdentityCodec.encode(config)
                            check(context.sharedPrefs.edit()
                                .putString("carrierIdentityJson", json)
                                .putBoolean("carrierIdentityEnabled", enabled).commit()) { "保存失败，请重试" }
                            saving = true
                            scope.launch {
                                try {
                                    val applied = withContext(Dispatchers.IO) {
                                        runCatching {
                                            MockServiceHelper.putConfig(
                                                context.getSystemService(android.location.LocationManager::class.java),
                                                context,
                                            )
                                        }.getOrDefault(false)
                                    }
                                    status = if (!applied) "已保存；当前场景未连接，下次生效" else when (MockServiceHelper.carrierIdentityStatus) {
                                        "global_applied" -> "已保存，全局临时配置已写入"
                                        "per_app" -> "已保存，分应用规则已启用"
                                        "disabled" -> "已保存，运营商身份已关闭"
                                        "waiting_for_scenario" -> "已保存，启动模拟场景后生效"
                                        "no_active_sim" -> "已保存，当前没有匹配的活跃 SIM；不会虚构插卡"
                                        "service_unavailable" -> "已保存，电话配置服务暂不可用"
                                        "failed", "restore_pending" -> "已保存，但系统覆盖或还原未完成；请重试"
                                        "unconfigured" -> "已保存，请先配置启用的 SIM"
                                        else -> "已保存并发送配置，设备效果待确认"
                                    }
                                } finally {
                                    saving = false
                                }
                            }
                            error = null
                        }.onFailure { error = it.message ?: "保存失败" }
                    },
                    enabled = !saving,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (saving) "正在应用…" else "保存并应用") }
                status.takeIf { it.isNotBlank() }?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        }

        countryPickerSlot?.let { slot ->
            val draft = drafts[slot - 1]
            CountryPickerDialog(
                selected = draft.countryIso,
                onDismiss = { countryPickerSlot = null },
                onSelect = { country ->
                    if (country.iso != draft.countryIso) {
                        drafts[slot - 1] = draft.copy(
                            countryIso = country.iso,
                            mcc = country.mcc?.toString().orEmpty(),
                            mnc = "",
                            carrierName = "",
                            operatorName = "",
                        )
                    }
                    countryPickerSlot = null
                },
            )
        }
        carrierPickerSlot?.let { slot ->
            val draft = drafts[slot - 1]
            CarrierPresetDialog(
                countryIso = draft.countryIso,
                onDismiss = { carrierPickerSlot = null },
                onSelect = { preset ->
                    drafts[slot - 1] = draft.copy(
                        mcc = preset.mcc.toString(),
                        mnc = preset.mnc.toString().padStart(preset.mncDigits, '0'),
                        mncDigits = preset.mncDigits,
                        carrierName = preset.carrierName,
                        operatorName = preset.operatorName,
                        radio = preset.radio,
                    )
                    carrierPickerSlot = null
                },
            )
        }
    }
}

@Composable
private fun CarrierTargetPicker(target: CarrierTarget, onTarget: (CarrierTarget) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { expanded = true }) { Text(targetLabel(target)) }
        androidx.compose.material3.DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            CarrierTarget.entries.forEach { candidate ->
                androidx.compose.material3.DropdownMenuItem(
                    text = { Text(targetLabel(candidate)) },
                    onClick = { expanded = false; onTarget(candidate) },
                )
            }
        }
    }
}

private fun targetLabel(target: CarrierTarget): String = when (target) {
    CarrierTarget.INHERIT -> "对应卡槽配置"
    CarrierTarget.SIM1 -> "SIM1"
    CarrierTarget.SIM2 -> "SIM2"
    CarrierTarget.REAL -> "真实值"
}

@Composable
private fun CountryPickerDialog(
    selected: String,
    onDismiss: () -> Unit,
    onSelect: (CarrierCountry) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val countries = remember(query) {
        CarrierCountries.all.filter {
            it.displayName.contains(query, true) || it.iso.contains(query, true)
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择国家/地区") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(query, { query = it }, label = { Text("搜索国家/地区") }, singleLine = true)
                LazyColumn(Modifier.height(420.dp)) {
                    items(countries, key = { it.iso }) { country ->
                        TextButton(
                            onClick = { onSelect(country) },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                "${country.displayName} (${country.iso})" +
                                    if (country.iso == selected) " · 当前" else "",
                                Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}

@Composable
private fun CarrierPresetDialog(
    countryIso: String,
    onDismiss: () -> Unit,
    onSelect: (com.system.location.service.core.carrier.CarrierPreset) -> Unit,
) {
    val presets = remember(countryIso) { CarrierPresetCatalog.forCountry(countryIso) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("运营商预设") },
        text = {
            LazyColumn(Modifier.height(360.dp)) {
                if (presets.isEmpty()) {
                    item { Text("该国家/地区暂无预设，请手动填写 MCC/MNC。") }
                }
                items(presets) { preset ->
                    TextButton(onClick = { onSelect(preset) }, modifier = Modifier.fillMaxWidth()) {
                        Text("${preset.carrierName} · ${preset.mcc}/${preset.mnc}")
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}

private fun SimCarrierProfile.toDraft(): SimDraft = SimDraft(
    slot = slot,
    enabled = enabled,
    countryIso = countryIso,
    mcc = mcc.toString(),
    mnc = mnc.toString().padStart(mncDigits, '0'),
    mncDigits = mncDigits,
    carrierName = carrierName,
    operatorName = operatorName,
    radio = radio,
    nrBand = nrBand,
    nrArfcn = nrArfcn.toString(),
)
