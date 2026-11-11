package com.system.location.service.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import com.system.location.service.designsystem.components.PortalActionRow
import com.system.location.service.designsystem.components.PortalSettingGroup

@Composable
fun SensorSettingsControls(targets: Set<String>, onPick: () -> Unit, onEdit: () -> Unit) {
    Column(verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
        Text("需 Xposed：在框架中同时勾选目标应用并重启应用。跟随路线模拟，不需要启用 Native；标准无 Root 模式不支持。",
            style = androidx.compose.material3.MaterialTheme.typography.bodySmall)
        PortalSettingGroup("应用传感器（实验）", 2) { index, rowModifier ->
            when (index) {
                0 -> PortalActionRow("选择应用", if (targets.isEmpty()) "未启用" else "已选择 ${targets.size} 个应用",
                    onPick, modifier = rowModifier, status = "需重启目标应用")
                1 -> PortalActionRow("编辑包名", "支持无启动图标的应用；清空可关闭", onEdit, modifier = rowModifier, status = "需重启目标应用")
            }
        }
    }
}
