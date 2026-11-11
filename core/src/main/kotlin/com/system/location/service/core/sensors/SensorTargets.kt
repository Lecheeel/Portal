package com.system.location.service.core.sensors

/** Exact package allowlist for the read-only sensor channel; never grants control commands. */
object SensorTargets {
    private val packageName = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")
    fun parse(text: String): Set<String> = text.split(',', '\n', ' ', ';')
        .map(String::trim).filter(String::isNotEmpty).toSet().also(::validate)

    fun validate(targets: Set<String>) {
        require(targets.size <= 128 && targets.all { it.length <= 255 && packageName.matches(it) })
    }

    fun allows(uid: Int, packages: Set<String>, targets: Set<String>): Boolean =
        uid >= 0 && uid % 100000 in 10000..89999 && packages.isNotEmpty() && packages.all { it in targets }
}
