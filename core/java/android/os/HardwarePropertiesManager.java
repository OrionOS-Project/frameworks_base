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
package android.os;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.content.Context;
import android.util.Log;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * The HardwarePropertiesManager class provides a mechanism of accessing hardware state of a
 * device: CPU, GPU and battery temperatures, CPU usage per core, fan speed, etc.
 */
public class HardwarePropertiesManager {

    private static final String TAG = HardwarePropertiesManager.class.getSimpleName();

    private final IHardwarePropertiesManager mService;

    @Retention(RetentionPolicy.SOURCE)
    @IntDef({
        DEVICE_TEMPERATURE_CPU, DEVICE_TEMPERATURE_GPU, DEVICE_TEMPERATURE_BATTERY
    })
    public @interface DeviceTemperatureType {}

    /**
     * Device temperature types. These must match the values in
     * frameworks/native/include/hardwareproperties/HardwarePropertiesManager.h
     */
    /** Temperature of CPUs in Celsius. */
    public static final int DEVICE_TEMPERATURE_CPU = 0;

    /** Temperature of GPUs in Celsius. */
    public static final int DEVICE_TEMPERATURE_GPU = 1;

    /** Temperature of battery in Celsius. */
    public static final int DEVICE_TEMPERATURE_BATTERY = 2;

    /** Calling app context. */
    private final Context mContext;

    /** @hide */
    public HardwarePropertiesManager(Context context, IHardwarePropertiesManager service) {
        mContext = context;
        mService = service;
    }

    /**
     * Return an array of device temperatures in Celsius.
     *
     * @param type type of requested device temperature, one of {@link #DEVICE_TEMPERATURE_CPU},
     * {@link #DEVICE_TEMPERATURE_GPU} or {@link #DEVICE_TEMPERATURE_BATTERY}.
     * @return an array of requested float device temperatures.
     *         Empty if platform doesn't provide the queried temperature.
     *
     * @throws SecurityException if a non profile or device owner tries to call this method.
    */
    public @NonNull float[] getDeviceTemperatures(@DeviceTemperatureType int type) {
        switch (type) {
        case DEVICE_TEMPERATURE_CPU:
        case DEVICE_TEMPERATURE_GPU:
        case DEVICE_TEMPERATURE_BATTERY:
            try {
                return mService.getDeviceTemperatures(mContext.getOpPackageName(), type);
            } catch (RemoteException e) {
                Log.w(TAG, "Could not get device temperatures", e);
                return new float[0];
            }
        default:
            Log.w(TAG, "Unknown device temperature type.");
            return new float[0];
        }
    }

    /**
     * Return an array of CPU usage info for each core.
     *
     * @return an array of {@link android.os.CpuUsageInfo} for each core.
     *         Empty if CPU usage is not supported on this system.
     *
     * @throws SecurityException if a non profile or device owner tries to call this method.
     */
    public @NonNull CpuUsageInfo[] getCpuUsages() {
        try {
            return mService.getCpuUsages(mContext.getOpPackageName());
        } catch (RemoteException e) {
            Log.w(TAG, "Could not get CPU usages", e);
            return new CpuUsageInfo[0];
        }
    }

    /**
     * Return an array of fan speeds in RPM.
     *
     * @return an array of float fan speeds in RPM. Empty if there are no fans or fan speed is not
     * supported on this system.
     *
     * @throws SecurityException if a non profile or device owner tries to call this method.
     */
    public @NonNull float[] getFanSpeeds() {
        try {
            return mService.getFanSpeeds(mContext.getOpPackageName());
        } catch (RemoteException e) {
            Log.w(TAG, "Could not get fan speeds", e);
            return new float[0];
        }
    }
}
