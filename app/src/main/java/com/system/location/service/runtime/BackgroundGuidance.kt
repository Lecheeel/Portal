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
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.system.location.service.R

object BackgroundGuidance {
    fun status(context: Context): String {
        val battery = context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)
        val location = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val notification = NotificationManagerCompat.from(context).areNotificationsEnabled()
        return context.getString(R.string.background_status, Build.MANUFACTURER, Build.MODEL,
            context.getString(if (battery) R.string.battery_unrestricted else R.string.battery_restricted),
            context.getString(if (location) R.string.permission_granted else R.string.permission_missing),
            context.getString(if (notification) R.string.permission_granted else R.string.permission_missing))
    }
    fun show(context: Context) {
        MaterialAlertDialogBuilder(context).setTitle(R.string.background_guidance)
            .setMessage(status(context) + "\n\n" + context.getString(R.string.background_guidance_desc))
            .setNegativeButton(android.R.string.cancel, null)
            .setNeutralButton(R.string.battery_settings) { _, _ -> open(context, listOf(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS), details(context))) }
            .setPositiveButton(R.string.background_settings) { _, _ ->
                MaterialAlertDialogBuilder(context).setTitle(R.string.background_settings)
                    .setItems(context.resources.getStringArray(R.array.background_settings_options)) { _, index ->
                        when (index) {
                            0 -> open(context, listOf(Intent().setComponent(ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")), details(context)))
                            1 -> open(context, listOf(details(context)))
                            2 -> open(context, listOf(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName), details(context)))
                        }
                    }.show()
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
