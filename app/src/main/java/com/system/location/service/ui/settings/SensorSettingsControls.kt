package com.system.location.service.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import com.system.location.service.core.sensors.SensorTargets
import com.system.location.service.designsystem.components.PortalActionRow
import com.system.location.service.designsystem.components.PortalCard
import com.system.location.service.designsystem.components.PortalSectionTitle

@Composable
fun SensorSettingsControls(targets: Set<String>, onPick: () -> Unit, onSave: (Set<String>) -> Unit) {
    var editing by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    PortalCard {
        PortalSectionTitle("应用传感器（实验）", "需 Xposed：在框架中同时勾选目标应用并重启应用。跟随路线模拟，不需要启用 Native；标准无 Root 模式不支持。")
        PortalActionRow("选择应用", if (targets.isEmpty()) "未启用" else "已选择 ${targets.size} 个应用", onPick)
        PortalActionRow("编辑包名", "支持无启动图标的应用；清空可关闭", {
            text = targets.sorted().joinToString("\n")
            error = null
            editing = true
        })
    }
    if (editing) AlertDialog(
        onDismissRequest = { editing = false },
        title = { Text("传感器目标应用") },
        text = {
            Column {
                OutlinedTextField(value = text, onValueChange = { text = it; error = null },
                    label = { Text("每行一个完整包名") }, minLines = 3, maxLines = 8, isError = error != null)
                error?.let { Text(it) }
            }
        },
        confirmButton = { TextButton(onClick = {
            runCatching { SensorTargets.parse(text) }
                .onSuccess { onSave(it); editing = false }
                .onFailure { error = "请填写完整包名，最多 128 个" }
        }) { Text("保存") } },
        dismissButton = { TextButton(onClick = { editing = false }) { Text("取消") } },
    )
}
