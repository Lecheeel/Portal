package com.system.location.service.data.persistence

/** Small storage boundary: migrated latitude and longitude are committed together. */
class CoordinatePreferences(
    private val read: () -> Map<String, *>,
    private val write: (Map<String, Any>) -> Unit,
) {
    fun get(key: String): Double {
        migrate()
        return (read()[key] as? String)?.toDoubleOrNull()?.takeIf { it.isFinite() } ?: 0.0
    }

    fun set(key: String, value: Double) {
        require(key in keys)
        require(value.isFinite() && value in if (key == LATITUDE) -90.0..90.0 else -180.0..180.0)
        migrate()
        write(mapOf(key to value.toString()))
    }

    private fun migrate() {
        val values = read()
        val version = values[VERSION] as? Int ?: 0
        require(version in 0..1) { "Unsupported coordinate schema: $version" }
        if (version == 1) return
        val migrated = mutableMapOf<String, Any>(VERSION to 1)
        keys.forEach { key ->
            val old = values[key]
            val number = when (old) {
                is Number -> old.toDouble()
                is String -> old.toDoubleOrNull()
                else -> null
            }
            migrated[key] = (number?.takeIf { it.isFinite() } ?: 0.0).toString()
        }
        write(migrated)
    }

    companion object {
        const val LATITUDE = "last_known_lat"
        const val LONGITUDE = "last_known_lng"
        const val VERSION = "coordinate_schema_version"
        private val keys = setOf(LATITUDE, LONGITUDE)
    }
}
