package com.system.location.service.maps

import java.security.MessageDigest
import java.io.ByteArrayOutputStream
import java.io.InputStream

internal object AMapKeyPolicy {
    fun readBounded(input: InputStream, maxBytes: Int): ByteArray {
        require(maxBytes >= 0)
        val output = ByteArrayOutputStream(minOf(maxBytes, 1024))
        val buffer = ByteArray(256)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) return output.toByteArray()
            require(count <= maxBytes - output.size()) { "自定义 Key 文件过大" }
            output.write(buffer, 0, count)
        }
    }

    fun effectiveKey(hasCustomKey: Boolean, readCustomKey: () -> String?, bundledKey: () -> String): String {
        if (hasCustomKey) runCatching(readCustomKey).getOrNull()?.let { return it }
        return bundledKey()
    }

    fun normalize(value: String): String? {
        val key = value.trim()
        if (key.isEmpty()) return null
        require(key.length in 8..256 && key.all {
            it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == '-' || it == '_'
        }) {
            "高德 Key 格式不合法"
        }
        return key
    }

    fun signerSha1(signatures: List<ByteArray>): String {
        require(signatures.isNotEmpty()) { "无法读取当前安装包签名" }
        return signatures.map { signature ->
            MessageDigest.getInstance("SHA-1").digest(signature)
                .joinToString(":") { byte -> "%02X".format(byte) }
        }.sorted().joinToString("\n")
    }
}
