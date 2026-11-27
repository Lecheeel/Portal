package com.system.location.service.ui.startup

import android.content.Context
import android.location.LocationManager
import android.os.Build
import android.provider.Settings
import com.system.location.service.backend.mock.AndroidMockProviderPort
import com.system.location.service.backend.native.NativeLibraryInstaller
import com.system.location.service.core.backend.BackendType
import com.system.location.service.hook.security.SampleWire
import com.system.location.service.service.MockServiceHelper
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible

internal enum class ModeRequirement {
    CHECKING, READY, DEVELOPER_OPTIONS, MOCK, MODULE, ANDROID, ABI, NATIVE_LIBRARY,
    ROOT, ROOT_DENIED, CHECK_FAILED, SAVE_FAILED,
}

internal suspend fun checkStartupMode(context: Context, mode: BackendType, verifyRoot: Boolean = false): ModeRequirement =
    runInterruptible(Dispatchers.IO) {
        if (mode == BackendType.MOCK_PROVIDER) {
            return@runInterruptible when {
                AndroidMockProviderPort(context).permissionGranted() -> ModeRequirement.READY
                Settings.Global.getInt(context.contentResolver, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) == 0 ->
                    ModeRequirement.DEVELOPER_OPTIONS
                else -> ModeRequirement.MOCK
            }
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return@runInterruptible ModeRequirement.ANDROID
        if (mode == BackendType.NATIVE && Build.SUPPORTED_ABIS.firstOrNull() != "arm64-v8a") {
            return@runInterruptible ModeRequirement.ABI
        }
        val manager = context.getSystemService(LocationManager::class.java)
        fun compatibleSystemModule() = MockServiceHelper.runtimeStatus(manager)?.let {
            SampleWire.isCompatible(it.getInt("sample_version")) && it.getString("hook_scope") == "SYSTEM_SERVER" &&
                it.getInt("hook_installed") > 0
        } == true
        val moduleReady = runCatching {
            compatibleSystemModule() || run {
                MockServiceHelper.tryInitService(manager)
                compatibleSystemModule()
            }
        }.getOrDefault(false)
        if (!moduleReady) return@runInterruptible ModeRequirement.MODULE
        if (mode == BackendType.XPOSED) return@runInterruptible ModeRequirement.READY
        if (!NativeLibraryInstaller.hasPackagedLibrary(context)) return@runInterruptible ModeRequirement.NATIVE_LIBRARY
        if (!verifyRoot) return@runInterruptible ModeRequirement.ROOT
        if (hasGrantedRoot()) ModeRequirement.READY else ModeRequirement.ROOT_DENIED
    }

/** Read the effective UID of an explicitly authorized command without changing the device. */
private fun hasGrantedRoot(): Boolean {
    val process = try {
        ProcessBuilder("su", "-c", "id -u").redirectErrorStream(true).start()
    } catch (_: IOException) {
        return false
    } catch (_: SecurityException) {
        return false
    }
    return try {
        process.waitFor(15, TimeUnit.SECONDS) && process.exitValue() == 0 &&
            process.inputStream.bufferedReader().readLine()?.trim() == "0"
    } finally {
        if (process.isAlive) process.destroyForcibly()
        process.inputStream.close()
        process.outputStream.close()
        process.errorStream.close()
    }
}
