package com.system.location.service.maps

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.amap.api.location.AMapLocationClient
import com.amap.api.maps.MapsInitializer
import com.amap.api.services.core.ServiceSettings

object AMapKeySettings {
    private val store = EncryptedKeyStore("portal.amap.api.key.v1", "amap-api-key.enc")

    fun customKey(context: Context): String? = store.read(context.applicationContext)
        ?.let(AMapKeyPolicy::normalize)

    fun effectiveKey(context: Context): String {
        val app = context.applicationContext
        val customKey = runCatching { customKey(app) }.getOrNull()
        return customKey ?: bundledKey(app)
    }

    fun saveCustomKey(context: Context, value: String) {
        val key = AMapKeyPolicy.normalize(value)
        if (key == null) {
            clearCustomKey(context)
            return
        }
        store.write(context.applicationContext, key)
    }

    fun clearCustomKey(context: Context) = store.clear(context.applicationContext)

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
}
