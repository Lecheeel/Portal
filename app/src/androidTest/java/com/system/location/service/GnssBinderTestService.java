package com.system.location.service;

import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Process;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Test APK remote processes do not inherit the target APK's Kotlin runtime. */
public final class GnssBinderTestService extends Service {
    public static final String DESCRIPTOR = "Portal.GnssBinderTest";
    public static final int SUBMIT = IBinder.FIRST_CALL_TRANSACTION;
    public static final int WAIT_ENTERED = SUBMIT + 1;
    public static final int RELEASE = SUBMIT + 2;
    public static final int READ = SUBMIT + 3;
    public static final int PID = SUBMIT + 4;
    public static final int DIE = SUBMIT + 5;
    private final CountDownLatch entered = new CountDownLatch(1);
    private final CountDownLatch release = new CountDownLatch(1);
    private final List<Integer> accepted = new ArrayList<>();
    private final IBinder endpoint = new Binder() {
        @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
                throws android.os.RemoteException {
            if (code < SUBMIT || code > DIE) return super.onTransact(code, data, reply, flags);
            data.enforceInterface(DESCRIPTOR);
            switch (code) {
                case SUBMIT:
                    int index = data.readInt();
                    boolean block = data.readInt() != 0;
                    synchronized (accepted) { accepted.add(index); }
                    if (block) {
                        entered.countDown();
                        if (!await(release, 10)) throw new IllegalStateException("Transaction was not released");
                    }
                    reply.writeNoException();
                    break;
                case WAIT_ENTERED:
                    boolean observed = await(entered, 3);
                    reply.writeNoException();
                    reply.writeInt(observed ? 1 : 0);
                    break;
                case RELEASE:
                    release.countDown();
                    reply.writeNoException();
                    break;
                case READ:
                    synchronized (accepted) {
                        int[] values = new int[accepted.size()];
                        for (int i = 0; i < values.length; i++) values[i] = accepted.get(i);
                        reply.writeNoException();
                        reply.writeIntArray(values);
                    }
                    break;
                case PID:
                    reply.writeNoException();
                    reply.writeInt(Process.myPid());
                    break;
                case DIE:
                    Process.killProcess(Process.myPid()); // one-way; no reply is awaited
                    break;
            }
            return true;
        }
    };
    private static boolean await(CountDownLatch latch, int seconds) {
        try { return latch.await(seconds, TimeUnit.SECONDS); }
        catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IllegalStateException(error); }
    }
    @Override public IBinder onBind(Intent intent) { return endpoint; }
    @Override public void onDestroy() { release.countDown(); super.onDestroy(); }
}
