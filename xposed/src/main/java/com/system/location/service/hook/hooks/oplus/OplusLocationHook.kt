package com.system.location.service.hook.hooks.oplus

import android.location.LocationListener
import android.os.Bundle
import de.robv.android.xposed.XposedHelpers
import com.system.location.service.hook.BaseLocationHook
import com.system.location.service.hook.hooks.blindhook.BlindHookLocation
import com.system.location.service.hook.hooks.blindhook.BlindHookLocation.invoke
import com.system.location.service.hook.hooks.fused.ThirdPartyLocationHook
import com.system.location.service.hook.utils.FakeLoc
import com.system.location.service.hook.utils.Logger
import com.system.location.service.hook.utils.hookMethodAfter
import com.system.location.service.hook.utils.onceHookMethodBefore
import com.system.location.service.hook.utils.toClass
import java.lang.reflect.Modifier

object OplusLocationHook: BaseLocationHook() {
    operator fun invoke(classLoader: ClassLoader) {
        ThirdPartyLocationHook(classLoader)
    }
}
