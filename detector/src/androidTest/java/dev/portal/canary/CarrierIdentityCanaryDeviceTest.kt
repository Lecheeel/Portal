package dev.portal.canary

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Reads the public telephony surface from a target app process while a virtual SIM is active. */
class CarrierIdentityCanaryDeviceTest {
    @Test fun readVirtualTelephonySurface() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assumeTrue(context.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) ==
            PackageManager.PERMISSION_GRANTED)
        val manager = context.getSystemService(TelephonyManager::class.java)
        val subscriptions = SubscriptionManager.from(context)
        val values = buildString {
            append("simState=").append(manager.simState)
            append(" simOperator=").append(manager.simOperator)
            append(" simOperatorName=").append(manager.simOperatorName)
            append(" simCountry=").append(manager.simCountryIso)
            append(" networkOperator=").append(manager.networkOperator)
            append(" networkType=").append(manager.networkType)
            append(" networkOperatorName=").append(manager.networkOperatorName)
            append(" serviceState=").append(manager.serviceState?.state)
            append(" serviceOperator=").append(manager.serviceState?.operatorNumeric)
            append(" phoneType=").append(manager.phoneType)
            append(" carrierId=").append(manager.simCarrierId)
            append(" active=").append(
                subscriptions.activeSubscriptionInfoList?.map { it.subscriptionId to it.carrierName })
        }
        System.err.println("PORTAL_CARRIER_TARGET $values")
        assumeTrue("virtual carrier fixture is active", manager.simOperator == "310260")
        assertEquals(TelephonyManager.SIM_STATE_READY, manager.simState)
        assertEquals("T-Mobile", manager.simOperatorName)
        assertEquals("us", manager.simCountryIso)
        assertEquals("310260", manager.networkOperator)
        assertEquals(TelephonyManager.NETWORK_TYPE_NR, manager.networkType)
        assertEquals(TelephonyManager.PHONE_TYPE_GSM, manager.phoneType)
        assertEquals(android.telephony.ServiceState.STATE_IN_SERVICE, manager.serviceState?.state)
        assertEquals("310260", manager.serviceState?.operatorNumeric)
        assertEquals("T-Mobile USA", manager.serviceState?.operatorAlphaLong)
        assertTrue(manager.simCarrierId > 0)
        assertTrue(subscriptions.activeSubscriptionInfoList.orEmpty().any {
            it.subscriptionId == 900000001 && it.carrierName == "T-Mobile"
        })
    }
}
