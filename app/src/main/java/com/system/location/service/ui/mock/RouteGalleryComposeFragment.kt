package com.system.location.service.ui.mock

import android.content.Intent
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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.fragment.app.viewModels
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.fragment.findNavController
import com.system.location.service.R
import com.system.location.service.core.runtime.RuntimePhase
import com.system.location.service.core.scenario.RouteMode
import com.system.location.service.designsystem.components.PortalActionRow
import com.system.location.service.designsystem.components.PortalCard
import com.system.location.service.designsystem.components.PortalSectionTitle
import com.system.location.service.designsystem.theme.PortalSpacing
import com.system.location.service.designsystem.theme.PortalTheme
import com.system.location.service.runtime.ScenarioRuntime
import com.system.location.service.ui.displayLabel
import com.system.location.service.ui.viewmodel.LibraryItem
import com.system.location.service.ui.viewmodel.LibraryKind
import com.system.location.service.ui.viewmodel.LibraryViewModel

/**
 * Compose route/scenario/location library. Persistence and scene construction stay in the existing
 * LibraryViewModel; SAF launchers stay in this Fragment host.
 */
class RouteGalleryComposeFragment : Fragment() {
    private val model by viewModels<LibraryViewModel>()
    private var importKind = LibraryKind.ROUTES
    private var exportKind = LibraryKind.ROUTES
    private var exportRouteId: String? = null

