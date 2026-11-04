package com.system.location.service.backend.mock

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.location.LocationServices
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import com.system.location.service.core.location.LocationSample
import com.system.location.service.ext.gmsMockEnabled
import com.system.location.service.ext.experimentalClearMockFlag
import java.util.concurrent.Executor

@SuppressLint("MissingPermission")
class AndroidGmsMockPort(context: Context) : FusedMockPort {
    private val context = context.applicationContext
    private val journal = context.getSharedPreferences("gms_mock_ownership", Context.MODE_PRIVATE)
    private val client by lazy { LocationServices.getFusedLocationProviderClient(this.context) }
    private val flag = MockFlagExperiment()
    private val available by lazy { GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context) == ConnectionResult.SUCCESS }
    private val session = GmsMockSession(this.context.gmsMockEnabled, { available }, object : GmsOwnership {
        override val pending get() = journal.getBoolean("pending", false)
        override fun record(pending: Boolean) {
            check(journal.edit().putBoolean("pending", pending).commit()) { "GMS 所有权记录保存失败" }
        }
    }, { enabled -> TaskOperation(client.setMockMode(enabled)) }, { sample ->
        val location = Location("fused").apply {
            latitude = sample.latitude; longitude = sample.longitude; altitude = sample.altitude
            accuracy = sample.accuracy; speed = sample.speed; bearing = sample.bearing
            time = sample.timeMillis; elapsedRealtimeNanos = sample.elapsedNanos
            verticalAccuracyMeters = sample.accuracy; speedAccuracyMetersPerSecond = 0.1f; bearingAccuracyDegrees = 1f
        }
        if (this.context.experimentalClearMockFlag) flag.clear(location)
        TaskOperation(client.setMockLocation(location))
    }, { android.os.SystemClock.elapsedRealtimeNanos() })

    override val capability get() = session.capability
    override fun diagnostics() = session.diagnostics()
    override suspend fun prepare() = session.prepare()
    override suspend fun start() = session.start()
    override suspend fun publish(sample: LocationSample) = session.publish(sample)
    override suspend fun stop() = session.stop()

    private class TaskOperation(val task: Task<Void>) : GmsOperation {
        override val isComplete get() = task.isComplete
        override val isSuccessful get() = task.isSuccessful
        override val failure get() = task.exception
        override fun then(action: () -> GmsOperation?): GmsOperation = TaskOperation(
            task.continueWithTask(DIRECT) { (action() as? TaskOperation)?.task ?: Tasks.forResult<Void>(null) })
    }
    companion object { private val DIRECT = Executor { it.run() } }
}
