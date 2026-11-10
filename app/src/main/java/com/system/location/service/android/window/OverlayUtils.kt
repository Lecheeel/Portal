package com.system.location.service.android.window

import android.content.Context
import android.provider.Settings
import android.util.Log

object OverlayUtils {
    fun hasOverlayPermissions(context: Context): Boolean {
        kotlin.runCatching {
            return Settings.canDrawOverlays(context)
        }.onFailure {
            Log.e("OverlayUtils", "hasOverlayPermissions: ", it)
        }
        return false
    }
}
