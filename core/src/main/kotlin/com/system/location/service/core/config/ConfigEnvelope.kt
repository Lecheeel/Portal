package com.system.location.service.core.config

import com.system.location.service.core.environment.EnvironmentConfig
import com.system.location.service.core.playback.NoiseProfile
import com.system.location.service.core.providers.ProviderProfile
import com.system.location.service.core.routing.RoutingConfig
import com.system.location.service.core.scenario.Scenario
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject

@Serializable
data class ConfigEnvelope(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val revision: Long = 0L,
    val scenario: Scenario,
    val routing: RoutingConfig = RoutingConfig(),
    val environment: EnvironmentConfig = EnvironmentConfig(),
    val noise: NoiseProfile = NoiseProfile(),
    val providers: List<ProviderProfile> = ProviderProfile.defaults(),
    val updatedAtMillis: Long = 0L,
) {
    init {
        require(schemaVersion == CURRENT_SCHEMA_VERSION) { "Unsupported schemaVersion: $schemaVersion" }
        require(revision >= 0L) { "revision must be non-negative" }
        require(updatedAtMillis >= 0L) { "updatedAtMillis must be non-negative" }
        require(providers.isNotEmpty()) { "providers must not be empty" }
        require(providers.map { it.kind }.distinct().size == providers.size) { "duplicate provider kind" }
        require(providers.size <= MAX_PROVIDERS) { "Too many providers: ${providers.size}" }
    }

    companion object {
        const val CURRENT_SCHEMA_VERSION = 2
        const val MAX_PROVIDERS = 16
    }
}

class ConfigException(message: String, cause: Throwable? = null) : IllegalArgumentException(message, cause)

/**
 * Versioned config codec (P5-03).
 *
 * v1 (legacy) only carried scenario/revision/timestamp; v2 adds routing, environment and noise
 * blocks. Readers accept v1 and fill defaults; writers always emit the current version. Unknown
 * fields are ignored so an older app can still open a config written by a newer build as long as
 * the schema version is known.
 */
object ConfigCodec {
    const val CURRENT_SCHEMA_VERSION = ConfigEnvelope.CURRENT_SCHEMA_VERSION
    const val VERSION_FIELD = "schemaVersion"

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    fun encode(config: ConfigEnvelope): String = json.encodeToString(ConfigEnvelope.serializer(), config)

    fun decode(raw: String): ConfigEnvelope {
        val root: JsonObject = runCatching { json.parseToJsonElement(raw).jsonObject }
            .getOrElse { throw ConfigException("Config is not a JSON object", it) }
        val version = (root[VERSION_FIELD] as? JsonPrimitive)?.intOrNull
            ?: throw ConfigException("Missing $VERSION_FIELD")
        return when (version) {
            1 -> migrateV1(json.decodeFromJsonElement(LegacyConfigV1.serializer(), root))
            2 -> runCatching { json.decodeFromJsonElement(ConfigEnvelope.serializer(), root) }
                .getOrElse { throw ConfigException("Invalid v2 config", it) }
            else -> throw ConfigException("Unsupported schemaVersion: $version")
        }
    }

    private fun migrateV1(legacy: LegacyConfigV1): ConfigEnvelope = ConfigEnvelope(
        schemaVersion = CURRENT_SCHEMA_VERSION,
        revision = legacy.revision,
        scenario = legacy.scenario,
        updatedAtMillis = legacy.updatedAtMillis,
    )

    @Serializable
    private data class LegacyConfigV1(
        val schemaVersion: Int = 1,
        val revision: Long = 0L,
        val scenario: Scenario,
        val updatedAtMillis: Long = 0L,
    )
}
