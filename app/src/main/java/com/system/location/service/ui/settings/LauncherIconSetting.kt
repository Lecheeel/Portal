package com.system.location.service.ui.settings

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.system.location.service.designsystem.components.PortalActionRow

@Composable
internal fun LauncherIconSetting(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var selected by remember { mutableStateOf(LauncherIcon.current(context)) }
    var showPicker by rememberSaveable { mutableStateOf(false) }
    var draft by rememberSaveable { mutableStateOf(selected.name) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    PortalActionRow("应用图标", selected.label, onClick = {
        selected = LauncherIcon.current(context)
        draft = selected.name
        error = null
        showPicker = true
    }, modifier = modifier, status = "桌面图标可能稍后刷新")
    if (showPicker) AlertDialog(
        onDismissRequest = { showPicker = false },
        title = { Text("应用图标") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                LauncherIcon.entries.forEach { icon ->
                    Row(Modifier.fillMaxWidth()
                        .selectable(draft == icon.name, role = Role.RadioButton, onClick = { draft = icon.name; error = null })
                        .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Surface(shape = MaterialTheme.shapes.medium, color = colorResource(icon.background)) {
                            // Adaptive launchers show the central 72dp of the 108dp foreground.
                            Box(Modifier.size(64.dp), contentAlignment = Alignment.Center) {
                                Image(painterResource(icon.preview), contentDescription = null, modifier = Modifier.requiredSize(96.dp))
                            }
                        }
                        Text(icon.label, modifier = Modifier.weight(1f))
                        RadioButton(selected = draft == icon.name, onClick = null)
                    }
                }
                Text("更换桌面图标不会修改定位或运行设置。部分桌面需要稍候刷新。",
                    style = MaterialTheme.typography.bodySmall)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = { TextButton(onClick = {
            runCatching { LauncherIcon.select(context, LauncherIcon.valueOf(draft)) }
                .onSuccess { selected = LauncherIcon.current(context); showPicker = false }
                .onFailure { error = "无法更换图标，请重试" }
        }) { Text("应用") } },
        dismissButton = { TextButton(onClick = { showPicker = false }) { Text("取消") } },
    )
}
