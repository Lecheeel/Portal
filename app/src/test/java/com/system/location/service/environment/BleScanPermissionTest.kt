package com.system.location.service.environment

import android.os.Build
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BleScanPermissionTest {
    @Test
    fun android12AndNewerRequireBluetoothScanPermission() {
        assertTrue(BleScanPermission.isGranted(Build.VERSION_CODES.S, true, false))
        assertFalse(BleScanPermission.isGranted(Build.VERSION_CODES.S, false, true))
    }

    @Test
    fun olderVersionsUseFineLocationForBleScanResults() {
        assertTrue(BleScanPermission.isGranted(Build.VERSION_CODES.R, false, true))
        assertFalse(BleScanPermission.isGranted(Build.VERSION_CODES.R, true, false))
    }
}
