package com.system.location.service.core.carrier

import java.security.SecureRandom
import java.util.zip.GZIPInputStream
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

@Serializable
data class CarrierPhoneRegion(val iso: String, val callingCode: String, val example: String,
    val pattern: String, val kind: String)

data class CarrierPhoneCity(val name: String, val operator: String)

/** Offline allocation/format data; generated numbers are not assigned network credentials. */
object CarrierPhoneNumbers {
    private val random = SecureRandom()
    val regions: List<CarrierPhoneRegion> by lazy {
        val text = checkNotNull(javaClass.getResourceAsStream("/carrier/phone-regions.json"))
            .bufferedReader().use { it.readText() }
        Json.decodeFromString(ListSerializer(CarrierPhoneRegion.serializer()), text)
    }
    private val cityPrefixes: Map<CarrierPhoneCity, List<String>> by lazy {
        val stream = checkNotNull(javaClass.getResourceAsStream("/carrier/cn-mobile-cities.tsv.gz"))
        GZIPInputStream(stream).bufferedReader().useLines { lines ->
            lines.map { line -> line.split('|').let { CarrierPhoneCity(it[0], it[1]) to it[2] } }
                .groupBy({ it.first }, { it.second })
        }
    }
    private val patterns by lazy { regions.associate { it.iso to Regex(it.pattern) } }

    fun chinaOperator(mcc: Int, mnc: Int, mncDigits: Int): String? = when (
        mcc.toString().padStart(3, '0') + mnc.toString().padStart(mncDigits, '0')) {
        "46000", "46002", "46004", "46007", "46008" -> "CMCC"
        "46001", "46006", "46009" -> "CUCC"
        "46003", "46005", "46011" -> "CTCC"
        "46015" -> "CBN"
        else -> null
    }

    fun cities(operator: String?): List<CarrierPhoneCity> = cityPrefixes.keys
        .filter { operator == null || it.operator == operator }.sortedBy { it.name }

    fun normalize(input: String): String {
        if (input.isBlank()) return ""
        val value = input.trim().filterNot { it == ' ' || it == '-' || it == '(' || it == ')' }
        require(value.matches(Regex("\\+?[1-9][0-9]{5,14}"))) { "号码需为 6–15 位数字，可带 + 国家码" }
        return value
    }

    fun matchesRegion(number: String, iso: String): Boolean {
        val region = regions.firstOrNull { it.iso == iso } ?: return false
        return number.startsWith("+" + region.callingCode) &&
            patterns.getValue(iso).matches(number.removePrefix("+" + region.callingCode))
    }

    fun generate(iso: String, city: String = "", operator: String? = null): String {
        if (iso == "CN") {
            require(operator != null) { "请先选择可识别的中国运营商预设，再选择城市" }
            val prefixes = cityPrefixes[CarrierPhoneCity(city, operator)]
                ?: error("所选城市没有匹配此运营商的可用号段")
            val prefix = prefixes[random.nextInt(prefixes.size)]
            return "+86" + prefix + random.nextInt(10_000).toString().padStart(4, '0')
        }
        val region = regions.firstOrNull { it.iso == iso }
            ?: error("该国家/地区暂无可用号码规则，请手动填写")
        val pattern = patterns.getValue(iso)
        // Keep the example's regional allocation prefix (notably shared +1 country codes),
        // randomize at least the last four subscriber digits, and validate the complete pattern.
        val suffixSize = minOf(6, maxOf(1, region.example.length - 3))
        repeat(2048) {
            val candidate = region.example.dropLast(suffixSize) + buildString {
                repeat(suffixSize) { append(random.nextInt(10)) }
            }
            if (candidate != region.example && pattern.matches(candidate)) return "+${region.callingCode}$candidate"
        }
        error("该国家/地区的号码规则暂不能生成，请手动填写")
    }

    fun belongsToCity(number: String, city: CarrierPhoneCity): Boolean =
        number.startsWith("+86") && number.length == 14 &&
            cityPrefixes[city]?.contains(number.substring(3, 10)) == true
}
