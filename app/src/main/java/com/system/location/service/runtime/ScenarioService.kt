package com.system.location.service.runtime

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.system.location.service.MainActivity
import com.system.location.service.R
import kotlinx.coroutines.*

/** The service owns the tick loop and wake lock, never a Fragment or ViewModel. */
class ScenarioService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var wakeLock: PowerManager.WakeLock? = null
    override fun onCreate() {
        super.onCreate()
        val notifications = getSystemService(NotificationManager::class.java)
        notifications.createNotificationChannel(NotificationChannel(CHANNEL, "场景播放", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, ScenarioService::class.java).setAction(STOP), PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.baseline_play_24).setContentTitle("定位场景运行中")
            .setContentText("打开应用查看状态；停止后清理定位后端")
            .setContentIntent(open).addAction(R.drawable.baseline_stop_24, "停止", stop).setOngoing(true).build()
        try {
            ServiceCompat.startForeground(this, 201, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
            wakeLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LocationService:Playback").apply { acquire() }
            ScenarioRuntime.attached(this)
            scope.launch {
                while (isActive) {
                    delay(ScenarioRuntime.controller.intervalMs)
                    ScenarioRuntime.tick(this@ScenarioService)
                }
            }
        } catch (error: Exception) {
            ScenarioRuntime.attachmentFailed(error)
            stopSelf()
        }
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) ScenarioRuntime.stop()
        // A dead process is reported as interrupted, never silently restored as running.
        return START_NOT_STICKY
    }
    internal fun finishPlayback() { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
    override fun onDestroy() {
        ScenarioRuntime.detached(this)
        scope.cancel()
        wakeLock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null
    companion object {
        private const val CHANNEL = "scenario_playback"
        private const val STOP = "com.system.location.service.STOP_SCENARIO"
    }
}
