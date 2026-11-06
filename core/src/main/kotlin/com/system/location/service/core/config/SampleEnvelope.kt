package com.system.location.service.core.config

import com.system.location.service.core.location.LocationSample
import com.system.location.service.core.providers.ProviderKind
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject

/**
 * Versioned sample envelope (P5-03).
 *
 * v2 adds provider/field/environment metadata to the raw `LocationSample`. A raw v1
 * `LocationSample` JSON object is still accepted and wrapped with defaults, so an old producer can
 * keep publishing while a new consumer starts using the extra metadata.
 */
@Serializable
data class SampleEnvelope(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val sample: LocationSample,
    val provider: ProviderKind? = null,
    val fieldsMask: Int = 0,
    val environmentVersion: Long = 0L,
    val configRevision: Long = 0L,
) {
    init {
        require(schemaVersion == CURRENT_SCHEMA_VERSION) { "Unsupported sample schemaVersion: $schemaVersion" }
        require(fieldsMask >= 0) { "fieldsMask must be non-negative" }
        require(environmentVersion >= 0L) { "environmentVersion must be non-negative" }
        require(configRevision >= 0L) { "configRevision must be non-negative" }
    }

    companion object {
        const val CURRENT_SCHEMA_VERSION = 2
    }
}

object SampleCodec {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    fun encode(envelope: SampleEnvelope): String = json.encodeToString(SampleEnvelope.serializer(), envelope)

    fun decode(raw: String): SampleEnvelope {
        val root: JsonObject = runCatching { json.parseToJsonElement(raw).jsonObject }
            .getOrElse { throw ConfigException("Sample is not a JSON object", it) }
        val version = (root[ConfigCodec.VERSION_FIELD] as? JsonPrimitive)?.intOrNull
        if (version == null) {
            val sample = runCatching { json.decodeFromJsonElement(LocationSample.serializer(), root) }
                .getOrElse { throw ConfigException("Invalid v1 sample", it) }
            return SampleEnvelope(sample = sample)
        }
        return when (version) {
            SampleEnvelope.CURRENT_SCHEMA_VERSION -> runCatching {
                json.decodeFromJsonElement(SampleEnvelope.serializer(), root)
            }.getOrElse { throw ConfigException("Invalid v2 sample", it) }
            1 -> {
                val sample = runCatching { json.decodeFromJsonElement(LocationSample.serializer(), root) }
                    .getOrElse { throw ConfigException("Invalid v1 sample", it) }
                SampleEnvelope(sample = sample)
            }
            else -> throw ConfigException("Unsupported sample schemaVersion: $version")
        }
    }
}
