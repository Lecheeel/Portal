package com.system.location.service.hook.hooks.telephony

import android.os.Bundle
import com.system.location.service.core.carrier.SimCarrierProfile
import kotlinx.serialization.json.Json

/** Read-only caller-scoped view; never exposes controller tokens, routing tables or real SIM IDs. */
object CarrierIdentitySnapshotCommand {
    const val COMMAND = "portal_carrier_identity_snapshot"
    const val PROFILE = "profile"
    fun read(request: Bundle): Boolean {
        request.remove(PROFILE)
        val kind = request.getString("kind") ?: return false
        val method = when (kind) {
            "phone" -> "getSimCountryIsoForPhone"
            "subscription" -> "getSimOperatorForSubscriber"
            else -> return false
        }
        if (!request.containsKey("id")) return false
        val profile = CarrierIdentityRuntime.profileForCall(method, listOf(request.getInt("id")))
            ?: return false
        request.putString(PROFILE, Json.encodeToString(SimCarrierProfile.serializer(), profile))
        return true
    }
}
