package com.system.location.service.hook.hooks.telephony

import android.content.pm.PackageManager
import com.system.location.service.hook.utils.BinderUtils

/** Virtual subscriptions have no carrier access rules, SMS role or device-owner delegation. */
internal object CarrierIdentifierAccess {
    fun permitted(): Boolean = runCatching {
        val context = BinderUtils.getSystemContext() ?: return false
        context.checkCallingOrSelfPermission("android.permission.READ_PRIVILEGED_PHONE_STATE") ==
            PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)
}
