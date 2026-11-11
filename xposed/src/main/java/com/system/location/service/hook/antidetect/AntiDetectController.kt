package com.system.location.service.hook.antidetect

import android.app.Application
import android.content.Context
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import com.system.location.service.hook.runtime.HookRuntime
import com.system.location.service.hook.utils.FakeLoc
import com.system.location.service.jni.AntiDetect

/**
 * Polls the controller for the two experimental anti-detection flags.
 *
 * Target application processes cannot read the controller's preferences directly; the existing
 * `fused_ext` command channel is the only allowed transport. The poll runs on the application's
 * main looper and creates no thread. A missing target selection simply leaves both features off.
 */
object AntiDetectController {
    private const val COMMAND = "get_anti_detect_state"
    private const val INTERVAL_MS = 1_000L
    @Volatile private var started = false

    fun start() {
        if (started) return
        val context = currentApplication()
        if (context == null) {
            // Package-ready can arrive before ActivityThread exposes the Application on some ROMs.
            Handler(Looper.getMainLooper()).postDelayed({ start() }, 500L)
            return
        }
        started = true
        val handler = Handler(Looper.getMainLooper())
        val poll = object : Runnable {
            override fun run() {
                pollOnce(context)
                handler.postDelayed(this, INTERVAL_MS)
            }
        }
        handler.post(poll)
    }

    private fun pollOnce(context: Context) {
        val manager = context.getSystemService(LocationManager::class.java) ?: return
        val reply = Bundle()
        val accepted = runCatching {
            manager.sendExtraCommand("fused_ext", COMMAND, reply)
        }.getOrDefault(false)
        if (!accepted) return
        val stackTraceClean = reply.getBoolean("stack_trace", false)
        if (FakeLoc.stackTraceCleanEnabled != stackTraceClean) {
            FakeLoc.stackTraceCleanEnabled = stackTraceClean
        }
        AntiDetect.setEnabled(reply.getBoolean("anti_detect", false), context)
    }

    private fun currentApplication(): Application? = runCatching {
        val activityThread = Class.forName("android.app.ActivityThread")
        val thread = activityThread.getMethod("currentActivityThread").invoke(null)
        activityThread.getMethod("getApplication").invoke(thread) as? Application
    }.onFailure { HookRuntime.log(it) }.getOrNull()
}
