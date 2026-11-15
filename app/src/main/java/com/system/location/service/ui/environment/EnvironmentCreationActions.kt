package com.system.location.service.ui.environment

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.system.location.service.R
import com.system.location.service.designsystem.components.PortalCard

internal data class EnvironmentCaptureState(
    val busy: Boolean = false,
    val message: String? = null,
    val failed: Boolean = false,
)

/** Shared by the simulation workspace and the environment library. */
@Composable
internal fun EnvironmentCreationActions(
    onCreate: () -> Unit,
    onCapture: () -> Unit,
    onImport: () -> Unit,
    capture: EnvironmentCaptureState,
    onCancelCapture: () -> Unit,
) {
    PortalCard(modifier = Modifier.animateContentSize()) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.primaryContainer) {
                Icon(painterResource(R.drawable.ic_layers_modern), null, Modifier.size(44.dp).padding(10.dp),
                    tint = MaterialTheme.colorScheme.onPrimaryContainer)
            }
            Column {
                Text("创建环境", style = MaterialTheme.typography.titleLarge)
                Text("Wi-Fi · 蓝牙信标 · 蜂窝小区", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onCreate, enabled = !capture.busy, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("手动新建") }
            OutlinedButton(onCapture, enabled = !capture.busy, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                Text(if (capture.busy) "正在采集" else "采集当前环境")
            }
        }
        TextButton(onImport, enabled = !capture.busy) { Text("导入环境 JSON") }
        AnimatedVisibility(capture.message != null) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (capture.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(capture.message.orEmpty(), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
                        color = if (capture.failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                    if (capture.busy) TextButton(onCancelCapture) { Text("取消采集") }
                }
                if (capture.busy) Text("首次定位和无线扫描可能需要约 30 秒，请保持定位、Wi-Fi 与蓝牙开启。",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
