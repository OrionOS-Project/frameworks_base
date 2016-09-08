/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package com.android.server.usb;

import android.annotation.NonNull;
import android.annotation.UserIdInt;
import android.content.Context;
import android.content.Intent;
import android.hardware.usb.UsbAccessory;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.UserHandle;
import android.util.Slog;
import android.util.SparseArray;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.util.IndentingPrintWriter;

/**
 * Maintains all {@link UsbUserSettingsManager} for all users.
 */
class UsbSettingsManager {
    private static final String LOG_TAG = UsbSettingsManager.class.getSimpleName();
    private static final boolean DEBUG = false;

    /** Context to be used by this module */
    private final @NonNull Context mContext;

    /** Map from user id to {@link UsbUserSettingsManager} for the user */
    @GuardedBy("mSettingsByUser")
    private final SparseArray<UsbUserSettingsManager> mSettingsByUser = new SparseArray<>();

    public UsbSettingsManager(@NonNull Context context) {
        mContext = context;
    }

    /**
     * Get the {@link UsbUserSettingsManager} for a user.
     *
     * @param userId The id of the user
     *
     * @return The settings for the user
     */
    @NonNull UsbUserSettingsManager getSettingsForUser(@UserIdInt int userId) {
        synchronized (mSettingsByUser) {
            UsbUserSettingsManager settings = mSettingsByUser.get(userId);
            if (settings == null) {
                settings = new UsbUserSettingsManager(mContext, new UserHandle(userId));
                mSettingsByUser.put(userId, settings);
            }
            return settings;
        }
    }

    /**
     * Remove the settings for a user.
     *
     * @param userIdToRemove The user o remove
     */
    void remove(@UserIdInt int userIdToRemove) {
        synchronized (mSettingsByUser) {
            mSettingsByUser.remove(userIdToRemove);
        }
    }

    /**
     * Dump all settings of all users.
     *
     * @param pw The writer to dump to
     */
    void dump(@NonNull IndentingPrintWriter pw) {
        synchronized (mSettingsByUser) {
            for (int i = 0; i < mSettingsByUser.size(); i++) {
                final int userId = mSettingsByUser.keyAt(i);
                final UsbUserSettingsManager settings = mSettingsByUser.valueAt(i);
                pw.println("Settings for user " + userId + ":");
                pw.increaseIndent();
                try {
                    settings.dump(pw);
                } finally {
                    pw.decreaseIndent();
                }
            }
        }
    }

    /**
     * Remove temporary access permission and broadcast that a device was removed.
     *
     * @param device The device that is removed
     */
    void usbDeviceRemoved(@NonNull UsbDevice device) {
        synchronized (mSettingsByUser) {
            for (int i = 0; i < mSettingsByUser.size(); i++) {
                // clear temporary permissions for the device
                mSettingsByUser.valueAt(i).removeDevicePermissions(device);
            }
        }

        Intent intent = new Intent(UsbManager.ACTION_USB_DEVICE_DETACHED);
        intent.putExtra(UsbManager.EXTRA_DEVICE, device);

        if (DEBUG) {
            Slog.d(LOG_TAG, "usbDeviceRemoved, sending " + intent);
        }
        mContext.sendBroadcastAsUser(intent, UserHandle.ALL);
    }

    /**
     * Remove temporary access permission and broadcast that a accessory was removed.
     *
     * @param accessory The accessory that is removed
     */
    void usbAccessoryRemoved(@NonNull UsbAccessory accessory) {
        synchronized (mSettingsByUser) {
            for (int i = 0; i < mSettingsByUser.size(); i++) {
                // clear temporary permissions for the accessory
                mSettingsByUser.valueAt(i).removeAccessoryPermissions(accessory);
            }
        }

        Intent intent = new Intent(UsbManager.ACTION_USB_ACCESSORY_DETACHED);
        intent.putExtra(UsbManager.EXTRA_ACCESSORY, accessory);
        mContext.sendBroadcastAsUser(intent, UserHandle.ALL);
    }
}
