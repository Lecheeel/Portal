package com.system.location.service.hook.hooks.telephony

import android.content.Context
import android.provider.Settings
import android.util.AtomicFile
import com.system.location.service.core.carrier.CarrierPropertyJournalData
import com.system.location.service.core.carrier.CarrierPropertyJournalEntry
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.serialization.json.Json

/** Private, boot-scoped write-ahead journal. Stores operator property values, never SIM credentials. */
internal class CarrierPropertyJournalStore(context: Context, private val allowed: Set<String>) {
    private val boot = Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)
        .also { require(it >= 0) { "Boot count unavailable" } }
    private val file = AtomicFile(File(context.noBackupFilesDir, "portal-carrier-property-journal-v1.json"))
    private var data: CarrierPropertyJournalData = load()

    fun entries(): Map<String, CarrierPropertyJournalEntry> = data.fields.toMap()

    fun put(field: String, entry: CarrierPropertyJournalEntry?) {
        require(field in allowed)
        val fields = data.fields.toMutableMap()
        if (entry == null) fields.remove(field) else fields[field] = entry
        commit(data.copy(fields = fields))
    }

    fun clear() = commit(CarrierPropertyJournalData(boot))

    private fun validate(value: CarrierPropertyJournalData) {
        require(value.version == 1 && value.bootCount >= 0)
        require(value.fields.keys.all { it in allowed })
        value.fields.values.forEach { entry ->
            entry.committed.validate()
            entry.pending?.validate()
        }
    }

    private fun load(): CarrierPropertyJournalData {
        val inputStream = try { file.openRead() } catch (failure: java.io.FileNotFoundException) {
            if (file.baseFile.exists() || File(file.baseFile.path + ".bak").exists()) throw failure
            return CarrierPropertyJournalData(boot)
        }
        val text = inputStream.use { input ->
            val bytes = ByteArrayOutputStream()
            val buffer = ByteArray(4096)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                require(bytes.size() + count <= 65_536) { "Carrier property journal too large" }
                bytes.write(buffer, 0, count)
            }
            bytes.toString("UTF-8")
        }
        val saved = Json.decodeFromString(CarrierPropertyJournalData.serializer(), text)
        validate(saved)
        // Non-persistent telephony properties are rebuilt by the OS on a new boot.
        return if (saved.bootCount == boot) saved else CarrierPropertyJournalData(boot)
    }

    private fun commit(next: CarrierPropertyJournalData) {
        validate(next)
        val bytes = Json.encodeToString(CarrierPropertyJournalData.serializer(), next).toByteArray(Charsets.UTF_8)
        require(bytes.size <= 65_536) { "Carrier property journal too large" }
        val output = file.startWrite()
        try {
            output.write(bytes)
            file.finishWrite(output)
            check(load() == next) { "Carrier property journal commit verification failed" }
            data = next
        } catch (failure: Exception) {
            file.failWrite(output)
            throw failure
        }
    }
}
