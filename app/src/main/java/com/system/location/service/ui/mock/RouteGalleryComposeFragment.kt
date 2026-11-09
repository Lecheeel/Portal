package com.system.location.service.ui.mock


import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup

import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.ComposeView

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

    val state by model.state.collectAsStateWithLifecycle()
    val runtime by ScenarioRuntime.state.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
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
            title = { Text(item.title.removePrefix("★ ")) },
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
            modifier = Modifier.fillMaxSize().imePadding(),
            contentPadding = PaddingValues(PortalSpacing.xl, PortalSpacing.xl, PortalSpacing.xl, 40.dp),
            verticalArrangement = Arrangement.spacedBy(PortalSpacing.md),
        ) {
            item(key = "categories") {
                PrimaryTabRow(
                    selectedTabIndex = state.kind.ordinal,
                    containerColor = MaterialTheme.colorScheme.background,
                    divider = {},
                ) {
                    LibraryKind.entries.forEach { kind ->
                        Tab(
                            selected = state.kind == kind,
                            onClick = { if (state.kind != kind) model.kind(kind) },
                            enabled = !state.busy,
                            text = { Text(kind.libraryLabel(), style = MaterialTheme.typography.titleSmall) },
                            icon = { Icon(painterResource(kind.libraryIcon()), null, Modifier.size(22.dp)) },
                        )
                    }
                }
            }
            item(key = "search") {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("搜索资料") },
                    leadingIcon = { Icon(painterResource(R.drawable.baseline_search_24), null) },
                    trailingIcon = {
                        if (query.isNotEmpty()) {
                            IconButton(onClick = { query = "" }) {
                                Icon(painterResource(R.drawable.baseline_close_24), "清除搜索")
                            }
                        }
                    },
                    shape = MaterialTheme.shapes.medium,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item(key = "actions") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = onAddRoute,
                        enabled = !state.busy && state.kind == LibraryKind.ROUTES,
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                    ) {
                        Icon(painterResource(R.drawable.baseline_route_24), null, Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("新建路线")
                    }
                    OutlinedButton(onClick = { moreOpen = true }, enabled = !state.busy, modifier = Modifier.heightIn(min = 48.dp)) {
                        Icon(painterResource(R.drawable.ic_more_horizontal), null, Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("更多")
                    }
                }
            }
            item(key = "progress") {
                AnimatedVisibility(visible = state.busy) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            }
            item(key = "summary") {
                Text(
                    state.message ?: "共 ${filtered.size} 条",
                    modifier = Modifier.animateContentSize(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (filtered.isEmpty() && !state.busy) {
                item(key = "empty") {
                    PortalCard {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.primaryContainer) {
                                Icon(
                                    painterResource(if (query.isNotBlank()) R.drawable.baseline_search_24 else state.kind.libraryIcon()),
                                    null,
                                    Modifier.size(72.dp).padding(20.dp),
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                )
                            }
                            Text(
                                if (query.isNotBlank()) "没有匹配的资料" else "暂无${state.kind.libraryLabel()}",
                                style = MaterialTheme.typography.titleMedium,
                            )
                        }
                    }
                }
            }
            items(filtered, key = { "${state.kind}-${it.id}" }) { item ->
                LibraryEntryCard(
                    item = item,
                    kind = state.kind,
                    onClick = { selectedItem = item },
                    modifier = Modifier.animateItem(),
                )
            }
            item(key = "runtime") {
                AnimatedVisibility(visible = runtime.isActive || runtime.error != null) {
                    PortalCard(modifier = Modifier.animateContentSize()) {
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
                            ) {
                                Icon(painterResource(R.drawable.baseline_stop_24), null, Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("停止")
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun LibraryKind.libraryLabel(): String = when (this) {
    LibraryKind.ROUTES -> "路线"
    LibraryKind.SCENARIOS -> "场景"
    LibraryKind.LOCATIONS -> "位置"
}

private fun LibraryKind.libraryIcon(): Int = when (this) {
    LibraryKind.ROUTES -> R.drawable.baseline_route_24
    LibraryKind.SCENARIOS -> R.drawable.ic_menu_slideshow
    LibraryKind.LOCATIONS -> R.drawable.baseline_location_on_24
}

private val FavoriteIcon: ImageVector by lazy {
    ImageVector.Builder("Favorite", 24.dp, 24.dp, 24f, 24f).apply {
        path(fill = SolidColor(Color.Black)) {
            moveTo(12f, 2f)
            lineTo(15.09f, 8.26f)
            lineTo(22f, 9.27f)
            lineTo(17f, 14.14f)
            lineTo(18.18f, 21.02f)
            lineTo(12f, 17.77f)
            lineTo(5.82f, 21.02f)
            lineTo(7f, 14.14f)
            lineTo(2f, 9.27f)
            lineTo(8.91f, 8.26f)
            close()
        }
    }.build()
}

@Composable
private fun LibraryEntryCard(
    item: LibraryItem,
    kind: LibraryKind,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(PortalSpacing.lg),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(PortalSpacing.md),
        ) {
            Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.primaryContainer) {
                Icon(
                    painterResource(kind.libraryIcon()), null,
                    Modifier.size(44.dp).padding(11.dp),
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    item.title.removePrefix("★ "),
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    item.detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (item.title.startsWith("★ ")) {
                Icon(FavoriteIcon, "已收藏", Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
            }
            Icon(
                painterResource(R.drawable.ic_more_horizontal), "资料操作",
                Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

