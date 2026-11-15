package com.system.location.service.maps

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import android.util.Base64
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * One API key, encrypted at rest with a per-purpose Android Keystore key.
 *
 * The plaintext key never enters SharedPreferences or logs. A corrupt/foreign file is reported as
 * an invalid custom key so the caller can fall back to the bundled key or ask the user to save it
 * again. This is intentionally a small primitive shared by map providers.
 */
internal class EncryptedKeyStore(
    private val keyAlias: String,
    private val fileName: String,
) {
    private val storageLock = Any()

    fun read(context: Context): String? = synchronized(storageLock) {
        val app = context.applicationContext
        val file = AtomicFile(File(app.noBackupFilesDir, fileName))
        if (!file.baseFile.exists()) return null
        val fields = file.openRead().use { input ->
            KeyFilePolicy.readBounded(input, MAX_STORAGE_BYTES).toString(Charsets.US_ASCII)
        }.split('\n')
        require(fields.size == 3 && fields[0].toIntOrNull() == FORMAT_VERSION) {
            "自定义 Key 文件格式无效"
        }
        val iv = Base64.decode(fields[1], Base64.NO_WRAP)
        val ciphertext = Base64.decode(fields[2], Base64.NO_WRAP)
        require(iv.size == IV_BYTES && ciphertext.size in TAG_BYTES..MAX_CIPHERTEXT_BYTES) {
            "自定义 Key 文件数据无效"
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, iv))
        cipher.doFinal(ciphertext).toString(Charsets.UTF_8).takeIf(String::isNotBlank)
    }

    fun write(context: Context, value: String) = synchronized(storageLock) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val ciphertext = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        val payload = listOf(
            FORMAT_VERSION.toString(),
            Base64.encodeToString(cipher.iv, Base64.NO_WRAP),
            Base64.encodeToString(ciphertext, Base64.NO_WRAP),
        ).joinToString("\n").toByteArray(Charsets.US_ASCII)
        writeAtomically(context.applicationContext, payload)
    }

    fun clear(context: Context) = synchronized(storageLock) {
        val file = AtomicFile(File(context.applicationContext.noBackupFilesDir, fileName))
        if (file.baseFile.exists()) file.delete()
        runCatching {
            KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(keyAlias)
        }
        Unit
    }

    private fun writeAtomically(context: Context, payload: ByteArray) {
        val file = AtomicFile(File(context.noBackupFilesDir, fileName))
        val stream = file.startWrite()
        try {
            stream.write(payload)
            file.finishWrite(stream)
        } catch (failure: Throwable) {
            file.failWrite(stream)
            throw failure
        }
    }

    private fun secretKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(keyAlias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(
            keyAlias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true)
            .build())
        return generator.generateKey()
    }

    private companion object {
        const val FORMAT_VERSION = 1
        const val MAX_STORAGE_BYTES = 1024
        const val IV_BYTES = 12
        const val TAG_BYTES = 16
        // 256 UTF-8 bytes of key material plus the GCM tag.
        const val MAX_CIPHERTEXT_BYTES = 256 + TAG_BYTES
    }
}

/** Shared bounded-read rule used by tests and both map key stores. */
internal object KeyFilePolicy {
    fun readBounded(input: java.io.InputStream, maxBytes: Int): ByteArray {
        require(maxBytes >= 0)
        val output = java.io.ByteArrayOutputStream(minOf(maxBytes, 1024))
        val buffer = ByteArray(256)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) return output.toByteArray()
            require(count <= maxBytes - output.size()) { "自定义 Key 文件过大" }
            output.write(buffer, 0, count)
        }
    }
}
