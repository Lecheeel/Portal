package com.system.location.service.hook.hooks.miui

import android.os.Build
import android.os.SystemClock
import android.telephony.CellIdentity
import android.telephony.CellIdentityCdma
import android.telephony.CellInfo
import android.telephony.CellInfoCdma
import android.telephony.CellSignalStrengthCdma
import com.system.location.service.hook.BaseLocationHook
import com.system.location.service.hook.hooks.blindhook.BlindHookLocation
import com.system.location.service.hook.hooks.telephony.currentCdmaIdentity
import com.system.location.service.hook.runtime.Reflection
import com.system.location.service.hook.utils.BinderUtils
import com.system.location.service.hook.utils.FakeLoc
import com.system.location.service.hook.utils.onceHookAllMethod
import com.system.location.service.hook.utils.onceHookMethod
import io.github.libxposed.api.XposedInterface

object MiuiBlurLocationProviderHook : BaseLocationHook() {
    operator fun invoke(classLoader: ClassLoader) {
        val cMiuiBlurLocationManagerImpl =
            Reflection.findClassIfExists(
                "com.android.server.location.MiuiBlurLocationManagerImpl",
                classLoader,
            )
        if (cMiuiBlurLocationManagerImpl != null) {
            BlindHookLocation(cMiuiBlurLocationManagerImpl, classLoader)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val hooker =
                    XposedInterface.Hooker { chain ->
                        val original = chain.proceed()
                        if (FakeLoc.enable && !BinderUtils.isSystemAppsCall()) {
                            val identity = currentCdmaIdentity()
                            CellIdentityCdma::class
                                .java
                                .getConstructor(
                                    Int::class.java,
                                    Int::class.java,
                                    Int::class.java,
                                    Int::class.java,
                                    Int::class.java,
                                    String::class.java,
                                    String::class.java,
                                )
                                .newInstance(
                                    identity.networkId,
                                    identity.systemId,
                                    identity.baseStationId,
                                    identity.latitudeQuarterMinutes,
                                    identity.longitudeQuarterMinutes,
                                    null,
                                    null,
                                )
                        } else original
                    }
                if (
                    cMiuiBlurLocationManagerImpl.onceHookMethod(
                        "getBlurryCellLocation",
                        CellIdentity::class.java,
                        callback = hooker,
                    ) == null
                ) {
                    cMiuiBlurLocationManagerImpl.onceHookMethod(
                        "getBlurryCellLocation",
                        CellIdentity::class.java,
                        Int::class.java,
                        String::class.java,
                        callback = hooker,
                    )
                }
            }

            cMiuiBlurLocationManagerImpl.onceHookAllMethod(
                "getBlurryCellInfos",
                XposedInterface.Hooker intercept69@{ chain ->
                    val args = chain.args.toTypedArray()
                    // Preserve the framework/blur query result first, then replace only on success.
                    val original = chain.proceed(args)
                    if (!FakeLoc.enable || BinderUtils.isSystemAppsCall()) return@intercept69 original
                    val identity = currentCdmaIdentity()
                    val cellInfo = kotlin.runCatching {
                        val cellIdentity = CellIdentityCdma::class
                            .java
                            .getConstructor(
                                Int::class.java,
                                Int::class.java,
                                Int::class.java,
                                Int::class.java,
                                Int::class.java,
                                String::class.java,
                                String::class.java,
                            )
                            .newInstance(
                                identity.networkId,
                                identity.systemId,
                                identity.baseStationId,
                                identity.latitudeQuarterMinutes,
                                identity.longitudeQuarterMinutes,
                                null,
                                null,
                            )
                        CellInfoCdma::class
                            .java
                            .getConstructor(
                                Int::class.java,
                                Boolean::class.java,
                                Long::class.java,
                                CellIdentityCdma::class.java,
                                CellSignalStrengthCdma::class.java,
                            )
                            .newInstance(
                                0,
                                true,
                                SystemClock.elapsedRealtimeNanos(),
                                cellIdentity,
                                CellSignalStrengthCdma::class.java.newInstance(),
                            )
                    }.getOrNull() ?: return@intercept69 original
                    arrayListOf<CellInfo>(cellInfo)
                },
            )
        }
    }
}
