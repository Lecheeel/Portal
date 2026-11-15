package com.system.location.service.hook.hooks.telephony

import com.system.location.service.core.routing.RoutingDecision
import com.system.location.service.core.geo.Wgs84
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TelephonyCallerPolicyTest {
    @Test
    fun onlyEnabledNonSystemCallsOnAllowedRoutesAreRewritten() {
        assertTrue(TelephonyCallerPolicy.shouldRewrite(true, false, RoutingDecision.Global))
        assertTrue(TelephonyCallerPolicy.shouldRewrite(true, false, RoutingDecision.Point(Wgs84(1.0, 2.0))))
        assertFalse(TelephonyCallerPolicy.shouldRewrite(true, false, RoutingDecision.Deny))
        assertFalse(TelephonyCallerPolicy.shouldRewrite(false, false, RoutingDecision.Global))
        assertFalse(TelephonyCallerPolicy.shouldRewrite(true, true, RoutingDecision.Global))
    }
}
