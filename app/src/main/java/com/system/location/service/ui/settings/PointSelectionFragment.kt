package com.system.location.service.ui.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.dp
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.system.location.service.R
import com.system.location.service.core.repository.SavedLocation
import com.system.location.service.core.routing.RoutingConfig
import com.system.location.service.data.repository.LibraryRepositories
import com.system.location.service.designsystem.components.PortalActionRow
import com.system.location.service.designsystem.theme.PortalTheme
import com.system.location.service.ext.routingJson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Choosing a saved point only opens an application draft; it never changes live rules. */
class PointSelectionFragment : Fragment() {
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                PortalTheme {
                    var query by rememberSaveable { mutableStateOf("") }
                    var generation by remember { mutableIntStateOf(0) }
                    var loading by remember { mutableStateOf(true) }
                    var error by remember { mutableStateOf<String?>(null) }
                    var locations by remember { mutableStateOf<List<SavedLocation>>(emptyList()) }
                    var routing by remember { mutableStateOf(RoutingConfig()) }
                    LaunchedEffect(generation) {
                        loading = true
                        error = null
                        runCatching {
                            withContext(Dispatchers.IO) {
                                LibraryRepositories.ensureMigrated()
                                LibraryRepositories.locations.list().sortedBy { it.name.lowercase() } to
                                    readRoutingSettings(context.routingJson)
                            }
                        }.onSuccess { (points, rules) -> locations = points; routing = rules }
                            .onFailure {
                                if (it is CancellationException) throw it
                                error = "无法读取保存地点或应用规则，原有配置未修改"
                            }
                        loading = false
                    }
                    val visible = remember(query, locations) {
                        locations.filter { it.name.contains(query, true) || it.address.contains(query, true) }
                    }
                    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("先选择保存地点，再选择使用该位置的应用。保存应用选择后才会更新规则。",
                            style = MaterialTheme.typography.bodyMedium)
                        OutlinedTextField(query, { query = it }, label = { Text("搜索地点名称或地址") },
                            modifier = Modifier.fillMaxWidth(), singleLine = true)
                        LazyColumn(Modifier.weight(1f).fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            contentPadding = PaddingValues(bottom = 16.dp)) {
                            when {
                                loading -> item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                                error != null -> item {
                                    Text(error!!, color = MaterialTheme.colorScheme.error)
                                    TextButton(onClick = { generation++ }) { Text("重新加载") }
                                }
                                locations.isEmpty() -> item {
                                    Text("还没有保存地点")
                                    Text("请先在地图中保存一个地点，再来分配应用。",
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                visible.isEmpty() -> item { Text("没有符合条件的地点") }
                                else -> items(visible, key = { it.id }) { point ->
                                    val assigned = routing.routes.count { it.point == point.coordinate }
                                    PortalActionRow(point.name, buildString {
                                        if (point.address.isNotBlank()) append(point.address).append('\n')
                                        append(point.coordinate.latitude).append(", ").append(point.coordinate.longitude)
                                        append(" · 已分配 ").append(assigned).append(" 个应用")
                                    }, onClick = {
                                        findNavController().navigate(R.id.nav_app_selection, Bundle().apply {
                                            putString("purpose", "point")
                                            putString("title", "固定位置 · ${point.name}")
                                            putDouble("latitude", point.coordinate.latitude)
                                            putDouble("longitude", point.coordinate.longitude)
                                            putStringArrayList("selected", ArrayList(routing.routes
                                                .filter { it.point == point.coordinate }.map { it.packageName }))
                                        })
                                    })
                                }
                            }
                        }
                    }
                }
            }
        }
}
