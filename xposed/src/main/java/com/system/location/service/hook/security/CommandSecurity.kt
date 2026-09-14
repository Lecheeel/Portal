package com.system.location.service.hook.security

import java.security.SecureRandom

enum class CallerRole { CONTROLLER, PEER, DENIED }

/** Package ownership comes from PackageManager, never from the request Bundle. */
object CallerPolicy {
    const val CONTROLLER_PACKAGE = "com.system.location.service"
    val peerPackages = setOf("android", "com.android.phone", "com.android.location.fused",
        "com.xiaomi.location.fused", "com.oplus.location")

    fun resolve(uid: Int, packages: Set<String>, controllerUid: Int?, systemPackages: Set<String>): CallerRole {
        if (uid < 0 || packages.isEmpty()) return CallerRole.DENIED
        if (uid == controllerUid && packages == setOf(CONTROLLER_PACKAGE)) return CallerRole.CONTROLLER
        if (packages.any { it in peerPackages && it in systemPackages }) return CallerRole.PEER
        return CallerRole.DENIED
    }
}

/** One session per caller UID. A new handshake invalidates the previous session. */
class CommandSecurity(private val tokenFactory: () -> String = {
    ByteArray(32).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
}) {
    private data class Session(val token: String, var sequence: Long = 0)
    private val sessions = mutableMapOf<Int, Session>()

    @Synchronized fun exchange(uid: Int, role: CallerRole): String? {
        if (role == CallerRole.DENIED) return null
        return tokenFactory().also { sessions[uid] = Session(it) }
    }

    @Synchronized fun accept(uid: Int, role: CallerRole, token: String, command: String?,
        version: Int, sequence: Long, sentAt: Long, now: Long): Boolean {
        if (role == CallerRole.DENIED || command !in commands || version != VERSION) return false
        if (role == CallerRole.PEER && command !in peerCommands) return false
        val session = sessions[uid] ?: return false
        if (token != session.token || sequence <= session.sequence || sentAt < 0 ||
            sentAt > now || now - sentAt > MAX_AGE_MS) return false
        session.sequence = sequence
        return true
    }

    companion object {
        const val VERSION = 1
        const val MAX_AGE_MS = 30_000L
        val peerCommands = setOf("sync_config", "set_proxy")
        val commands = peerCommands + setOf("start", "stop", "is_start", "start_gnss_mock",
            "stop_gnss_mock", "is_gnss_start", "is_wifi_mock_start", "start_wifi_mock",
            "stop_wifi_mock", "get_location", "get_listener_size", "get_speed", "get_bearing",
            "get_altitude", "set_speed_amp", "set_altitude", "set_speed", "set_bearing",
            "update_location", "move", "put_config", "broadcast_location", "load_library")
    }
}
