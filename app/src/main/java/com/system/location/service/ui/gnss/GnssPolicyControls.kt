package com.system.location.service.ui.gnss

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.edit
import com.system.location.service.core.gnss.GnssMinimumPolicy
import com.system.location.service.core.gnss.GnssSimulationEnvironment
import com.system.location.service.core.gnss.GnssSimulationMode
import com.system.location.service.designsystem.components.PortalActionRow
import com.system.location.service.ext.sharedPrefs
import com.system.location.service.ui.preferenceRevision

/** Uses the same persisted enum names as the authenticated runtime configuration. */
@Composable
internal fun GnssPolicyControls(enabled: Boolean, onChanged: () -> Unit) {
    val context = LocalContext.current
    val revision = preferenceRevision()
    val preferences = remember(context, revision) { context.sharedPrefs.all }
    var selection by remember { mutableStateOf<String?>(null) }
    val modes = listOf(
        GnssSimulationMode.AUTO.name to "自动：目标天空 + 可选实测信号校准",
        GnssSimulationMode.AUTONOMOUS.name to "自主：目标天空，不使用本机采集校准",
        GnssSimulationMode.REPLAY.name to "实测回放：复现采集地点与时段",
        GnssSimulationMode.OFF.name to "关闭：保留真实卫星回调",
    )
    val quantities = listOf(
        GnssMinimumPolicy.PHYSICAL_FIRST.name to "物理优先：不足时报告实际数量",
        GnssMinimumPolicy.GUARANTEE_QUANTITY.name to "数量保证：在输出容量内用虚拟卫星补足",
    )
    val environments = listOf(
        GnssSimulationEnvironment.OPEN_SKY.name to "开阔天空",
        GnssSimulationEnvironment.URBAN.name to "城市遮挡",
        GnssSimulationEnvironment.INDOOR.name to "室内衰减",
    )
    val settings = listOf(
        Triple("gnssSimulationMode", "卫星生成模式", modes),
        Triple("gnssMinimumPolicy", "卫星数量策略", quantities),
        Triple("gnssSimulationEnvironment", "模拟接收环境", environments),
    )
    for ((key, title, options) in settings) {
        val value = preferences[key] as? String ?: options.first().first
        val label = options.firstOrNull { it.first == value }?.second ?: "未知设置：$value"
        PortalActionRow(title, label, onClick = { selection = key }, enabled = enabled)
    }
    if (selection != null) {
        val (key, title, options) = settings.single { it.first == selection }
        AlertDialog(
            onDismissRequest = { selection = null },
            title = { Text(title) },
            text = {
                androidx.compose.foundation.layout.Column {
                    options.forEach { (value, label) ->
                        TextButton(enabled = enabled, onClick = {
                            context.sharedPrefs.edit { putString(key, value) }
                            selection = null
                            onChanged()
                        }) { Text(label) }
                    }
                    if (key == "gnssSimulationEnvironment") Text("模拟环境与手机实际摆放环境相互独立；室内场景可能没有可用定位解。")
                    if (key == "gnssMinimumPolicy") Text("下限针对可见数量；跟踪数、参与定位数和 DOP 仍独立计算。实测回放不补入其他天空。")
                    if (key == "gnssSimulationMode") Text("自动和自主模式均无需采集。自动模式可迁移有效画像的信号特征，不搬用采集地天空，也不代表模拟地点的真实接收情况。")
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { selection = null }) { Text("取消") } },
        )
    }
}
