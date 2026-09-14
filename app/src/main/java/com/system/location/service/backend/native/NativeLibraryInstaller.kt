package com.system.location.service.backend.native

import android.content.Context
import android.os.Build
import com.system.location.service.core.backend.BackendResult
import java.io.File
import java.util.concurrent.TimeUnit

/** Explicit experimental path; never changes SELinux policy or grants world write access. */
object NativeLibraryInstaller {
    const val DESTINATION = "/data/local/ext-lib/liblocationext.so"
    fun install(context: Context): BackendResult {
        if (Build.SUPPORTED_ABIS.firstOrNull() != "arm64-v8a") return BackendResult.Failure("NATIVE_ABI",
            "当前 Native 路径只评估 arm64 固定符号布局", "改用标准 Mock 或 Xposed 后端")
        val source = File(context.applicationInfo.nativeLibraryDir, "liblocationext.so")
        if (!source.isFile) return BackendResult.Failure("NATIVE_LIBRARY", "APK 中未找到 Native 库", "检查安装包 ABI")
        fun quote(path: String) = "'" + path.replace("'", "'\\''") + "'"
        val directory = "/data/local/ext-lib"
        val temporary = "$DESTINATION.tmp"
        // Refuse symlinks before a privileged copy; replace through a temporary regular file.
        val script = "test ! -L ${quote(directory)} && test ! -L ${quote(temporary)} && " +
            "mkdir -p ${quote(directory)} && chmod 0755 ${quote(directory)} && " +
            "cp ${quote(source.absolutePath)} ${quote(temporary)} && chmod 0644 ${quote(temporary)} && " +
            "chown 0:0 ${quote(temporary)} && mv -f ${quote(temporary)} ${quote(DESTINATION)}"
        return try {
            val process = ProcessBuilder("su", "-c", script).redirectErrorStream(true).start()
            try {
                if (!process.waitFor(20, TimeUnit.SECONDS)) {
                    process.destroyForcibly()
                    BackendResult.Failure("NATIVE_ROOT", "Root 授权或复制超时", "授权后重试，或改用其他后端")
                } else if (process.exitValue() == 0) BackendResult.Success
                else BackendResult.Failure("NATIVE_COPY", process.inputStream.bufferedReader().readText().take(1000),
                    "检查 Root 授权和系统访问策略；不会自动关闭 SELinux")
            } finally { process.inputStream.close(); process.outputStream.close(); process.errorStream.close() }
        } catch (error: Exception) { BackendResult.Failure("NATIVE_ROOT", error.message ?: "无法运行 su", "选择无 Root 模式或配置 Root") }
    }
}
