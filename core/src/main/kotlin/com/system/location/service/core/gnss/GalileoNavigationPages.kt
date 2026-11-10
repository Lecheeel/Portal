package com.system.location.service.core.gnss

import java.util.Base64

/** CRC-checked payload: 128-bit I/NAV word or 214-bit F/NAV information block. */
class GalileoNavigationPage internal constructor(
    val svid: Int,
    val capturedAtMillis: Long,
    val format: GalileoClockReference,
    val wordType: Int,
    private val data: ByteArray,
    private val bitLength: Int,
) {
    fun unsigned(start: Int, length: Int): Long {
        require(start >= 0 && length in 1..32 && start + length <= bitLength)
        var result = 0L
        repeat(length) { result = (result shl 1) or
            ((data[(start + it) / 8].toInt() ushr (7 - (start + it) % 8)) and 1).toLong() }
        return result
    }
    fun signed(start: Int, length: Int): Long {
        val result = unsigned(start, length)
        return if (result and (1L shl (length - 1)) != 0L) result - (1L shl length) else result
    }
}

data class GalileoPageResult(val page: GalileoNavigationPage?, val reason: String? = null)

/** Android sync/tail-free packing, Galileo OS SIS ICD 2.2 Tables 28/38, 5.1.9.4 CRC-24Q. */
object GalileoNavigationPages {
    fun decode(observation: GnssNavigationObservation): GalileoPageResult {
        fun invalid(reason: String) = GalileoPageResult(null, reason)
        if (observation.svid !in 1..36 || observation.type !in listOf(0x0601, 0x0602))
            return invalid("not a supported Galileo I/NAV or F/NAV message")
        if (observation.status !in 0..2) return invalid("invalid receiver parity status")
        val input = Base64.getDecoder().decode(observation.dataBase64)
        val inav = observation.type == 0x0601
        if (input.size != if (inav) 29 else 30) return invalid("invalid Android Galileo page length")
        val padding = if (inav) 4 else 2
        if (input.last().toInt() and ((1 shl padding) - 1) != 0) return invalid("nonzero Galileo page padding")
        fun bit(index: Int) = (input[index / 8].toInt() ushr (7 - index % 8)) and 1
        if (inav && (bit(0) != 0 || bit(114) != 1)) return invalid("invalid even/odd I/NAV page parts")
        if (inav && (bit(1) != 0 || bit(115) != 0)) return invalid("I/NAV alert page is not nominal navigation")
        val protectedBits = if (inav) 196 else 214
        var crc = 0
        repeat(protectedBits) { index ->
            val feedback = (crc ushr 23) xor bit(index)
            crc = (crc shl 1) and 0xFFFFFF
            if (feedback != 0) crc = crc xor 0x864CFB
        }
        val transmitted = (0 until 24).fold(0) { value, index -> (value shl 1) or bit(protectedBits + index) }
        if (crc != transmitted) return invalid("Galileo CRC-24Q mismatch")
        val dataBits = if (inav) 128 else 214
        val data = ByteArray((dataBits + 7) / 8)
        repeat(dataBits) { index ->
            val source = if (!inav) index else if (index < 112) index + 2 else index + 4
            data[index / 8] = (data[index / 8].toInt() or (bit(source) shl (7 - index % 8))).toByte()
        }
        val wordType = data[0].toInt().and(255) ushr 2
        if (wordType != observation.submessageId) return invalid("Galileo word/submessage identity mismatch")
        if (wordType == 63) return invalid("Galileo dummy page contains no navigation solution")
        return GalileoPageResult(GalileoNavigationPage(observation.svid, observation.capturedAtMillis,
            if (inav) GalileoClockReference.INAV_E1_E5B else GalileoClockReference.FNAV_E1_E5A,
            wordType, data, dataBits))
    }
}
