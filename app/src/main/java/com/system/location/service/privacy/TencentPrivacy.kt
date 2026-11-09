package com.system.location.service.privacy

import android.content.Context
import com.tencent.tencentmap.mapsdk.maps.TencentMapInitializer

/** AMap consent is never interpreted as Tencent consent. */
object TencentPrivacy {
    private var initialized = false
    fun ready(context: Context): Boolean {
        if (!context.getSharedPreferences("sdk_privacy", 0).getBoolean("tencent_maps_v1", false)) return false
        if (!initialized) {
            TencentMapInitializer.setAgreePrivacy(context.applicationContext, true)
            TencentMapInitializer.start(context.applicationContext)
            initialized = true
        }
        return true
    }
    fun accept(context: Context) {
        val prefs = context.getSharedPreferences("sdk_privacy", 0)
        if (!prefs.edit().putBoolean("tencent_maps_v1", true).commit()) {
            prefs.edit().remove("tencent_maps_v1").commit()
            error("隐私选择保存失败，请重试")
        }
        check(ready(context))
    }
}