    private val importGpx = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) model.importGpx(uri)
    }
    private val exportGpx = registerForActivityResult(ActivityResultContracts.CreateDocument("application/gpx+xml")) { uri ->
        if (uri != null) model.exportGpx(uri, exportRouteId)
    }
    private val importFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) model.import(uri, importKind)
    }
    private val exportFile = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) model.export(uri, exportKind)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        importKind = runCatching { LibraryKind.valueOf(savedInstanceState?.getString("importKind") ?: "ROUTES") }.getOrDefault(LibraryKind.ROUTES)
        exportKind = runCatching { LibraryKind.valueOf(savedInstanceState?.getString("exportKind") ?: "ROUTES") }.getOrDefault(LibraryKind.ROUTES)
        exportRouteId = savedInstanceState?.getString("exportRouteId")
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = ComposeView(requireContext()).apply {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        setContent {
            PortalTheme {
                LibraryScreen(
                    model = model,
                    onAddRoute = { findNavController().navigate(R.id.nav_route_edit) },
                    onImportJson = { kind ->
                        importKind = kind
                        importFile.launch(arrayOf("application/json", "text/*"))
                    },
                    onExportJson = { kind ->
                        exportKind = kind
                        exportFile.launch("location-${kind.name.lowercase()}.json")
                    },
                    onImportGpx = { importGpx.launch(arrayOf("application/gpx+xml", "application/xml", "text/xml", "application/octet-stream")) },
                    onExportGpx = { routeId ->
                        exportRouteId = routeId
                        exportGpx.launch(if (routeId == null) "location-routes.gpx" else "route.gpx")
                    },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        model.refresh()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("importKind", importKind.name)
        outState.putString("exportKind", exportKind.name)
        outState.putString("exportRouteId", exportRouteId)
        super.onSaveInstanceState(outState)
    }
}

@Composable
private fun LibraryScreen(
    model: LibraryViewModel,
    onAddRoute: () -> Unit,
    onImportJson: (LibraryKind) -> Unit,
    onExportJson: (LibraryKind) -> Unit,
    onImportGpx: () -> Unit,
    onExportGpx: (String?) -> Unit,
) {
    val context = LocalContext.current
    val state by model.state.collectAsStateWithLifecycle()
    val runtime by ScenarioRuntime.state.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }
    var selectedItem by remember { mutableStateOf<LibraryItem?>(null) }
    var renameTarget by remember { mutableStateOf<LibraryItem?>(null) }
    var renameValue by remember { mutableStateOf("") }
    var renameIsCopy by remember { mutableStateOf(false) }
    var moreOpen by remember { mutableStateOf(false) }
    var modeTarget by remember { mutableStateOf<LibraryItem?>(null) }

    val filtered = remember(state.items, query) {
        if (query.isBlank()) state.items
        else state.items.filter { it.title.contains(query, ignoreCase = true) || it.detail.contains(query, ignoreCase = true) }
    }

    if (moreOpen) {
        val choices = buildList {
            add("导入 JSON")
            add("导出 JSON")
            if (state.kind == LibraryKind.ROUTES) {
                add("导入 GPX")
                add("导出 GPX")
            }
        }
        AlertDialog(
            onDismissRequest = { moreOpen = false },
            title = { Text("资料库操作") },
            text = {
                Column {
                    choices.forEach { choice ->
                        PortalActionRow(choice, onClick = {
                            moreOpen = false
                            when (choice) {
                                "导入 JSON" -> onImportJson(state.kind)
                                "导出 JSON" -> onExportJson(state.kind)
                                "导入 GPX" -> onImportGpx()
                                "导出 GPX" -> onExportGpx(null)
                            }
                        })
                    }
                }
            },
            confirmButton = { TextButton(onClick = { moreOpen = false }) { Text("取消") } },
        )
    }

    selectedItem?.let { item ->
        val actions = buildList {
            add("启动")
            add("重命名")
            add("复制")
            add("删除")
            if (state.kind != LibraryKind.SCENARIOS) add("收藏 / 取消收藏")
            if (state.kind != LibraryKind.LOCATIONS) add("播放模式")
            if (state.kind == LibraryKind.ROUTES) add("导出 GPX")
        }
        AlertDialog(
            onDismissRequest = { selectedItem = null },
            title = { Text(item.title) },
            text = {
                Column {
                    actions.forEach { action ->
                        PortalActionRow(action, onClick = {
                            selectedItem = null
                            when (action) {
                                "启动" -> model.start(item.id)
                                "重命名" -> {
                                    renameIsCopy = false
                                    renameTarget = item
                                    renameValue = item.title.removePrefix("★ ")
                                }
                                "复制" -> {
                                    renameIsCopy = true
                                    renameTarget = item
                                    renameValue = item.title.removePrefix("★ ") + " 副本"
                                }
                                "删除" -> model.delete(item.id)
                                "收藏 / 取消收藏" -> model.favorite(item.id)
                                "播放模式" -> modeTarget = item
                                "导出 GPX" -> onExportGpx(item.id)
                            }
                        })
                    }
                }
            },
            confirmButton = { TextButton(onClick = { selectedItem = null }) { Text("取消") } },
        )
    }

    renameTarget?.let { item ->
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text(if (renameIsCopy) "复制" else "重命名") },
            text = {
                OutlinedTextField(
                    value = renameValue,
                    onValueChange = { renameValue = it },
                    singleLine = true,
                    label = { Text("名称") },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val target = renameTarget
                    renameTarget = null
                    if (target != null && renameValue.isNotBlank()) {
                        if (renameIsCopy) model.copy(target.id, renameValue)
                        else model.rename(target.id, renameValue)
                    }
                }) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { renameTarget = null }) { Text("取消") } },
        )
    }

    modeTarget?.let { item ->
        AlertDialog(
            onDismissRequest = { modeTarget = null },
            title = { Text("播放模式") },
            text = {
                Column {
                    RouteMode.entries.forEach { mode ->
                        PortalActionRow(
                            when (mode) {
                                RouteMode.ONCE -> "单次"
                                RouteMode.LOOP -> "循环（闭合回起点）"
                                RouteMode.PING_PONG -> "往返"
                            },
                            onClick = {
                                modeTarget = null
                                model.mode(item.id, mode)
                            },
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = { modeTarget = null }) { Text("取消") } },
        )
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        LazyColumn(
            contentPadding = PaddingValues(20.dp, 24.dp, 20.dp, 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(
                    "管理路线、场景和保存的位置。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LibraryKind.entries.forEach { kind ->
                        FilterChip(
                            selected = state.kind == kind,
                            onClick = { model.kind(kind) },
                            enabled = !state.busy,
                            label = {
                                Text(when (kind) {
                                    LibraryKind.ROUTES -> "路线"
                                    LibraryKind.SCENARIOS -> "场景"
                                    LibraryKind.LOCATIONS -> "位置"
                                })
                            },
                        )
                    }
                }
            }
            item {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("搜索资料") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = onAddRoute,
                        enabled = !state.busy && state.kind == LibraryKind.ROUTES,
                        modifier = Modifier.weight(1f),
                    ) { Text("新建路线") }
                    OutlinedButton(onClick = { moreOpen = true }, enabled = !state.busy) {
                        Text("更多")
                    }
                }
            }
            if (state.busy) {
                item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            }
            item {
                Text(
                    state.message ?: if (filtered.isEmpty()) "暂无资料" else "共 ${filtered.size} 条",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (filtered.isEmpty()) {
                item {
                    PortalCard {
                        Text("这里还没有内容。", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "可以新建路线、保存位置，或从 JSON/GPX 导入。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else {
                filtered.forEach { item ->
                    item(key = "${state.kind}-${item.id}") {
                        PortalCard {
                            PortalActionRow(
                                title = item.title,
                                subtitle = item.detail,
                                onClick = { selectedItem = item },
                            )
                        }
                    }
                }
            }
            if (runtime.isActive || runtime.error != null) {
                item {
                    PortalCard {
                        PortalSectionTitle(
                            "${runtime.backend.displayLabel()} · ${runtime.phase.displayLabel()}",
                            runtime.scenarioName.orEmpty(),
                        )
                        Spacer(Modifier.height(PortalSpacing.sm))
                        runtime.error?.let { error ->
                            Text("${error.stage}: ${error.reason}", color = MaterialTheme.colorScheme.error)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = {
                                    if (runtime.phase == RuntimePhase.PAUSED) ScenarioRuntime.resume()
                                    else ScenarioRuntime.pause()
                                },
                                enabled = runtime.phase in setOf(RuntimePhase.RUNNING, RuntimePhase.PAUSED),
                                modifier = Modifier.weight(1f),
                            ) { Text(if (runtime.phase == RuntimePhase.PAUSED) "继续" else "暂停") }
                            OutlinedButton(
                                onClick = { ScenarioRuntime.stop() },
                                enabled = runtime.isActive || runtime.error != null,
                                modifier = Modifier.weight(1f),
                            ) { Text("停止") }
                        }
                    }
                }
            }
        }
    }
}

