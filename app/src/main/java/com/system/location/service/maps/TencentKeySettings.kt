package com.system.location.service.maps

import android.content.Context
import com.system.location.service.BuildConfig

/**
 * Tencent map key storage.
 *
 * The WebService endpoints read the effective key at request time, so a saved key applies without
 * restarting Portal. The vector map SDK reads its key during SDK initialization; that value is
 * documented separately on the map-key screen and falls back to the build-time key when the SDK
 * does not support a runtime override.
 */
object TencentKeySettings {
    private val store = EncryptedKeyStore("portal.tencent.api.key.v1", "tencent-api-key.enc")

    fun customKey(context: Context): String? = store.read(context.applicationContext)
        ?.let(TencentKeyPolicy::normalize)

    fun effectiveKey(context: Context): String =
        runCatching { customKey(context) }.getOrNull()
            ?: BuildConfig.TENCENT_MAP_KEY.takeIf(String::isNotBlank).orEmpty()

    fun saveCustomKey(context: Context, value: String) {
        val key = TencentKeyPolicy.normalize(value)
        if (key == null) {
            clearCustomKey(context)
            return
        }
        store.write(context.applicationContext, key)
    }

    fun clearCustomKey(context: Context) = store.clear(context.applicationContext)

    /** No public Tencent SDK key setter exists; WebService reads the value per request. */
    fun apply(context: Context): Boolean = effectiveKey(context).isNotBlank()
}

internal object TencentKeyPolicy {
    fun normalize(value: String): String? {
        val key = value.trim()
        if (key.isEmpty()) return null
        require(key.length in 8..128 && key.all {
            it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == '-' || it == '_'
        }) {
            "腾讯 Key 格式不合法"
        }
        return key
    }
}
