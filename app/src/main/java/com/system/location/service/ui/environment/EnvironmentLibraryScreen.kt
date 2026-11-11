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
import com.system.location.service.designsystem.components.PortalActionRow
import com.system.location.service.designsystem.components.PortalSettingGroup

internal data class EnvironmentLibraryState(
    val names: List<String> = emptyList(),
    val loading: Boolean = true,
    val error: String? = null,
)

@Composable
internal fun EnvironmentLibraryScreen(
    state: EnvironmentLibraryState,
    onRetry: () -> Unit,
    onExport: (String) -> Unit,
    onReplay: (String) -> Unit,
    onDelete: (String) -> Unit,
    onRename: (String, String) -> Unit,
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
            PortalSettingGroup("环境操作", 4) { index, modifier ->
                when (index) {
                    0 -> PortalActionRow("设置为回放环境", onClick = { action = null; onReplay(name) }, modifier = modifier)
                    1 -> PortalActionRow("导出环境 JSON", onClick = { action = null; onExport(name) }, modifier = modifier)
                    2 -> PortalActionRow("重命名", onClick = { action = null; renaming = name; nameInput = name }, modifier = modifier)
                    3 -> PortalActionRow("删除", onClick = { action = null; deleting = name }, modifier = modifier,
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
}
