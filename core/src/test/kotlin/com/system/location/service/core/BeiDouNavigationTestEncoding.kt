package com.system.location.service.core

import com.system.location.service.core.gnss.*
import java.util.Base64

/** Independently encode edited ICD logical fields as BCH/interleaved wire words. */
internal object BeiDouNavigationTestEncoding {
    fun shifted(observation: GnssNavigationObservation, seconds: Int): GnssNavigationObservation {
        val frame = BeiDouNavigationWords.decode(observation).subframe!!
        val bits = IntArray(300)
        for (word in 0..9) {
            val length = if (word == 0) 26 else 22
            val value = frame.rawUnsigned(word * 30, length)
            repeat(length) { bit -> bits[word * 30 + bit] = (value ushr (length - 1 - bit) and 1).toInt() }
        }
        val sow = Math.floorMod(frame.secondsOfWeek + seconds, 604800)
        repeat(8) { bits[18 + it] = sow ushr (19 - it) and 1 }
        repeat(12) { bits[30 + it] = sow ushr (11 - it) and 1 }
        fun value(start: Int, length: Int) = (start until start + length).fold(0) { n, i -> n * 2 + bits[i] }
        fun bch(data: Int): Int {
            var remainder = data shl 4
            for (bit in 14 downTo 4) if (remainder and (1 shl bit) != 0) remainder = remainder xor (0x13 shl (bit - 4))
            return data shl 4 or (remainder and 15)
        }
        val words = IntArray(10)
        words[0] = value(0, 26) shl 4 or (bch(value(15, 11)) and 15)
        for (word in 1..9) {
            val first = bch(value(word * 30, 11))
            val second = bch(value(word * 30 + 11, 11))
            repeat(15) { bit -> words[word] = words[word] or ((first ushr bit and 1) shl (bit * 2 + 1)) or
                ((second ushr bit and 1) shl (bit * 2)) }
        }
        val bytes = ByteArray(40)
        words.forEachIndexed { index, word -> repeat(4) { bytes[index * 4 + it] = (word ushr (24 - it * 8)).toByte() } }
        return observation.copy(dataBase64 = Base64.getEncoder().encodeToString(bytes))
    }
}
