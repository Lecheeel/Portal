package com.system.location.service

import android.app.Application
import android.content.Context
import com.amap.api.location.AMapLocationClient
import com.amap.api.maps.MapsInitializer
import com.tencent.bugly.crashreport.CrashReport
import com.system.location.service.android.Bugly

class LocationServiceApp: Application() {

    override fun onCreate() {
        super.onCreate()

        // 高德 SDK 隐私合规（必须最先调用，否则 SDK 无法启动）
        MapsInitializer.updatePrivacyShow(this, true, true)
        MapsInitializer.updatePrivacyAgree(this, true)
        AMapLocationClient.updatePrivacyShow(this, true, true)
        AMapLocationClient.updatePrivacyAgree(this, true)

        CrashReport.initCrashReport(applicationContext)

        CrashReport.setUserId(applicationContext, Bugly.getUniqueDeviceId(applicationContext))
        CrashReport.setDeviceId(applicationContext, Bugly.getUniqueDeviceId(applicationContext))
        CrashReport.setDeviceModel(applicationContext, Bugly.getDeviceModel())
        CrashReport.setCollectPrivacyInfo(applicationContext, true)

        appContext = applicationContext

        //CrashReport.setAllThreadStackEnable(applicationContext, true, true)
    }

    companion object {
        lateinit var appContext: Context
    }
}
