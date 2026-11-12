package com.system.location.service.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.system.location.service.R
import com.system.location.service.designsystem.components.PortalActionRow
import com.system.location.service.designsystem.components.PortalSettingGroup
import com.system.location.service.designsystem.components.PortalSectionTitle

internal enum class SettingsCategory(val title: String, val summary: String, val destination: Int, val icon: Int) {
    LOCATION("地图与定位", "地图服务、海拔、精度与上报间隔", R.id.nav_settings_location, R.drawable.ic_portal_map),
    MOTION("运动与摇杆", "速度、路线平滑与物理漂移", R.id.nav_settings_motion, R.drawable.ic_portal_motion),
    GNSS("卫星与 GNSS", "卫星生成、数量策略与接收环境", R.id.nav_settings_gnss, R.drawable.ic_portal_satellite),
    APPS("分应用规则", "应用路由、固定点与传感器目标", R.id.nav_settings_apps, R.drawable.ic_portal_apps),
    SYSTEM("系统与环境模拟", "SIM、Wi-Fi、网络类型与环境配置", R.id.nav_settings_system, R.drawable.ic_portal_system),
    ADVANCED("高级与实验", "定位通道、兼容选项与实验功能", R.id.nav_settings_advanced, R.drawable.ic_portal_experiment),
    PRIVACY("后台与隐私", "后台运行与崩溃报告", R.id.nav_settings_privacy, R.drawable.ic_portal_privacy),
    UPDATES("关于与更新", "应用图标、检查更新与更新通道", R.id.nav_settings_updates, R.drawable.ic_portal_update),
}

@Composable
internal fun SettingsDirectory(
    onCategory: (SettingsCategory) -> Unit,
    hooksEnabled: Boolean,
    mapProvider: String,
    updateChannel: String,
) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        PortalSectionTitle("偏好与服务", "按使用场景管理设置与模拟能力")
        listOf(
            "常用设置" to listOf(SettingsCategory.LOCATION, SettingsCategory.MOTION),
            "模拟能力" to listOf(SettingsCategory.GNSS, SettingsCategory.APPS, SettingsCategory.SYSTEM, SettingsCategory.ADVANCED),
            "应用" to listOf(SettingsCategory.PRIVACY, SettingsCategory.UPDATES),
        ).forEach { (title, categories) ->
            PortalSettingGroup(title, categories.size) { index, rowModifier ->
                val category = categories[index]
                val status = when (category) {
                    SettingsCategory.LOCATION -> "当前地图：$mapProvider"
                    SettingsCategory.GNSS, SettingsCategory.SYSTEM -> if (hooksEnabled) "Xposed / Native" else "需 Xposed / Native"
                    SettingsCategory.APPS -> "应用传感器需 Xposed"
                    SettingsCategory.UPDATES -> "当前通道：$updateChannel"
                    else -> null
                }
                PortalActionRow(
                    title = category.title,
                    modifier = rowModifier,
                    subtitle = listOfNotNull(category.summary, status).joinToString("\n"),
                    onClick = { onCategory(category) },
                    leadingContent = {
                        Icon(painterResource(category.icon), null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary)
                    },
                )
            }
        }
    }
}
