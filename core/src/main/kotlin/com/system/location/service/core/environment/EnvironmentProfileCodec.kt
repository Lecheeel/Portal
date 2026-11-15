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

    const val MAX_PROFILE_BYTES = 2 * 1024 * 1024

    fun encode(profile: EnvironmentProfile): String {
        val encoded = json.encodeToString(EnvironmentProfile.serializer(), profile)
        require(encoded.toByteArray(Charsets.UTF_8).size <= MAX_PROFILE_BYTES) {
            "Environment profile exceeds $MAX_PROFILE_BYTES bytes"
        }
        return encoded
    }

    fun decode(raw: String): EnvironmentProfile {
        if (raw.toByteArray(Charsets.UTF_8).size > MAX_PROFILE_BYTES) {
            throw EnvironmentCodecException("Environment profile exceeds $MAX_PROFILE_BYTES bytes")
        }
        val profile = runCatching { json.decodeFromString(EnvironmentProfile.serializer(), raw) }
            .getOrElse { throw EnvironmentCodecException("Invalid environment profile", it) }
        if (profile.schemaVersion != EnvironmentProfile.SCHEMA_VERSION) {
            throw EnvironmentCodecException("Unsupported environment schemaVersion: ${profile.schemaVersion}")
        }
        return profile
    }
}
