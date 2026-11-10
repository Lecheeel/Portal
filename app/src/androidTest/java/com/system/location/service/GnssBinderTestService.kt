package com.system.location.service

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.os.Process
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Bounded synchronous Binder transport fixture, isolated in the test APK's remote process. */
class GnssBinderTestService : Service() {
    private val entered = CountDownLatch(1)
    private val release = CountDownLatch(1)
    private val accepted = mutableListOf<Int>()
    private val endpoint = object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            data.enforceInterface(DESCRIPTOR)
            when (code) {
                SUBMIT -> {
                    val index = data.readInt()
                    val block = data.readInt() != 0
                    synchronized(accepted) { accepted += index }
                    if (block) {
                        entered.countDown()
                        check(release.await(10, TimeUnit.SECONDS)) { "Test controller did not release transaction" }
                    }
                    reply!!.writeNoException()
                }
                WAIT_ENTERED -> {
                    val observed = entered.await(3, TimeUnit.SECONDS)
                    reply!!.writeNoException()
                    reply.writeInt(if (observed) 1 else 0)
                }
                RELEASE -> { release.countDown(); reply!!.writeNoException() }
                READ -> {
                    reply!!.writeNoException()
                    reply.writeIntArray(synchronized(accepted) { accepted.toIntArray() })
                }
                PID -> { reply!!.writeNoException(); reply.writeInt(Process.myPid()) }
                DIE -> {
                    // One-way control transaction: no caller waits for a reply from a dead process.
                    Process.killProcess(Process.myPid())
                }
                else -> return super.onTransact(code, data, reply, flags)
            }
            return true
        }
    }

    override fun onBind(intent: Intent): IBinder = endpoint
    override fun onDestroy() { release.countDown(); super.onDestroy() }

    companion object {
        const val DESCRIPTOR = "Portal.GnssBinderTest"
        const val SUBMIT = IBinder.FIRST_CALL_TRANSACTION
        const val WAIT_ENTERED = SUBMIT + 1
        const val RELEASE = SUBMIT + 2
        const val READ = SUBMIT + 3
        const val PID = SUBMIT + 4
        const val DIE = SUBMIT + 5
    }
}
