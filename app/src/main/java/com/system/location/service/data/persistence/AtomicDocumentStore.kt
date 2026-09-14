package com.system.location.service.data.persistence

import android.util.AtomicFile
import com.system.location.service.core.repository.DocumentStore
import com.system.location.service.core.repository.VersionedRepository
import java.io.File
import java.io.FileNotFoundException

class AtomicDocumentStore(file: File) : DocumentStore {
    private val file = AtomicFile(file)
    @Synchronized override fun read(): String? = try {
        file.openRead().use { input ->
            val bytes = input.readNBytes(VersionedRepository.MAX_DOCUMENT_CHARS * 4 + 1)
            require(bytes.size <= VersionedRepository.MAX_DOCUMENT_CHARS * 4) { "File exceeds library size limit" }
            bytes.toString(Charsets.UTF_8)
        }
    } catch (_: FileNotFoundException) { null }
    @Synchronized override fun writeAtomically(text: String) {
        val stream = file.startWrite()
        try { stream.write(text.toByteArray(Charsets.UTF_8)); file.finishWrite(stream) }
        catch (error: Throwable) { file.failWrite(stream); throw error }
    }
}
