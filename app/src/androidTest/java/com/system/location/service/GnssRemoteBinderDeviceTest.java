package com.system.location.service;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.os.IInterface;
import android.os.Parcel;
import android.os.Process;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.system.location.service.hook.hooks.gnss.GnssCallbackRegistry;
import com.system.location.service.hook.runtime.CallbackSubscription;
import com.system.location.service.hook.runtime.DeliveryWorker;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import kotlin.Unit;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Java accesses the production registry's JVM API without widening its Kotlin visibility. */
@RunWith(AndroidJUnit4.class)
public class GnssRemoteBinderDeviceTest {
    @Test public void unregisterDuringRemoteTransactionInvalidatesTheOldBatch() throws Exception {
        try (Remote remote = new Remote()) {
            GnssCallbackRegistry registry = new GnssCallbackRegistry();
            IInterface listener = () -> remote.binder;
            assertTrue(registry.register(listener, "old", Process.myUid()));
            AtomicReference<Throwable> error = new AtomicReference<>();
            Thread sender = new Thread(() -> {
                try { deliver(registry, listener, remote, true); }
                catch (Throwable failure) { error.set(failure); }
            });
            try {
                sender.start();
                try {
                    assertEquals(1, remote.integer(GnssBinderTestService.WAIT_ENTERED));
                    // This must finish while the actual remote Binder transaction is still blocked.
                    registry.unregister(listener);
                    assertTrue(registry.register(listener, "replacement", Process.myUid()));
                } finally {
                    try { remote.control(GnssBinderTestService.RELEASE); }
                    finally { sender.join(4000); }
                }
                assertFalse(sender.isAlive());
                if (error.get() != null) throw new AssertionError(error.get());
                assertArrayEquals(new int[] {0}, remote.accepted());
                assertNull(registry.subscriptions().get(0).getPendingDeliveryKey());
                deliver(registry, listener, remote, false);
                assertArrayEquals(new int[] {0, 0, 1, 2}, remote.accepted());
            } finally { registry.clear(); }
        }
    }

    @Test public void pauseDuringRemoteTransactionTransfersOnlyItsUnsentSuffix() throws Exception {
        try (Remote remote = new Remote()) {
            GnssCallbackRegistry registry = new GnssCallbackRegistry();
            GnssCallbackRegistry successor = new GnssCallbackRegistry();
            IInterface listener = () -> remote.binder;
            assertTrue(registry.register(listener, "receiver", Process.myUid()));
            AtomicReference<Exception> error = new AtomicReference<>();
            DeliveryWorker worker = new DeliveryWorker("RemoteBinderTest", () -> 60000L, false,
                () -> { deliver(registry, listener, remote, true); return Unit.INSTANCE; },
                failure -> { error.set(failure); return Unit.INSTANCE; });
            try {
                worker.start();
                assertEquals(1, remote.integer(GnssBinderTestService.WAIT_ENTERED));
                assertFalse("Binder is still in flight", worker.stop(20));
                remote.control(GnssBinderTestService.RELEASE);
                assertTrue(worker.stop(4000));
                assertNull(error.get());
                assertArrayEquals(new int[] {0}, remote.accepted());
                CallbackSubscription saved = registry.subscriptions().get(0);
                assertEquals(1, saved.getNextDeliveryIndex());
                registry.clear();
                assertTrue(successor.restore(saved));
                deliver(successor, listener, remote, false);
                deliver(successor, listener, remote, false);
                assertArrayEquals(new int[] {0, 1, 2}, remote.accepted());
            } finally {
                try { remote.control(GnssBinderTestService.RELEASE); }
                finally {
                    try { worker.stop(4000); }
                    finally { registry.clear(); successor.clear(); }
                }
            }
        }
    }

    @Test public void remoteProcessDeathRemovesTheRealBinderRegistration() throws Exception {
        try (Remote remote = new Remote()) {
            GnssCallbackRegistry registry = new GnssCallbackRegistry();
            IInterface listener = () -> remote.binder;
            CountDownLatch died = new CountDownLatch(1);
            IBinder.DeathRecipient death = died::countDown;
            remote.binder.linkToDeath(death, 0);
            try {
                assertTrue(registry.register(listener, "receiver", Process.myUid()));
                Parcel data = Parcel.obtain();
                try {
                    data.writeInterfaceToken(GnssBinderTestService.DESCRIPTOR);
                    assertTrue(remote.binder.transact(GnssBinderTestService.DIE, data, null, IBinder.FLAG_ONEWAY));
                } finally { data.recycle(); }
                assertTrue("Real remote death notification missing", died.await(4, TimeUnit.SECONDS));
                long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
                while (!registry.subscriptions().isEmpty() && System.nanoTime() < until) Thread.sleep(10);
                assertTrue(registry.subscriptions().isEmpty());
                assertFalse(registry.register(listener, "dead", Process.myUid()));
            } finally {
                registry.clear();
                remote.binder.unlinkToDeath(death, 0);
            }
        }
    }

    private static void deliver(GnssCallbackRegistry registry, IInterface listener, Remote remote, boolean block) {
        registry.deliverBatch(listener, "exact batch", 3, () -> true, index -> {
            remote.submit(index, block);
            return Unit.INSTANCE;
        });
    }

    private static final class Remote implements AutoCloseable {
        final Context context = InstrumentationRegistry.getInstrumentation().getContext();
        final CountDownLatch connected = new CountDownLatch(1);
        IBinder binder;
        boolean bound;
        final ServiceConnection connection = new ServiceConnection() {
            @Override public void onServiceConnected(ComponentName name, IBinder service) {
                binder = service;
                connected.countDown();
            }
            @Override public void onServiceDisconnected(ComponentName name) { }
        };
        Remote() throws Exception {
            Intent intent = new Intent().setComponent(new ComponentName(context.getPackageName(),
                GnssBinderTestService.class.getName()));
            bound = context.bindService(intent, connection, Context.BIND_AUTO_CREATE);
            try {
                assertTrue("Cannot bind test APK service", bound);
                assertTrue("Remote service did not connect", connected.await(5, TimeUnit.SECONDS));
                assertNotEquals("Fixture must cross a process boundary", Process.myPid(), integer(GnssBinderTestService.PID));
            } catch (Throwable error) { close(); throw error; }
        }
        interface Read<T> { T read(Parcel parcel); }
        <T> T transact(int code, java.util.function.Consumer<Parcel> fill, Read<T> read) {
            Parcel data = Parcel.obtain();
            Parcel reply = Parcel.obtain();
            try {
                data.writeInterfaceToken(GnssBinderTestService.DESCRIPTOR);
                fill.accept(data);
                assertTrue(binder.transact(code, data, reply, 0));
                reply.readException();
                return read.read(reply);
            } catch (android.os.RemoteException error) { throw new AssertionError(error); }
            finally { data.recycle(); reply.recycle(); }
        }
        void submit(int index, boolean block) {
            transact(GnssBinderTestService.SUBMIT, p -> { p.writeInt(index); p.writeInt(block ? 1 : 0); }, p -> null);
        }
        int integer(int code) { return transact(code, p -> {}, Parcel::readInt); }
        int[] accepted() { return transact(GnssBinderTestService.READ, p -> {}, Parcel::createIntArray); }
        void control(int code) { transact(code, p -> {}, p -> null); }
        @Override public void close() { if (bound) { context.unbindService(connection); bound = false; } }
    }
}
