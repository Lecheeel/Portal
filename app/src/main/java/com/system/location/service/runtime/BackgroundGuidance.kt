package com.system.location.service.runtime

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import com.system.location.service.ui.PortalDialog
import com.system.location.service.ui.PortalDialogContent
import com.system.location.service.ui.showPortalChoiceDialog
import com.system.location.service.R

object BackgroundGuidance {
    private data class Status(val batteryUnrestricted: Boolean, val locationGranted: Boolean, val notificationsEnabled: Boolean)

    private fun inspect(context: Context) = Status(
        batteryUnrestricted = context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName),
        locationGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED,
        notificationsEnabled = NotificationManagerCompat.from(context).areNotificationsEnabled(),
    )

    fun status(context: Context): String {
        val state = inspect(context)
        return context.getString(R.string.background_status, Build.MANUFACTURER, Build.MODEL,
            context.getString(if (state.batteryUnrestricted) R.string.battery_unrestricted else R.string.battery_restricted),
            context.getString(if (state.locationGranted) R.string.permission_granted else R.string.permission_missing),
            context.getString(if (state.notificationsEnabled) R.string.permission_granted else R.string.permission_missing))
    }

    fun diagnosticStatus(context: Context): String {
        val state = inspect(context)
        return "后台状态：电池优化豁免=${state.batteryUnrestricted}；精确位置权限=${state.locationGranted}；通知=${state.notificationsEnabled}"
    }
    fun show(context: Context) {
        val dialog = PortalDialog(context)
        dialog.content {
            PortalDialogContent(context.getString(R.string.background_guidance),
                status(context) + "\n\n" + context.getString(R.string.background_guidance_desc), buttons = {
                    TextButton(onClick = { dialog.dismiss() }) { Text(context.getString(android.R.string.cancel)) }
                    TextButton(onClick = {
                        dialog.dismiss()
                        showPortalChoiceDialog(context, context.getString(R.string.background_settings),
                            context.resources.getStringArray(R.array.background_settings_options).toList()) { index ->
                            when (index) {
                                0 -> open(context, listOf(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS), details(context)))
                                1 -> open(context, listOf(Intent().setComponent(ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")), details(context)))
                                2 -> open(context, listOf(details(context)))
                                3 -> open(context, listOf(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName), details(context)))
                            }
                        }
                    }) { Text(context.getString(R.string.open_background_settings)) }
                })
        }.show()
    }

    private fun details(context: Context) = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
    private fun open(context: Context, candidates: List<Intent>) {
        for (intent in candidates) {
            try { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return }
            catch (_: android.content.ActivityNotFoundException) { }
            catch (_: SecurityException) { }
        }
        Toast.makeText(context, R.string.settings_unavailable, Toast.LENGTH_LONG).show()
    }
}
