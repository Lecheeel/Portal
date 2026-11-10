package com.system.location.service.ui.home

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.system.location.service.core.repository.SavedLocation
import com.system.location.service.core.repository.SavedRoute
import com.system.location.service.core.scenario.RouteMode

internal data class HomeLibraryState(
    val routes: List<SavedRoute> = emptyList(),
    val locations: List<SavedLocation> = emptyList(),
    val busy: Boolean = false,
    val error: String? = null,
)

internal fun RouteMode.homeLabel() = when (this) {
    RouteMode.ONCE -> "单次"
    RouteMode.LOOP -> "循环"
    RouteMode.PING_PONG -> "往返"
}

@Composable
internal fun HomeTargetPicker(
    library: HomeLibraryState,
    onSelect: (HomeSelection) -> Unit,
    onDismiss: () -> Unit,
    onRetry: () -> Unit,
    onCreateRoute: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var routes by rememberSaveable { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("已保存地点与路线") },
        text = {
            Column(modifier.fillMaxWidth().testTag("home-target-picker")) {
                PrimaryTabRow(selectedTabIndex = if (routes) 1 else 0) {
                    Tab(selected = !routes, onClick = { routes = false }, text = { Text("地点") })
                    Tab(selected = routes, onClick = { routes = true }, text = { Text("路线") })
                }
                if (library.busy) {
                    Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        CircularProgressIndicator(Modifier.size(24.dp))
                        Text("正在加载资料库")
                    }
                } else if (library.error != null) {
                    Text(library.error, Modifier.padding(vertical = 16.dp))
                    TextButton(onClick = onRetry) { Text("重试") }
                } else {
                    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 320.dp)) {
                        if (routes) {
                            if (library.routes.isEmpty()) item { Text("暂无已保存路线，可创建路线或在资料库导入。", Modifier.padding(16.dp)) }
                            items(library.routes, key = { it.route.id }) { saved ->
                                TextButton(onClick = { onSelect(HomeSelection.Route(saved)) }, modifier = Modifier.fillMaxWidth()) {
                                    Column(Modifier.fillMaxWidth()) {
                                        Text(saved.route.name, style = MaterialTheme.typography.titleSmall)
                                        Text("${saved.route.points.size} 个点 · ${saved.mode.homeLabel()}", style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                            }
                        } else {
                            if (library.locations.isEmpty()) item { Text("暂无已保存地点，可点击地图或搜索后保存。", Modifier.padding(16.dp)) }
                            items(library.locations, key = { it.id }) { saved ->
                                TextButton(onClick = { onSelect(HomeSelection.Point(saved.coordinate, saved.name, saved.id)) }, modifier = Modifier.fillMaxWidth()) {
                                    Column(Modifier.fillMaxWidth()) {
                                        Text(saved.name, style = MaterialTheme.typography.titleSmall)
                                        Text(saved.address.ifBlank { "${saved.coordinate.latitude}, ${saved.coordinate.longitude}" }, style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                            }
                        }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onClick = onCreateRoute) { Text("创建路线") }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}
