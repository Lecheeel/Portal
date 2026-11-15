package com.system.location.service.environment

import android.content.Context
import com.system.location.service.core.environment.EnvironmentCodecException
import com.system.location.service.core.environment.EnvironmentProfile
import com.system.location.service.core.environment.EnvironmentProfileCodec
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AccessDeniedException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING

/**
 * User-triggered environment capture storage (P2-02). Never used on a location callback; every
 * method is synchronous file I/O and must be called off the main thread by the UI.
 */
class EnvironmentStore(private val directory: File) {
    constructor(context: Context) : this(File(context.filesDir, DIRECTORY))

    /** Save an imported profile under a fresh name so importing never replaces a saved profile. */
    fun saveNew(name: String, profile: EnvironmentProfile): String = synchronized(SAVE_LOCK) {
        val base = sanitize(name)
        var candidate = base
        var suffix = 2L
        while (File(directory, "$candidate$EXTENSION").exists()) {
            val suffixText = "-$suffix"
            candidate = base.take((MAX_NAME_LENGTH - suffixText.length).coerceAtLeast(0)) + suffixText
            suffix++
        }
        save(candidate, profile)
        candidate
    }

    fun save(name: String, profile: EnvironmentProfile): File {
        ensureDirectory()
        val safe = sanitize(name)
        val file = File(directory, "$safe$EXTENSION")
        val tmp = Files.createTempFile(directory.toPath(), "${safe.take(40)}-env-", ".tmp").toFile()
        val payload = EnvironmentProfileCodec.encode(profile).toByteArray(Charsets.UTF_8)
        try {
            FileOutputStream(tmp).use { stream ->
                stream.write(payload)
                stream.fd.sync()
            }
            synchronized(SAVE_LOCK) {
                try {
                    Files.move(tmp.toPath(), file.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
                } catch (_: AtomicMoveNotSupportedException) {
                    Files.move(tmp.toPath(), file.toPath(), REPLACE_EXISTING)
                } catch (_: AccessDeniedException) {
                    Files.move(tmp.toPath(), file.toPath(), REPLACE_EXISTING)
                }
            }
        } finally {
            if (tmp.exists()) tmp.delete()
        }
        return file
    }

    fun load(name: String): EnvironmentProfile = EnvironmentProfileCodec.decode(
        readUtf8Bounded(File(directory, "${sanitize(name)}$EXTENSION")),
    )

    fun list(): List<String> {
        if (!directory.exists()) return emptyList()
        require(directory.isDirectory) { "Environment path is not a directory" }
        val files = directory.listFiles { file -> file.isFile && file.name.endsWith(EXTENSION) }
            ?: error("Cannot read environment directory")
        return files.map { it.name.removeSuffix(EXTENSION) }.sorted()
    }

    fun delete(name: String): Boolean = synchronized(SAVE_LOCK) {
        File(directory, "${sanitize(name)}$EXTENSION").delete()
    }

    fun rename(oldName: String, newName: String): File = synchronized(SAVE_LOCK) {
        ensureDirectory()
        val source = File(directory, "${sanitize(oldName)}$EXTENSION")
        val target = File(directory, "${sanitize(newName)}$EXTENSION")
        require(source.isFile) { "Environment not found" }
        require(!target.exists()) { "Environment already exists" }
        if (!source.renameTo(target)) error("Cannot rename environment")
        target
    }

    private fun ensureDirectory() {
        if (!directory.exists() && !directory.mkdirs()) error("Cannot create environment directory")
    }

    private fun readUtf8Bounded(file: File): String {
        val output = ByteArrayOutputStream()
        file.inputStream().use { input ->
            val buffer = ByteArray(8 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (output.size() > EnvironmentProfileCodec.MAX_PROFILE_BYTES - count) {
                    throw EnvironmentCodecException(
                        "Environment profile exceeds ${EnvironmentProfileCodec.MAX_PROFILE_BYTES} bytes",
                    )
                }
                output.write(buffer, 0, count)
            }
        }
        return try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(output.toByteArray()))
                .toString()
        } catch (error: java.nio.charset.CharacterCodingException) {
            throw EnvironmentCodecException("Invalid UTF-8 environment profile", error)
        }
    }

    private fun sanitize(name: String): String {
        val safe = name.trim().replace(Regex("[^A-Za-z0-9._-]"), "_").take(MAX_NAME_LENGTH)
        require(safe.isNotBlank()) { "Invalid environment name" }
        return safe
    }

    private companion object {
        val SAVE_LOCK = Any()
        const val MAX_NAME_LENGTH = 64
        const val DIRECTORY = "environment"
        const val EXTENSION = ".json"
    }
}
