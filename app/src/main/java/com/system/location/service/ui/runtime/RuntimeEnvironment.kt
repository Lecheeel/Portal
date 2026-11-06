package com.system.location.service.ui.runtime

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import androidx.core.content.ContextCompat
import com.system.location.service.R
import com.system.location.service.backend.mock.AndroidMockProviderPort
import com.system.location.service.core.backend.BackendType
import com.system.location.service.service.MockServiceHelper
import java.io.File

/** Read-only checks: never execute su or request Root authorization from a status screen. */
internal data class RuntimeEnvironment(
    val rootPresent: Boolean,
    val moduleConnected: Boolean,
    val mockAllowed: Boolean,
    val preciseLocation: Boolean,
    val locationEnabled: Boolean,
) {
    fun issues(mode: BackendType): List<Int> = buildList {
        if (!preciseLocation) add(R.string.runtime_issue_permission)
        if (!locationEnabled) add(R.string.runtime_issue_location)
        if (mode == BackendType.MOCK_PROVIDER && !mockAllowed) add(R.string.runtime_issue_mock)
        if (mode != BackendType.MOCK_PROVIDER && !moduleConnected) add(R.string.runtime_issue_xposed)
        if (mode == BackendType.NATIVE && !rootPresent) add(R.string.runtime_issue_root)
    }
    companion object {
        fun inspect(context: Context): RuntimeEnvironment {
            val paths = System.getenv("PATH").orEmpty().split(File.pathSeparator) +
                listOf("/system/bin", "/system/xbin", "/sbin", "/su/bin", "/debug_ramdisk")
            val root = paths.filter { it.isNotBlank() }.any { File(it, "su").canExecute() }
            val manager = context.getSystemService(LocationManager::class.java)
            val connected = runCatching {
                if (!MockServiceHelper.isServiceInit()) MockServiceHelper.tryInitService(manager)
                MockServiceHelper.runtimeStatus(manager)?.getInt("sample_version") == 1
            }.getOrDefault(false)
            return RuntimeEnvironment(root, connected,
                AndroidMockProviderPort(context).permissionGranted(),
                ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED,
                manager.isLocationEnabled)
        }
    }
}
