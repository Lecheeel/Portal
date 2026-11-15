package com.system.location.service.maps

import android.content.Context
import android.content.pm.PackageManager
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import android.util.Base64
import android.os.Build
import com.amap.api.location.AMapLocationClient
import com.amap.api.maps.MapsInitializer
import com.amap.api.services.core.ServiceSettings
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

object AMapKeySettings {
    private const val KEY_ALIAS = "portal.amap.api.key.v1"
    private const val STORAGE_FILE = "amap-api-key.enc"
    private const val FORMAT_VERSION = 1
    private const val MAX_STORAGE_BYTES = 1024
    private const val GCM_IV_BYTES = 12
    private const val GCM_TAG_BYTES = 16
    private const val MAX_CIPHERTEXT_BYTES = 256 + GCM_TAG_BYTES
    private val storageLock = Any()

    fun customKey(context: Context): String? = synchronized(storageLock) {
        readEncrypted(context.applicationContext)
    }

    fun effectiveKey(context: Context): String {
        val app = context.applicationContext
        val file = File(app.noBackupFilesDir, STORAGE_FILE)
        val customKey = synchronized(storageLock) {
            if (file.exists()) runCatching { readEncrypted(app) }.getOrNull() else null
        }
        return customKey ?: bundledKey(app)
    }

    fun saveCustomKey(context: Context, value: String) {
        val key = AMapKeyPolicy.normalize(value)
        if (key == null) {
            clearCustomKey(context)
            return
        }
        synchronized(storageLock) {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, secretKey())
            val ciphertext = cipher.doFinal(key.toByteArray(Charsets.UTF_8))
            val payload = listOf(
                FORMAT_VERSION.toString(),
                Base64.encodeToString(cipher.iv, Base64.NO_WRAP),
                Base64.encodeToString(ciphertext, Base64.NO_WRAP),
            ).joinToString("\n").toByteArray(Charsets.US_ASCII)
            writeAtomically(context.applicationContext, payload)
        }
    }

    fun clearCustomKey(context: Context) {
        synchronized(storageLock) {
            val file = AtomicFile(File(context.applicationContext.noBackupFilesDir, STORAGE_FILE))
            if (file.baseFile.exists()) file.delete()
            runCatching {
                KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(KEY_ALIAS)
            }
        }
    }

    fun apply(context: Context): Boolean {
        val key = effectiveKey(context)
        if (key.isBlank()) return false
        MapsInitializer.setApiKey(key)
        ServiceSettings.getInstance().setApiKey(key)
        AMapLocationClient.setApiKey(key)
        return true
    }

    fun applicationSha1(context: Context): String {
        val packageManager = context.packageManager
        val info = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> packageManager.getPackageInfo(
                context.packageName,
                PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()),
            )
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.P -> packageManager.getPackageInfo(
                context.packageName,
                PackageManager.GET_SIGNING_CERTIFICATES,
            )
            else -> {
                @Suppress("DEPRECATION")
                packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES)
            }
        }
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.signingInfo?.apkContentsSigners.orEmpty().map { it.toByteArray() }
        } else {
            @Suppress("DEPRECATION")
            info.signatures.orEmpty().map { it.toByteArray() }
        }
        return AMapKeyPolicy.signerSha1(signatures)
    }

    private fun bundledKey(context: Context): String = runCatching {
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getApplicationInfo(
                context.packageName,
                PackageManager.ApplicationInfoFlags.of(PackageManager.GET_META_DATA.toLong()),
            )
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getApplicationInfo(context.packageName, PackageManager.GET_META_DATA)
        }
        info.metaData?.getString("com.amap.api.v2.apikey").orEmpty()
    }.getOrDefault("")

    private fun readEncrypted(context: Context): String? {
        val file = AtomicFile(File(context.noBackupFilesDir, STORAGE_FILE))
        if (!file.baseFile.exists()) return null
        val fields = file.openRead().use { input ->
            AMapKeyPolicy.readBounded(input, MAX_STORAGE_BYTES).toString(Charsets.US_ASCII)
        }.split('\n')
        require(fields.size == 3 && fields[0].toIntOrNull() == FORMAT_VERSION) { "自定义 Key 文件格式无效" }
        val iv = Base64.decode(fields[1], Base64.NO_WRAP)
        val ciphertext = Base64.decode(fields[2], Base64.NO_WRAP)
        require(iv.size == GCM_IV_BYTES && ciphertext.size in GCM_TAG_BYTES..MAX_CIPHERTEXT_BYTES) {
            "自定义 Key 文件数据无效"
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, iv))
        return cipher.doFinal(ciphertext).toString(Charsets.UTF_8)
            .takeIf(String::isNotBlank)?.let(AMapKeyPolicy::normalize)
    }

    private fun writeAtomically(context: Context, payload: ByteArray) {
        val file = AtomicFile(File(context.noBackupFilesDir, STORAGE_FILE))
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
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true)
            .build())
        return generator.generateKey()
    }
}
