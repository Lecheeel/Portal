package com.system.location.service

import androidx.test.runner.AndroidJUnitRunner
import android.app.Application
import com.system.location.service.ext.sharedPrefs

/** No implicit agreement: each map test fixture explicitly accepts maps. */
class PortalTestRunner : AndroidJUnitRunner() {
    override fun callApplicationOnCreate(app: Application) {
        // Clear BEFORE Application restores choices or initializes third-party SDKs.
        if (androidx.test.platform.app.InstrumentationRegistry.getArguments().getString("freshPrivacy") == "true") {
            // Both vendors' consent lives here, including Tencent's tencent_maps_v1.
            check(app.getSharedPreferences("sdk_privacy", 0).edit().clear().commit())
            // Exercise the actual no-preference default, even after a prior provider test.
            check(app.sharedPrefs.edit().remove("map_provider").commit())
        }
        super.callApplicationOnCreate(app)
    }
}
