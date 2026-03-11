/*
 * Copyright (C) 2022 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.android.systemui.power.batteryevent.domain;

import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;

public class BatteryEventService extends Service {

    private static final String TAG = "BatteryEventService";
    private static final String ACTION_BATTERY_EVENT = "PNW.batteryStatusChanged";
    private static final String INTELLIGENCE_PACKAGE = "com.google.android.settings.intelligence";
    private static final int WAKELOCK_TIMEOUT_MS = 3000;
    private static final int SIGNIFICANT_LEVEL_DELTA = 5;

    private BroadcastReceiver mBatteryReceiver;
    private PowerManager.WakeLock mWakeLock;
    private int mLastNotifiedLevel = -1;

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "BatteryEventService created");

        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        mWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TAG + "::update");

        registerBatteryReceiver();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "BatteryEventService started, intent=" + intent);
        notifyWidget();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (mBatteryReceiver != null) {
            unregisterReceiver(mBatteryReceiver);
            mBatteryReceiver = null;
        }
        if (mWakeLock != null && mWakeLock.isHeld()) {
            mWakeLock.release();
        }
        mLastNotifiedLevel = -1;
        Log.d(TAG, "BatteryEventService destroyed");
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void registerBatteryReceiver() {
        mBatteryReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                if (action == null) return;
                int level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
                if (level == -1) {
                    BatteryManager bm =
                            (BatteryManager) context.getSystemService(Context.BATTERY_SERVICE);
                    if (bm != null) {
                        level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
                    }
                }

                if (level < 0) {
                    level = (mLastNotifiedLevel != -1) ? mLastNotifiedLevel : 0;
                }

                boolean isStateChange = !Intent.ACTION_BATTERY_CHANGED.equals(action);
                boolean isSignificantLevelChange =
                        Math.abs(level - mLastNotifiedLevel) >= SIGNIFICANT_LEVEL_DELTA;

                if (!(isStateChange || isSignificantLevelChange)) {
                    return;
                }

                mLastNotifiedLevel = level;

                Log.d(TAG, "Battery event: action=" + action + " level=" + level + "%");
                if (mWakeLock != null && !mWakeLock.isHeld()) {
                    mWakeLock.acquire(WAKELOCK_TIMEOUT_MS);
                }

                try {
                    notifyWidget();
                } finally {
                    if (mWakeLock != null && mWakeLock.isHeld()) {
                        mWakeLock.release();
                    }
                }
            }
        };

        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_BATTERY_CHANGED);
        filter.addAction("android.intent.action.BATTERY_LEVEL_CHANGED");
        filter.addAction(Intent.ACTION_POWER_CONNECTED);
        filter.addAction(Intent.ACTION_POWER_DISCONNECTED);
        filter.addAction(Intent.ACTION_BATTERY_LOW);
        filter.addAction(Intent.ACTION_BATTERY_OKAY);

        registerReceiver(mBatteryReceiver, filter, Context.RECEIVER_NOT_EXPORTED);

        Log.d(TAG, "Battery receiver registered");
    }

    private void notifyWidget() {
        try {
            Intent notify = new Intent(ACTION_BATTERY_EVENT);
            notify.setPackage(INTELLIGENCE_PACKAGE);
            sendBroadcast(notify);
            Log.d(TAG, "Notified Intelligence widget");
        } catch (Exception e) {
            Log.e(TAG, "Failed to notify widget: " + e);
        }
    }
}
