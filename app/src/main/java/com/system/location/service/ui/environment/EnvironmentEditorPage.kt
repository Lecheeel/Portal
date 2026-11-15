package com.system.location.service.ui.environment

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

internal enum class EnvironmentDataKind(val label: String) {
    WIFI("Wi-Fi"), BLE("蓝牙"), CELL("蜂窝"), CDMA("CDMA")
}

/** A full page with fixed navigation/actions and a keyboard-aware scrolling body. */
@Composable
internal fun EnvironmentEditorPage(
    onBack: () -> Unit,
    title: @Composable () -> Unit,
    text: @Composable () -> Unit,
    confirmButton: @Composable () -> Unit,
    dismissButton: @Composable () -> Unit,
) {
    BackHandler(onBack = onBack)
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().imePadding().padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth().padding(top = 12.dp),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) {
                    ProvideTextStyle(MaterialTheme.typography.titleLarge) { title() }
                }
                dismissButton()
            }
            HorizontalDivider()
            Box(Modifier.weight(1f).fillMaxWidth()) { text() }
            Surface(Modifier.fillMaxWidth().padding(bottom = 16.dp), shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.primaryContainer) {
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), horizontalArrangement = Arrangement.End,
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) { confirmButton() }
            }
        }
    }
}
