package com.system.location.service.ui.mock

import com.system.location.service.core.geo.Wgs84
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable data class HistoricalLocation(val name: String, val address: String, val lat: Double, val lon: Double) {
    init { require(name.isNotBlank()); Wgs84(lat, lon) }
    override fun toString(): String = Json { encodeDefaults = true }.encodeToString(LocationEnvelope(location = this))
    companion object {
        /** Legacy CSV is read only; new records are versioned JSON with Double fields. */
        fun fromString(text: String): HistoricalLocation {
            if (text.trimStart().startsWith("{")) {
                val envelope = Json.decodeFromString<LocationEnvelope>(text)
                require(envelope.schemaVersion == 1) { "Unsupported saved location schema" }
                return envelope.location
            }
            val fields = mutableListOf<String>()
            val field = StringBuilder()
            var quoted = false
            var index = 0
            while (index < text.length) {
                val char = text[index]
                when {
                    char == '"' && quoted && text.getOrNull(index + 1) == '"' -> { field.append('"'); index++ }
                    char == '"' -> quoted = !quoted
                    char == ',' && !quoted -> { fields += field.toString().trim(); field.setLength(0) }
                    else -> field.append(char)
                }
                index++
            }
            require(!quoted) { "Unclosed CSV quote" }
            fields += field.toString().trim()
            require(fields.size == 4) { "Expected four CSV fields" }
            return HistoricalLocation(fields[0], fields[1], fields[2].toDouble(), fields[3].toDouble())
        }
    }
}
@Serializable private data class LocationEnvelope(val schemaVersion: Int = 1, val location: HistoricalLocation)
