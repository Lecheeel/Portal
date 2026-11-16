package com.system.location.service

import android.os.Parcel
import android.telephony.SubscriptionInfo
import com.system.location.service.core.carrier.SimCarrierProfile
import org.junit.Assert.*
import org.junit.Test

/** Requires host-side temporary hidden API access, as do the other platform object tests. */
class CarrierVirtualSubscriptionObjectTest {
    @Test fun platformBuilderProducesCompleteCopyableAndParcelableSubscriptions() {
        val type = Class.forName("com.system.location.service.hook.hooks.telephony.CarrierVirtualSubscription")
        val instance = type.getField("INSTANCE").get(null)
        val method = type.getDeclaredMethod("info", SimCarrierProfile::class.java)
        for (slot in 1..2) {
            val profile = SimCarrierProfile(slot, virtual = true, countryIso = "AU", mcc = 505,
                mnc = 1, mncDigits = 2, carrierName = "Test AU")
            val info = method.invoke(instance, profile) as? SubscriptionInfo
            assertNotNull("Builder must produce a complete subscription", info)
            val parcel = Parcel.obtain()
            try {
                info!!.writeToParcel(parcel, 0)
                parcel.setDataPosition(0)
                val copy = SubscriptionInfo.CREATOR.createFromParcel(parcel)
                assertEquals(info, copy)
                assertEquals(info.hashCode(), copy.hashCode())
                assertEquals(slot - 1, copy.simSlotIndex)
                assertEquals("505", copy.mccString)
                assertEquals("01", copy.mncString)
                assertEquals("au", copy.countryIso)
                assertEquals("Test AU", copy.carrierName.toString())
                assertNotNull(copy.toString())
                val builder = Class.forName("android.telephony.SubscriptionInfo\$Builder")
                    .getConstructor(SubscriptionInfo::class.java).newInstance(copy)
                assertEquals(copy, builder.javaClass.getMethod("build").invoke(builder))
            } finally { parcel.recycle() }
        }
    }
}
