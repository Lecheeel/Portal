package com.system.location.service.hook.hooks.telephony

/** Private system-server/phone-process handshake; request metadata never reaches carrier config. */
internal object CarrierOverrideProtocol {
    const val FEATURE = "portal.carrier.override-capability.v1"
    const val SUPPORTED = "portal_carrier_override_supported"
    const val OWNED = "portal_carrier_override_owned"
    const val PENDING = "portal_carrier_override_pending"
    const val ACTION = "portal_carrier_override_action"
    const val APPLY = "apply"
    const val RESTORE = "restore"
    val FIELDS = setOf("sim_country_iso_override_string", "carrier_name_override_bool",
        "carrier_name_string", "force_home_network_bool")
}
