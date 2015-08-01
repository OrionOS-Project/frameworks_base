/*
 * Copyright (C) 2015 The Android Open Source Project
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

package com.android.server;

import android.app.KeyguardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Resources;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.PowerManager;
import android.os.Vibrator;
import android.os.PowerManager.WakeLock;
import android.os.SystemProperties;
import android.provider.MediaStore;
import android.provider.Settings;
import android.util.Slog;

/**
 * The service that listens for gestures detected in sensor firmware and starts the intent
 * accordingly.
 * <p>For now, only camera launch gesture is supported, and in the future, more gestures can be
 * added.</p>
 * @hide
 */
class GestureLauncherService extends SystemService {
    private static final boolean DBG = false;
    private static final String TAG = "GestureLauncherService";

    /** The listener that receives the gesture event. */
    private final GestureEventListener mGestureListener = new GestureEventListener();

    private Sensor mCameraLaunchSensor;
    private Vibrator mVibrator;
    private KeyguardManager mKeyGuard;
    private Context mContext;

    /** The wake lock held when a gesture is detected. */
    private WakeLock mWakeLock;

    public GestureLauncherService(Context context) {
        super(context);
        mContext = context;
    }

    public void onStart() {
        // Nothing to publish.
    }

    public void onBootPhase(int phase) {
        if (phase == PHASE_THIRD_PARTY_APPS_CAN_START) {
            Resources resources = mContext.getResources();
            if (!isGestureLauncherEnabled(resources)) {
                if (DBG) Slog.d(TAG, "Gesture launcher is disabled in system properties.");
                return;
            }

            mVibrator = (Vibrator) mContext.getSystemService(Context.VIBRATOR_SERVICE);
            mKeyGuard = (KeyguardManager) mContext.getSystemService(Context.KEYGUARD_SERVICE);
            PowerManager powerManager = (PowerManager) mContext.getSystemService(
                    Context.POWER_SERVICE);
            mWakeLock = powerManager.newWakeLock(
                    PowerManager.SCREEN_BRIGHT_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP,
                    "GestureLauncherService");
            if (isCameraLaunchEnabled(resources)) {
                registerCameraLaunchGesture(resources);
            }
        }
    }

    /**
     * Registers for the camera launch gesture.
     */
    private void registerCameraLaunchGesture(Resources resources) {
        SensorManager sensorManager = (SensorManager) mContext.getSystemService(
                Context.SENSOR_SERVICE);
        int cameraLaunchGestureId = resources.getInteger(
                com.android.internal.R.integer.config_cameraLaunchGestureSensorType);
        if (cameraLaunchGestureId != -1) {
            boolean registered = false;
            String sensorName = resources.getString(
                com.android.internal.R.string.config_cameraLaunchGestureSensorStringType);
            mCameraLaunchSensor = sensorManager.getDefaultSensor(
                    cameraLaunchGestureId,
                    true /*wakeUp*/);

            // Compare the camera gesture string type to that in the resource file to make
            // sure we are registering the correct sensor. This is redundant check, it
            // makes the code more robust.
            if (mCameraLaunchSensor != null) {
                if (sensorName.equals(mCameraLaunchSensor.getStringType())) {
                    registered = sensorManager.registerListener(mGestureListener,
                            mCameraLaunchSensor, 0);
                } else {
                    String message = String.format("Wrong configuration. Sensor type and sensor "
                            + "string type don't match: %s in resources, %s in the sensor.",
                            sensorName, mCameraLaunchSensor.getStringType());
                    throw new RuntimeException(message);
                }
            }
            if (DBG) Slog.d(TAG, "Camera launch sensor registered: " + registered);
        } else {
            if (DBG) Slog.d(TAG, "Camera launch sensor is not specified.");
        }
    }

    /**
     * Whether to enable the camera launch gesture.
     */
    public static boolean isCameraLaunchEnabled(Resources resources) {
        boolean configSet = resources.getInteger(
                com.android.internal.R.integer.config_cameraLaunchGestureSensorType) != -1;
        return configSet &&
                !SystemProperties.getBoolean("gesture.disable_camera_launch", false);
    }

    /**
     * Whether GestureLauncherService should be enabled according to system properties.
     */
    public static boolean isGestureLauncherEnabled(Resources resources) {
        // For now, the only supported gesture is camera launch gesture, so whether to enable this
        // service equals to isCameraLaunchEnabled();
        return isCameraLaunchEnabled(resources);
    }

    private final class GestureEventListener implements SensorEventListener {
        @Override
        public void onSensorChanged(SensorEvent event) {
            if (event.sensor == mCameraLaunchSensor) {
                handleCameraLaunchGesture();
                return;
            }
        }

        private void handleCameraLaunchGesture() {
            if (DBG) Slog.d(TAG, "Received a camera launch event.");
            boolean userSetupComplete = Settings.Secure.getInt(mContext.getContentResolver(),
                    Settings.Secure.USER_SETUP_COMPLETE, 0) != 0;
            if (!userSetupComplete) {
                if (DBG) Slog.d(TAG, String.format(
                        "userSetupComplete = %s, ignoring camera launch gesture.",
                        userSetupComplete));
                return;
            }
            if (DBG) Slog.d(TAG, String.format(
                    "userSetupComplete = %s, performing camera launch gesture.",
                    userSetupComplete));
            boolean locked = mKeyGuard != null && mKeyGuard.inKeyguardRestrictedInputMode();
            String action = locked
                    ? MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA_SECURE
                    : MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA;
            Intent intent = new Intent(action);
            PackageManager pm = mContext.getPackageManager();
            ResolveInfo componentInfo = pm.resolveActivity(intent,
                PackageManager.MATCH_DEFAULT_ONLY);
            if (componentInfo == null) {
                if (DBG) Slog.d(TAG, "Couldn't find an app to process the camera intent.");
                return;
            }

            if (mVibrator != null && mVibrator.hasVibrator()) {
                mVibrator.vibrate(1000L);
            }
            // Turn on the screen before the camera launches.
            mWakeLock.acquire(500L);
            intent.setComponent(new ComponentName(componentInfo.activityInfo.packageName,
                    componentInfo.activityInfo.name));
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            mContext.startActivity(intent);
            mWakeLock.release();
        }

        @Override
        public void onAccuracyChanged(Sensor sensor, int accuracy) {
            // Ignored.
        }
    }
}
