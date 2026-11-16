package com.system.location.service.data.persistence

import android.content.SharedPreferences
import com.system.location.service.core.carrier.CarrierIdentityCodec
import com.system.location.service.core.carrier.CarrierIdentityConfig
import java.security.SecureRandom

/** Assign once in the controller, persist before publication, and send the same value to peers. */
object CarrierIdentityPreferences {
    private val random = SecureRandom()

    fun seedMissing(config: CarrierIdentityConfig): CarrierIdentityConfig = config.copy(
        sims = config.sims.map { profile ->
            if (profile.identitySeed != 0L) profile else {
                var seed: Long
                do { seed = random.nextLong() } while (seed == 0L)
                profile.copy(identitySeed = seed)
            }
        },
    )

    @Synchronized
    fun migrate(preferences: SharedPreferences): CarrierIdentityConfig? {
        val raw = preferences.getString("carrierIdentityJson", "").orEmpty()
        if (raw.isBlank()) return null
        val config = CarrierIdentityCodec.decode(raw)
        if (config.sims.none { it.identitySeed == 0L }) return config
        val seeded = seedMissing(config)
        check(preferences.edit().putString("carrierIdentityJson", CarrierIdentityCodec.encode(seeded)).commit()) {
            "Unable to persist SIM identity seeds"
        }
        return seeded
    }
}
