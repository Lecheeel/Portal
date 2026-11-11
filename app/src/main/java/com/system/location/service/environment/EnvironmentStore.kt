package com.system.location.service.environment

import android.content.Context
import com.system.location.service.core.environment.EnvironmentProfile
import com.system.location.service.core.environment.EnvironmentProfileCodec
import java.io.File

/**
 * User-triggered environment capture storage (P2-02). Never used on a location callback; every
 * method is synchronous file I/O and must be called off the main thread by the UI.
 */
class EnvironmentStore(private val directory: File) {
    constructor(context: Context) : this(File(context.filesDir, DIRECTORY))

    fun save(name: String, profile: EnvironmentProfile): File {
        ensureDirectory()
        val safe = sanitize(name)
        val file = File(directory, "$safe$EXTENSION")
        val tmp = File(directory, "$safe$EXTENSION.tmp")
        tmp.writeText(EnvironmentProfileCodec.encode(profile), Charsets.UTF_8)
        if (file.exists() && !file.delete()) error("Cannot replace $name")
        if (!tmp.renameTo(file)) error("Cannot commit $name")
        return file
    }

    fun load(name: String): EnvironmentProfile =
        EnvironmentProfileCodec.decode(File(directory, "${sanitize(name)}$EXTENSION").readText(Charsets.UTF_8))

    fun list(): List<String> {
        if (!directory.exists()) return emptyList()
        require(directory.isDirectory) { "Environment path is not a directory" }
        val files = directory.listFiles { file -> file.isFile && file.name.endsWith(EXTENSION) }
            ?: error("Cannot read environment directory")
        return files.map { it.name.removeSuffix(EXTENSION) }.sorted()
    }

    fun delete(name: String): Boolean = File(directory, "${sanitize(name)}$EXTENSION").delete()

    fun rename(oldName: String, newName: String): File {
        ensureDirectory()
        val source = File(directory, "${sanitize(oldName)}$EXTENSION")
        val target = File(directory, "${sanitize(newName)}$EXTENSION")
        require(source.isFile) { "Environment not found" }
        require(!target.exists()) { "Environment already exists" }
        if (!source.renameTo(target)) error("Cannot rename environment")
        return target
    }

    private fun ensureDirectory() {
        if (!directory.exists() && !directory.mkdirs()) error("Cannot create environment directory")
    }

    private fun sanitize(name: String): String {
        val safe = name.trim().replace(Regex("[^A-Za-z0-9._-]"), "_").take(64)
        require(safe.isNotBlank()) { "Invalid environment name" }
        return safe
    }

    private companion object {
        const val DIRECTORY = "environment"
        const val EXTENSION = ".json"
    }
}
