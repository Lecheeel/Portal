package com.system.location.service.detector

import android.content.Context
import android.os.Build
import android.util.AtomicFile
import com.system.location.service.detector.model.GnssReference
import com.system.location.service.detector.model.GnssReferenceCodec
import java.io.File

/** App-private, excluded from backup. Save/clear/load share one lock with the headless runner. */
object GnssReferenceStore {
    data class Loaded(val reference: GnssReference?, val note: String)

    private fun file(context: Context) = AtomicFile(File(context.noBackupFilesDir, "gnss-reference-v1.bin"))

    @Synchronized fun load(context: Context): Loaded {
        val file = file(context)
        return runCatching {
            file.openRead().use { input ->
                require(input.channel.size() <= GnssReferenceCodec.MAX_BYTES)
                val reference = GnssReferenceCodec.read(input)
                require(reference.deviceFingerprint == Build.FINGERPRINT) { "设备或系统版本已改变，请重新选择参考窗口" }
                Loaded(reference, "本机用户选择的参考窗口，来源真实性需另行确认")
            }
        }.getOrElse {
            if (it is java.io.FileNotFoundException && !file.baseFile.exists()) {
                Loaded(null, "未保存参考窗口，画像比较跳过")
            } else Loaded(null, "参考窗口不可用：${it.message ?: it.javaClass.simpleName}")
        }
    }

    @Synchronized fun save(context: Context, reference: GnssReference) {
        require(reference.deviceFingerprint == Build.FINGERPRINT)
        // Validate before opening the atomic transaction, leaving the previous reference intact.
        require(reference.profile().adequate) { "参考窗口采样不足或间隔过大" }
        val file = file(context)
        val output = file.startWrite()
        try {
            GnssReferenceCodec.write(reference, output)
            file.finishWrite(output)
        } catch (error: Throwable) {
            file.failWrite(output)
            throw error
        }
    }

    @Synchronized fun clear(context: Context) { file(context).delete() }
}
