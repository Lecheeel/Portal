package com.system.location.service.data.persistence

import android.content.SharedPreferences
import com.system.location.service.core.carrier.CarrierIdentityCodec
import com.system.location.service.core.carrier.CarrierIdentityConfig
import java.security.SecureRandom

/** Assign once in the controller, persist before publication, and send the same value to peers. */
object CarrierIdentityPreferences {
    private val random = SecureRandom()

    fun seedMissing(config: CarrierIdentityConfig, occupiedIds: Set<Int> = emptySet()): CarrierIdentityConfig {
        val assigned = (occupiedIds + config.sims.map { it.virtualSubscriptionId }).toMutableSet()
        return config.copy(sims = config.sims.map { profile ->
            var seed = profile.identitySeed
            if (seed == 0L) do { seed = random.nextLong() } while (seed == 0L)
            var id = profile.virtualSubscriptionId
            if (id == 0) {
                val available = (1..65535).filterNot { it in assigned }
                check(available.isNotEmpty()) { "No virtual subscription ID available" }
                id = available[random.nextInt(available.size)]
                assigned.add(id)
            }
            profile.copy(identitySeed = seed, virtualSubscriptionId = id)
        })
    }

    fun occupiedIds(context: android.content.Context): Set<Int> = runCatching {
        context.getSystemService(android.telephony.SubscriptionManager::class.java)
            ?.activeSubscriptionInfoList.orEmpty().map { it.subscriptionId }.toSet()
    }.getOrDefault(emptySet())

    @Synchronized
    fun migrate(preferences: SharedPreferences, occupiedIds: Set<Int> = emptySet()): CarrierIdentityConfig? {
        val raw = preferences.getString("carrierIdentityJson", "").orEmpty()
        if (raw.isBlank()) return null
        val config = CarrierIdentityCodec.decode(raw)
        if (config.sims.none { it.identitySeed == 0L || it.virtualSubscriptionId == 0 }) return config
        val seeded = seedMissing(config, occupiedIds)
        check(preferences.edit().putString("carrierIdentityJson", CarrierIdentityCodec.encode(seeded)).commit()) {
            "Unable to persist SIM identity seeds"
        }
        return seeded
    }
}
