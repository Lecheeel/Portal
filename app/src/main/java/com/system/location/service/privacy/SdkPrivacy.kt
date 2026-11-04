package com.system.location.service.privacy

import android.content.Context
import com.amap.api.location.AMapLocationClient
import com.amap.api.maps.MapsInitializer
import com.tencent.bugly.crashreport.CrashReport
import java.util.UUID

/** Map consent and optional crash reporting are separate, persisted choices. */
object SdkPrivacy {
    private lateinit var application: Context
    private var stopFailed = false
    private val preferences get() = application.getSharedPreferences("sdk_privacy", Context.MODE_PRIVATE)
    private val controller by lazy {
        SdkConsentController(object : SdkConsentStore {
            override fun read() = SdkConsent(
                preferences.getBoolean("maps_accepted", false),
                preferences.getBoolean("crash_reporting", false)
            )
            override fun write(value: SdkConsent) {
                check(preferences.edit().putBoolean("maps_accepted", value.mapsAccepted)
                    .putBoolean("crash_reporting", value.crashReporting).commit()) { "隐私选择保存失败，请重试" }
            }
        }, object : ConsentSdk {
            override fun initializeMaps() {
                MapsInitializer.updatePrivacyShow(application, true, true)
                MapsInitializer.updatePrivacyAgree(application, true)
                AMapLocationClient.updatePrivacyShow(application, true, true)
                AMapLocationClient.updatePrivacyAgree(application, true)
            }
            override fun setCrashReporting(enabled: Boolean) {
                if (enabled) {
                    CrashReport.enableBugly(true)
                    CrashReport.setCollectPrivacyInfo(application, false)
                    var installationId = preferences.getString("crash_installation_id", null)
                    if (installationId == null) {
                        installationId = UUID.randomUUID().toString()
                        if (!preferences.edit().putString("crash_installation_id", installationId).commit()) {
                            preferences.edit().remove("crash_installation_id").commit()
                            error("崩溃上报标识保存失败")
                        }
                    }
                    CrashReport.setDeviceId(application, installationId)
                    CrashReport.initCrashReport(application)
                    // Bugly can silently skip initialization (for example without an app id).
                    check(com.tencent.bugly.CrashModule.getInstance().hasInitialized()) { "崩溃上报服务初始化失败" }
                } else {
                    // closeBugly unregisters user-info callbacks and shuts down the shared
                    // executor too. It must run BEFORE enableBugly(false), which gates it.
                    // The controller never tries to reopen it in this process.
                    if (stopFailed) CrashReport.enableBugly(true) // closeBugly is gated by this flag.
                    try { CrashReport.closeBugly(); stopFailed = false }
                    catch (failure: Throwable) { stopFailed = true; throw failure }
                    finally { CrashReport.enableBugly(false) }
                }
            }
        })
    }
    val mapsAccepted get() = controller.mapsAccepted
    val mapsReady get() = controller.mapsReady
    val crashReportingEnabled get() = controller.crashReportingEnabled
    val crashReportingRequested get() = controller.crashReportingRequested
    val crashReportingPendingRestart get() = controller.crashReportingPendingRestart
    fun initialize(context: Context) {
        application = context.applicationContext
        controller.restore()
    }
    fun acceptMaps() = controller.acceptMaps()
    fun setCrashReporting(enabled: Boolean) = controller.setCrashReporting(enabled)
}
