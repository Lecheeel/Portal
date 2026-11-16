package com.system.location.service.core.carrier

import kotlinx.serialization.json.Json

/** One persisted/wire format and one size limit, validated before preferences are changed. */
object CarrierIdentityCodec {
    const val MAX_LENGTH = 65_536
    fun encode(config: CarrierIdentityConfig): String =
        Json.encodeToString(CarrierIdentityConfig.serializer(), config).also(::validateSize)

    fun decode(encoded: String): CarrierIdentityConfig {
        validateSize(encoded)
        require(encoded.isNotBlank()) { "运营商配置为空" }
        return Json.decodeFromString(CarrierIdentityConfig.serializer(), encoded)
    }

    private fun validateSize(encoded: String) {
        require(encoded.length <= MAX_LENGTH) { "运营商配置超过大小上限，请减少应用规则" }
    }
}
