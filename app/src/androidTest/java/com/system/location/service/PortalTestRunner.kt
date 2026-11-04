package com.system.location.service

import androidx.test.runner.AndroidJUnitRunner
import android.app.Application

/** No implicit agreement: each map test fixture explicitly accepts maps. */
class PortalTestRunner : AndroidJUnitRunner() {
    override fun callApplicationOnCreate(app: Application) {
        // Clear BEFORE Application restores choices or initializes third-party SDKs.
        if (androidx.test.platform.app.InstrumentationRegistry.getArguments().getString("freshPrivacy") == "true") {
            check(app.getSharedPreferences("sdk_privacy", 0).edit().clear().commit())
        }
        super.callApplicationOnCreate(app)
    }
}
