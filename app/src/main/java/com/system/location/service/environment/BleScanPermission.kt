package com.system.location.service.environment

import android.os.Build

internal object BleScanPermission {
    fun isGranted(sdkInt: Int, scanPermissionGranted: Boolean, fineLocationGranted: Boolean): Boolean =
        if (sdkInt >= Build.VERSION_CODES.S) scanPermissionGranted else fineLocationGranted
}
