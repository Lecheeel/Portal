package com.system.location.service.hook.hooks.telephony

import android.app.AppOpsManager
import android.content.pm.PackageManager
import android.os.Binder
import com.system.location.service.hook.utils.BinderUtils

/** Modeled numbers still require the caller's phone-number permission and matching AppOp. */
internal object CarrierPhoneNumberAccess {
    fun permitted(packageName: String? = null, legacy: Boolean = true): Boolean = runCatching {
        val context = BinderUtils.getSystemContext() ?: return false
        if (CarrierIdentifierAccess.permitted()) return true
        val uid = Binder.getCallingUid()
        val packages = context.packageManager.getPackagesForUid(uid)?.toList().orEmpty()
        val name = if (packageName != null) packageName.takeIf { it in packages } ?: return false
            else packages.singleOrNull() ?: return false
        val ops = context.getSystemService(AppOpsManager::class.java)
        fun allowed(permission: String, operation: String): Boolean =
            context.checkCallingOrSelfPermission(permission) == PackageManager.PERMISSION_GRANTED &&
                ops.checkOpNoThrow(operation, uid, name) == AppOpsManager.MODE_ALLOWED
        if (allowed("android.permission.READ_PHONE_NUMBERS", AppOpsManager.OPSTR_READ_PHONE_NUMBERS)) return true
        if (!legacy) return false
        if (allowed("android.permission.READ_SMS", AppOpsManager.OPSTR_READ_SMS)) return true
        if (context.packageManager.getApplicationInfo(name, 0).targetSdkVersion <= 29 &&
            allowed("android.permission.READ_PHONE_STATE", AppOpsManager.OPSTR_READ_PHONE_STATE)) return true
        name == android.provider.Telephony.Sms.getDefaultSmsPackage(context) &&
            ops.checkOpNoThrow("android:write_sms", uid, name) == AppOpsManager.MODE_ALLOWED
    }.getOrDefault(false)
}
