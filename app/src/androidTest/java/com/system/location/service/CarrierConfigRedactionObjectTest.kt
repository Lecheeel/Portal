package com.system.location.service

import android.os.PersistableBundle
import com.system.location.service.core.carrier.SimCarrierProfile
import org.junit.Assert.*
import org.junit.Test

class CarrierConfigRedactionObjectTest {
    @Test fun emptyRedactedConfigIsNeverExpandedAndSubsetDoesNotGainUnrequestedFields() {
        val type = Class.forName("com.system.location.service.hook.hooks.telephony.CarrierConfigReadHook")
        val instance = type.getField("INSTANCE").get(null)
        val method = type.declaredMethods.single { it.name.startsWith("overlay") && it.parameterCount == 3 }
        method.isAccessible = true
        val profile = SimCarrierProfile(1, virtual = true, countryIso = "US", mcc = 310,
            mnc = 260, carrierName = "Test")
        fun overlay(bundle: PersistableBundle, requested: Set<String>? = null) =
            method.invoke(instance, bundle, profile, requested) as PersistableBundle
        val empty = PersistableBundle()
        assertSame(empty, overlay(empty))
        assertSame(empty, overlay(empty, setOf("carrier_name_string")))
        val original = PersistableBundle().apply { putBoolean("unrelated", true) }
        val subset = overlay(original, setOf("carrier_name_string"))
        assertEquals("Test", subset.getString("carrier_name_string"))
        assertEquals(setOf("unrelated", "carrier_name_string"), subset.keySet())
        assertEquals(setOf("unrelated"), original.keySet())
        val untouched = overlay(original, emptySet())
        assertEquals(original.keySet(), untouched.keySet())
        val full = overlay(original)
        assertEquals("us", full.getString("sim_country_iso_override_string"))
        assertEquals(1, full.getInt("carrier_id_int"))
        assertTrue(full.getBoolean("unrelated"))
        assertFalse(original.containsKey("carrier_id_int"))
    }
}
