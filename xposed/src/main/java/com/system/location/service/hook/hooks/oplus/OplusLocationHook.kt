package com.system.location.service.hook.hooks.oplus

import com.system.location.service.hook.BaseLocationHook
import com.system.location.service.hook.hooks.fused.ThirdPartyLocationHook

object OplusLocationHook : BaseLocationHook() {
    operator fun invoke(classLoader: ClassLoader) {
        ThirdPartyLocationHook(classLoader)
    }
}
