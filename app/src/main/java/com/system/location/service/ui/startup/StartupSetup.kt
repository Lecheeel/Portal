package com.system.location.service.ui.startup

import android.Manifest.permission.ACCESS_COARSE_LOCATION
import android.Manifest.permission.ACCESS_FINE_LOCATION
import android.Manifest.permission.POST_NOTIFICATIONS
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.system.location.service.R
import com.system.location.service.maps.MapProvider
import com.system.location.service.runtime.BackgroundGuidance

@Composable
internal fun StartupSetup(activity: Activity, onComplete: (MapProvider) -> Boolean) {
    val supportsNotifications = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
    val isXiaomi = BackgroundGuidance.isXiaomiFamily
    var locationGranted by remember { mutableStateOf(activity.hasPermission(ACCESS_FINE_LOCATION)) }
    var notificationGranted by remember { mutableStateOf(NotificationManagerCompat.from(activity).areNotificationsEnabled()) }
    var batteryExempt by remember { mutableStateOf(BackgroundGuidance.isBatteryExempt(activity)) }
    var xiaomiBackgroundRestricted by remember { mutableStateOf(isXiaomi && BackgroundGuidance.isRestricted(activity)) }
    var locationRequested by rememberSaveable { mutableStateOf(false) }
    var notificationRequested by rememberSaveable { mutableStateOf(false) }
    var skipBackground by rememberSaveable { mutableStateOf(false) }
    var xiaomiBatteryOpened by rememberSaveable { mutableStateOf(false) }
    var xiaomiAutostartOpened by rememberSaveable { mutableStateOf(false) }
    var selected by rememberSaveable { mutableStateOf<MapProvider?>(null) }
    var mapError by rememberSaveable { mutableStateOf(false) }

    fun refreshStatus() {
        locationGranted = activity.hasPermission(ACCESS_FINE_LOCATION)
        notificationGranted = NotificationManagerCompat.from(activity).areNotificationsEnabled()
        batteryExempt = BackgroundGuidance.isBatteryExempt(activity)
        xiaomiBackgroundRestricted = isXiaomi && BackgroundGuidance.isRestricted(activity)
    }
    val requestPermissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result.containsKey(ACCESS_FINE_LOCATION)) locationRequested = true
        if (result.containsKey(POST_NOTIFICATIONS)) notificationRequested = true
        refreshStatus()
        if (locationGranted && notificationGranted && !batteryExempt && !skipBackground) {
            BackgroundGuidance.openBatterySettings(activity, requestExemption = true)
        }
    }
    LifecycleResumeEffect(activity) {
        refreshStatus()
        onPauseOrDispose { }
    }

    val location = activity.permissionState(ACCESS_FINE_LOCATION, locationGranted, locationRequested)
    val notifications = when {
        notificationGranted -> SetupPermission.GRANTED
        !supportsNotifications -> SetupPermission.SETTINGS
        else -> activity.permissionState(POST_NOTIFICATIONS, notificationGranted, notificationRequested)
    }
    val pendingPermissions = buildList {
        if (location == SetupPermission.REQUEST || location == SetupPermission.RETRY) {
            add(ACCESS_FINE_LOCATION)
            add(ACCESS_COARSE_LOCATION)
        }
        if (notifications == SetupPermission.REQUEST || notifications == SetupPermission.RETRY) add(POST_NOTIFICATIONS)
    }
    val step = when {
        pendingPermissions.isNotEmpty() -> SetupStep.PERMISSIONS
        !locationGranted || !notificationGranted -> SetupStep.PERMISSION_SETTINGS
        !skipBackground && !batteryExempt -> SetupStep.SYSTEM_BATTERY
        !skipBackground && isXiaomi && !xiaomiBatteryOpened -> SetupStep.XIAOMI_BATTERY
        !skipBackground && isXiaomi && !xiaomiAutostartOpened -> SetupStep.XIAOMI_AUTOSTART
        else -> SetupStep.MAP
    }

    StartupSetupScreen(
        progress = SetupProgress(location, notifications, batteryExempt,
            xiaomiBackgroundRestricted.takeIf { isXiaomi }, step),
        selectedMap = selected,
        mapError = mapError,
        onSkipBackground = { skipBackground = true },
        onSelectMap = { selected = it; mapError = false },
        onReadPolicy = { provider ->
            val policy = provider.privacyPolicyUrl
            runCatching { activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(policy))) }.onFailure {
                Toast.makeText(activity, activity.getString(R.string.setup_open_policy_failed, policy), Toast.LENGTH_LONG).show()
            }
        },
        onContinue = {
            when (step) {
                SetupStep.PERMISSIONS -> requestPermissions.launch(pendingPermissions.toTypedArray())
                SetupStep.PERMISSION_SETTINGS -> if (!locationGranted) BackgroundGuidance.openAppDetails(activity)
                    else BackgroundGuidance.openNotifications(activity)
                SetupStep.SYSTEM_BATTERY -> BackgroundGuidance.openBatterySettings(activity, requestExemption = true)
                SetupStep.XIAOMI_BATTERY -> xiaomiBatteryOpened = BackgroundGuidance.openXiaomiBattery(activity)
                SetupStep.XIAOMI_AUTOSTART -> xiaomiAutostartOpened = BackgroundGuidance.openXiaomiAutostart(activity)
                SetupStep.MAP -> {
                    refreshStatus()
                    val provider = selected
                    if (locationGranted && notificationGranted && provider != null) mapError = !onComplete(provider)
                }
            }
        },
    )
}

private fun Activity.hasPermission(permission: String): Boolean =
    checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

private fun Activity.permissionState(permission: String, granted: Boolean, requested: Boolean): SetupPermission = when {
    granted -> SetupPermission.GRANTED
    !requested -> SetupPermission.REQUEST
    shouldShowRequestPermissionRationale(permission) -> SetupPermission.RETRY
    else -> SetupPermission.SETTINGS
}
