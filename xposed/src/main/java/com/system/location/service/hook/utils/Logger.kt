package com.system.location.service.hook.utils

import de.robv.android.xposed.XposedBridge

object Logger {
    private fun isEnableLog(): Boolean {
        return FakeLoc.enableLog
    }

    fun info(msg: String) {
        if (isEnableLog()) {
            XposedBridge.log("[LocationService] $msg")
        }
    }

    fun info(msg: String, throwable: Throwable) {
        if (isEnableLog()) {
            XposedBridge.log("[LocationService] $msg: ${throwable.stackTraceToString()}")
        }
    }

    fun debug(msg: String) {
        XposedBridge.log("[LocationService][DEBUG] $msg")
    }

    fun debug(msg: String, throwable: Throwable) {
        XposedBridge.log("[LocationService][DEBUG] $msg: ${throwable.stackTraceToString()}")
    }

    fun error(msg: String) {
        XposedBridge.log("[LocationService][ERROR] $msg")
    }

    fun error(msg: String, throwable: Throwable) {
        XposedBridge.log("[LocationService][ERROR] $msg: ${throwable.stackTraceToString()}")
    }

    fun warn(msg: String) {
        XposedBridge.log("[LocationService][WARN] $msg")
    }

    fun warn(msg: String, throwable: Throwable) {
        XposedBridge.log("[LocationService][WARN] $msg: ${throwable.stackTraceToString()}")
    }
}
