package com.system.location.service.core.environment

import kotlinx.serialization.json.Json

class EnvironmentCodecException(message: String, cause: Throwable? = null) : IllegalArgumentException(message, cause)

/**
 * JSON import/export for captured or procedural environment profiles (P2-02).
 *
 * The profile carries its own schemaVersion; unknown keys are ignored for forward-compatible
 * readers, but an unknown version is rejected instead of silently producing a wrong environment.
 */
object EnvironmentProfileCodec {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    fun encode(profile: EnvironmentProfile): String =
        json.encodeToString(EnvironmentProfile.serializer(), profile)

    fun decode(raw: String): EnvironmentProfile {
        val profile = runCatching { json.decodeFromString(EnvironmentProfile.serializer(), raw) }
            .getOrElse { throw EnvironmentCodecException("Invalid environment profile", it) }
        if (profile.schemaVersion != EnvironmentProfile.SCHEMA_VERSION) {
            throw EnvironmentCodecException("Unsupported environment schemaVersion: ${profile.schemaVersion}")
        }
        return profile
    }
}
