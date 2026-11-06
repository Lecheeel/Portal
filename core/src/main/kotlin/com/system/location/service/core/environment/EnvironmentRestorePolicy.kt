package com.system.location.service.core.environment

/** P5-06: restore policy for the environment source after loading an active config. */
object EnvironmentRestorePolicy {
    fun sourceMode(saved: EnvironmentSourceType, replayJson: String?): String =
        if (!replayJson.isNullOrBlank() && saved != EnvironmentSourceType.PROCEDURAL) "REPLAY" else "PROCEDURAL"
}
