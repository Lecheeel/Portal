package com.system.location.service.ui.runtime

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WifiManager
import android.provider.Settings
import android.widget.Toast
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.system.location.service.R
import com.system.location.service.core.backend.BackendType
import com.system.location.service.core.runtime.RuntimePhase
import com.system.location.service.core.runtime.RuntimeState
import com.system.location.service.runtime.ScenarioRuntime
import com.system.location.service.ui.PortalDialogContent

@Composable
internal fun MockWirelessReminderDialog(runtime: RuntimeState) {
    val pending by ScenarioRuntime.mockWirelessReminder.collectAsStateWithLifecycle()
    val reminder = pending ?: return
    if (runtime.backend != BackendType.MOCK_PROVIDER || runtime.scenarioId != reminder.scenarioId ||
        runtime.startedAt != reminder.startedAt || runtime.phase !in setOf(
            RuntimePhase.RUNNING, RuntimePhase.HOLDING, RuntimePhase.PAUSED)) return

    val context = LocalContext.current
    var radios by remember(reminder) { mutableStateOf<WirelessRadios?>(null) }
    val dismiss = { ScenarioRuntime.dismissMockWirelessReminder(reminder) }
    LifecycleResumeEffect(reminder) {
        fun refresh() {
            val current = WirelessRadios.read(context)
            radios = current
            if (current.wifiOff && current.bluetoothOff) dismiss()
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) = refresh()
        }
        val filter = IntentFilter(WifiManager.WIFI_STATE_CHANGED_ACTION).apply {
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
        }
        // Bluetooth broadcasts may originate from a privileged process with a different UID.
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
        refresh()
        onPauseOrDispose { context.unregisterReceiver(receiver) }
    }

    val current = radios ?: return
    if (current.wifiOff && current.bluetoothOff) return
    Dialog(onDismissRequest = dismiss) {
        PortalDialogContent(
            title = stringResource(R.string.mock_wireless_title),
            message = stringResource(R.string.mock_wireless_message),
            buttons = {
                TextButton(onClick = dismiss) { Text(stringResource(R.string.mock_wireless_ignore)) }
                if (!current.wifiOff) {
                    TextButton(onClick = { context.openRadioSettings(Settings.ACTION_WIFI_SETTINGS) }) {
                        Text(stringResource(R.string.mock_wireless_wifi_settings))
                    }
                }
                if (!current.bluetoothOff) {
                    TextButton(onClick = { context.openRadioSettings(Settings.ACTION_BLUETOOTH_SETTINGS) }) {
                        Text(stringResource(R.string.mock_wireless_bluetooth_settings))
                    }
                }
            },
        )
    }
}

private data class WirelessRadios(val wifiOff: Boolean, val bluetoothOff: Boolean) {
    companion object {
        fun read(context: Context): WirelessRadios {
            val wifiOff = runCatching {
                val manager = context.applicationContext.getSystemService(WifiManager::class.java)
                manager == null || manager.wifiState == WifiManager.WIFI_STATE_DISABLED
            }.getOrDefault(false)
            val bluetoothOff = runCatching {
                val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
                adapter == null || adapter.state == BluetoothAdapter.STATE_OFF
            }.getOrDefault(false)
            return WirelessRadios(wifiOff, bluetoothOff)
        }
    }
}

private fun Context.openRadioSettings(action: String) {
    val opened = runCatching { startActivity(Intent(action)) }.isSuccess ||
        runCatching { startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS)) }.isSuccess
    if (!opened) Toast.makeText(this, R.string.mock_wireless_settings_unavailable, Toast.LENGTH_SHORT).show()
}
