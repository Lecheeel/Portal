package com.system.location.service.hook.hooks.wlan

import com.system.location.service.hook.utils.BinderUtils

/** The system uses package "android", which the vendor package-name filter does not cover. */
internal object WifiCallerPolicy {
    fun mayRewrite(packageName: String, uid: Int): Boolean =
        uid >= 0 && uid % 100000 >= 10000 && packageName != "android" &&
            !BinderUtils.isSystemPackages(packageName)
}
