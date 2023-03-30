package com.esr.esense_recorder;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.Nullable;

public class SensorRecordService extends Service {
    private static final String TAG = SensorRecordService.class.getSimpleName();;

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "sensor service onCreate()");

    }

    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "sensor service onStartCommand()");
        /*
        if (eSenseController.getState() == ESenseConnectionState.CONNECTED ){
            // Check config
            if (eSenseController.getESenseConfig() == null) {
                pendingStartLog = true;
                eSenseController.readESenseConfig(); // also done in IMUconfig button
                Log.d(TAG, "read config from start record button");
            }
            if (!eSenseController.areSensorNotificationsActive()) { // this statement is executed first time startRecordButton is pressed
                pendingStartLog = true;
                Log.d(TAG, "start sensors from start record button");
                startSensors(); // also done in IMUmonitor button
            }

            Log.d(TAG, "start log from start record button");
            startLog();

        } else {
            showToast(getString(R.string.toast_message_no_device_connected));
        }
         */
        return super.onStartCommand(intent, flags, startId);
    }
}
