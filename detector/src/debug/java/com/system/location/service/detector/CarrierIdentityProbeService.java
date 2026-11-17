package com.system.location.service.detector;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import android.telephony.SignalStrength;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;

import java.util.ArrayList;
import java.util.List;

/** Debug-only implementation: framework reads execute as this service's independent UID. */
public final class CarrierIdentityProbeService extends Service {
    public static final String DESCRIPTOR = "Portal.CarrierIdentityProbe";
    public static final int QUERY = IBinder.FIRST_CALL_TRANSACTION;

    private final Binder binder = new Binder() {
        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
                throws RemoteException {
            if (code == IBinder.INTERFACE_TRANSACTION) {
                if (reply != null) reply.writeString(DESCRIPTOR);
                return true;
            }
            if (code != QUERY) return super.onTransact(code, data, reply, flags);
            data.enforceInterface(DESCRIPTOR);
            CarrierIdentityProbeService.this.enforceCallingPermission(
                    Manifest.permission.DUMP, "Carrier identity probe requires DUMP");
            if (reply == null) throw new IllegalArgumentException("Probe query requires a reply");
            final int forcedSubId = data.dataAvail() >= Integer.BYTES ? data.readInt() : -1;
            final int callerUid = Binder.getCallingUid();
            final long token = Binder.clearCallingIdentity();
            final Bundle result;
            try {
                result = query(callerUid, forcedSubId);
            } finally {
                Binder.restoreCallingIdentity(token);
            }
            reply.writeNoException();
            reply.writeBundle(result);
            return true;
        }
    };

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @SuppressLint("MissingPermission")
    @SuppressWarnings("deprecation")
    private Bundle query(int callerUid, int forcedSubId) {
        final Bundle result = new Bundle();
        result.putString("package", CarrierIdentityProbeService.this.getPackageName());
        result.putInt("uid", android.os.Process.myUid());
        result.putInt("pid", android.os.Process.myPid());
        result.putInt("callerUid", callerUid);
        result.putBoolean("uidDifferentFromCaller", android.os.Process.myUid() != callerUid);
        result.putBoolean("privileged", CarrierIdentityProbeService.this.checkSelfPermission(
                "android.permission.READ_PRIVILEGED_PHONE_STATE") == PackageManager.PERMISSION_GRANTED);
        for (String permission : new String[]{"android.permission.READ_PHONE_STATE",
                "android.permission.READ_PHONE_NUMBERS", "android.permission.READ_SMS"}) {
            result.putBoolean("granted:" + permission, CarrierIdentityProbeService.this.checkSelfPermission(
                    permission) == PackageManager.PERMISSION_GRANTED);
        }
        result.putInt("forcedSubId", forcedSubId);
        final ArrayList<Bundle> rows = new ArrayList<>();
        result.putParcelableArrayList("rows", rows);
        final SubscriptionManager subscriptions = CarrierIdentityProbeService.this.getSystemService(
                SubscriptionManager.class);
        final TelephonyManager telephony = CarrierIdentityProbeService.this.getSystemService(
                TelephonyManager.class);
        try {
            if (subscriptions == null) throw new IllegalStateException("SubscriptionManager unavailable");
            final List<SubscriptionInfo> active = subscriptions.getActiveSubscriptionInfoList();
            if (active == null) {
                result.putString("activeSubscriptionsError", "Framework returned a null active subscription list");
            } else {
                for (SubscriptionInfo info : active) rows.add(queryRow(info, subscriptions, telephony));
            }
        } catch (Exception | LinkageError failure) {
            result.putString("activeSubscriptionsError", error(failure));
        }
        if (forcedSubId >= 0) {
            result.putString("forcedNumber", null);
            result.putString("forcedLine1", null);
            capture(result, "forcedNumber", () -> {
                if (subscriptions == null) throw new IllegalStateException("SubscriptionManager unavailable");
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    return subscriptions.getPhoneNumber(forcedSubId);
                }
                if (telephony == null) throw new IllegalStateException("TelephonyManager unavailable");
                return telephony.createForSubscriptionId(forcedSubId).getLine1Number();
            });
            capture(result, "forcedLine1", () -> {
                if (telephony == null) throw new IllegalStateException("TelephonyManager unavailable");
                return telephony.createForSubscriptionId(forcedSubId).getLine1Number();
            });
        }
        return result;
    }

    @SuppressLint("MissingPermission")
    @SuppressWarnings("deprecation")
    private Bundle queryRow(SubscriptionInfo info, SubscriptionManager subscriptions,
            TelephonyManager telephony) {
        final Bundle row = new Bundle();
        row.putInt("id", -1);
        row.putInt("slot", -1);
        row.putInt("rat", -1);
        row.putInt("signalLevel", -1);
        for (String key : new String[]{"plmn", "carrierName", "simOperator", "networkOperator", "number", "line1"}) {
            row.putString(key, null);
        }
        if (info == null) {
            row.putString("error", "Framework active subscription list contained null");
            return row;
        }
        capture(row, "id", info::getSubscriptionId);
        capture(row, "slot", info::getSimSlotIndex);
        capture(row, "plmn", () -> {
            final String mcc = info.getMccString();
            final String mnc = info.getMncString();
            return mcc == null || mnc == null ? null : mcc + mnc;
        });
        capture(row, "carrierName", () -> {
            final CharSequence name = info.getCarrierName();
            return name == null ? null : name.toString();
        });
        final int subId = row.getInt("id", -1);
        TelephonyManager scoped = null;
        try {
            if (telephony == null) throw new IllegalStateException("TelephonyManager unavailable");
            if (subId < 0) throw new IllegalStateException("Invalid active subscription ID");
            scoped = telephony.createForSubscriptionId(subId);
        } catch (Exception | LinkageError failure) {
            row.putString("telephonyManagerError", error(failure));
        }
        final TelephonyManager perSub = scoped;
        capture(row, "simOperator", () -> require(perSub).getSimOperator());
        capture(row, "networkOperator", () -> require(perSub).getNetworkOperator());
        capture(row, "number", () -> Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                ? subscriptions.getPhoneNumber(subId) : require(perSub).getLine1Number());
        capture(row, "line1", () -> require(perSub).getLine1Number());
        capture(row, "rat", () -> require(perSub).getDataNetworkType());
        capture(row, "signalLevel", () -> {
            final SignalStrength signal = require(perSub).getSignalStrength();
            return signal == null ? -1 : signal.getLevel();
        });
        return row;
    }

    private static TelephonyManager require(TelephonyManager manager) {
        if (manager == null) throw new IllegalStateException("Per-subscription TelephonyManager unavailable");
        return manager;
    }

    private interface QueryValue {
        Object read() throws Exception;
    }

    private static void capture(Bundle output, String key, QueryValue query) {
        try {
            final Object value = query.read();
            if (value instanceof Integer) output.putInt(key, (Integer) value);
            else output.putString(key, value == null ? null : value.toString());
        } catch (Exception | LinkageError failure) {
            output.putString(key + "Error", error(failure));
        }
    }

    private static String error(Throwable failure) {
        final String message = failure.getMessage();
        return failure.getClass().getName() + (message == null ? "" : ": " + message);
    }
}
