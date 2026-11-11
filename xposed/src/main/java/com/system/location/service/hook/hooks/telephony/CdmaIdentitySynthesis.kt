package com.system.location.service.hook.hooks.telephony

import com.system.location.service.hook.utils.FakeLoc

/** Deterministic CDMA sector identity shared by the telephony and MIUI compatibility paths. */
internal data class CdmaIdentity(
    val cid: Int,
    val lac: Int,
    val psc: Int,
    val baseStationId: Int,
    val systemId: Int,
    val networkId: Int,
    val latitudeQuarterMinutes: Int,
    val longitudeQuarterMinutes: Int,
)

/**
 * A real sector is a nearby tower, not a tower exactly at the device's coordinates. The old
 * `Int.MAX_VALUE` sentinel in every field was a more obvious synthetic marker than a missing cell.
 */
internal fun currentCdmaIdentity(): CdmaIdentity {
    val random = kotlin.random.Random(FakeLoc.environmentSeed.toInt() xor 0x43444D41)
    val latitudeOffset = random.nextInt(-40, 41) / 14_400.0
    val longitudeOffset = random.nextInt(-40, 41) / 14_400.0
    return CdmaIdentity(
        cid = random.nextInt(0, 65_536),
        lac = random.nextInt(0, 65_536),
        psc = random.nextInt(0, 512),
        baseStationId = random.nextInt(0, 65_536),
        systemId = random.nextInt(0, 32_768),
        networkId = random.nextInt(0, 65_536),
        latitudeQuarterMinutes = ((FakeLoc.latitude + latitudeOffset) * 14_400.0).toInt(),
        longitudeQuarterMinutes = ((FakeLoc.longitude + longitudeOffset) * 14_400.0).toInt(),
    )
}
