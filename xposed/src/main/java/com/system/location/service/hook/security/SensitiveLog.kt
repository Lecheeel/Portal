package com.system.location.service.hook.security

/** P6-04: redact location/config payloads before writing a debug log line. */
object SensitiveLog {
    private val sensitiveKeys = setOf(
        "lat", "lon", "latitude", "longitude", "altitude",
        "sample_lat", "sample_lon", "sample_altitude",
        "environment_json", "routing_json", "gnss_capture", "gnss_navigation", "runtime_transfer", "proxy_session", "key", "token",
    )

    fun isSensitive(key: String): Boolean {
        val normalized = key.lowercase()
        return normalized in sensitiveKeys ||
            normalized.endsWith("_lat") || normalized.endsWith("_lon") ||
            normalized.endsWith("_latitude") || normalized.endsWith("_longitude")
    }

    fun describe(commandId: String?, values: Map<String, String?>): String {
        val fields = values.map { (key, value) ->
            "$key=${if (isSensitive(key)) "<redacted>" else value}"
        }.joinToString(", ")
        return "commandId=$commandId, rely={$fields}"
    }
}
