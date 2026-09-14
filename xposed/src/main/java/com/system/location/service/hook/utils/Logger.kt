package com.system.location.service.hook.utils

import de.robv.android.xposed.XposedBridge

object Logger {
    private val normal = LogRateLimiter()
    private val errors = LogRateLimiter()
    private fun write(level: String, msg: String, throwable: Throwable? = null) {
        if (level == "DEBUG" && (!FakeLoc.enableLog || !FakeLoc.enableDebugLog)) return
        if (level == "INFO" && !FakeLoc.enableLog) return
        val limiter = if (level == "ERROR" || level == "WARN") errors else normal
        if (!limiter.allow("$level:${msg.substringBefore(':').take(120)}", System.nanoTime())) return
        XposedBridge.log("[LocationService][$level] $msg" + (throwable?.let { ": ${it.stackTraceToString()}" } ?: ""))
    }
    fun info(msg: String) = write("INFO", msg)
    fun info(msg: String, throwable: Throwable) = write("INFO", msg, throwable)
    fun debug(msg: String) = write("DEBUG", msg)
    fun debug(msg: String, throwable: Throwable) = write("DEBUG", msg, throwable)
    fun error(msg: String) = write("ERROR", msg)
    fun error(msg: String, throwable: Throwable) = write("ERROR", msg, throwable)
    fun warn(msg: String) = write("WARN", msg)
    fun warn(msg: String, throwable: Throwable) = write("WARN", msg, throwable)
}
