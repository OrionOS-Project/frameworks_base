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

package com.android.systemui.statusbar.phone;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ResolveInfo;
import android.content.pm.UserInfo;
import android.os.UserHandle;
import android.os.UserManager;
import android.util.Slog;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Data model and controller for app icons appearing in the navigation bar. The data is stored on
 * disk in SharedPreferences. Each icon has a separate pref entry consisting of a flattened
 * ComponentName.
 */
class NavigationBarAppsModel {
    private final static String TAG = "NavigationBarAppsModel";

    // Default number of apps to load initially.
    private final static int NUM_INITIAL_APPS = 4;

    // Preferences file name.
    private final static String SHARED_PREFERENCES_NAME = "com.android.systemui.navbarapps";

    // Preference name for the version of the other preferences.
    private final static String VERSION_PREF = "version";

    // Current version number for preferences.
    private final static int CURRENT_VERSION = 3;

    // Preference name for the number of app icons.
    private final static String APP_COUNT_PREF = "app_count";

    // Preference name prefix for each app's info. The actual pref has an integer appended to it.
    private final static String APP_PREF_PREFIX = "app_";

    // User serial number prefix for each app's info. The actual pref has an integer appended to it.
    private final static String APP_USER_PREFIX = "app_user_";

    // Character separating current user serial number from the user-specific part of a pref.
    // Example "22|app_user_2" - when logged as user with serial 22, we'll use this pref for the
    // user serial of the third app of the logged-in user.
    private final static char USER_SEPARATOR = '|';

    private final Context mContext;
    private final UserManager mUserManager;
    private final SharedPreferences mPrefs;

    // Apps are represented as an ordered list of app infos.
    private final List<AppInfo> mApps = new ArrayList<AppInfo>();

    // Id of the current user.
    private int mCurrentUserId = -1;

    // Serial number of the current user.
    private long mCurrentUserSerialNumber = -1;

    public NavigationBarAppsModel(Context context) {
        mContext = context;
        mPrefs = mContext.getSharedPreferences(SHARED_PREFERENCES_NAME, Context.MODE_PRIVATE);
        mUserManager = (UserManager) context.getSystemService(Context.USER_SERVICE);

        int version = mPrefs.getInt(VERSION_PREF, -1);
        if (version != CURRENT_VERSION) {
            // Since the data format changed, clean everything.
            SharedPreferences.Editor edit = mPrefs.edit();
            edit.clear();
            edit.putInt(VERSION_PREF, CURRENT_VERSION);
            edit.apply();
        }
    }

    /**
     * Reinitializes the model for a new user.
     */
    public void setCurrentUser(int userId) {
        mCurrentUserId = userId;
        mCurrentUserSerialNumber = mUserManager.getSerialNumberForUser(new UserHandle(userId));

        mApps.clear();

        int appCount = mPrefs.getInt(userPrefixed(APP_COUNT_PREF), -1);
        if (appCount >= 0) {
            loadAppsFromPrefs();
        } else {
            // We switched to this user for the first time ever. This is a good opportunity to clean
            // prefs for users deleted in the past.
            removePrefsForDeletedUsers();

            addDefaultApps();
        }
    }

    /**
     * Removes prefs for users that don't exist on the device.
     */
    private void removePrefsForDeletedUsers() {
        // Build a set of string representations of serial numbers of the device users.
        final List<UserInfo> users = mUserManager.getUsers();
        final int userCount = users.size();

        final Set<String> userSerials = new HashSet<String> ();

        for (int i = 0; i < userCount; ++i) {
            userSerials.add(Long.toString(users.get(i).serialNumber));
        }

        // Walk though all prefs and delete ones which user is not in the string set.
        final Map<String, ?> allPrefs = mPrefs.getAll();
        final SharedPreferences.Editor edit = mPrefs.edit();

        for (Map.Entry<String, ?> pref : allPrefs.entrySet()) {
            final String key = pref.getKey();
            if (key.equals(VERSION_PREF)) continue;

            final int userSeparatorPos = key.indexOf(USER_SEPARATOR);

            if (userSeparatorPos < 0) {
                // Removing anomalous pref with no user.
                edit.remove(key);
                continue;
            }

            final String prefUserSerial = key.substring(0, userSeparatorPos);

            if (!userSerials.contains(prefUserSerial)) {
                // Removes pref for a not existing user.
                edit.remove(key);
                continue;
            }
        }

        edit.apply();
    }

    /** Returns the number of apps. */
    public int getAppCount() {
        return mApps.size();
    }

    /** Returns the app at the given index. */
    public AppInfo getApp(int index) {
        return mApps.get(index);
    }

    /** Adds the app before the given index. */
    public void addApp(int index, AppInfo appInfo) {
        mApps.add(index, appInfo);
    }

    /** Sets the app at the given index. */
    public void setApp(int index, AppInfo appInfo) {
        mApps.set(index, appInfo);
    }

    /** Remove the app at the given index. */
    public AppInfo removeApp(int index) {
        return mApps.remove(index);
    }

    /** Saves the current model to disk. */
    public void savePrefs() {
        SharedPreferences.Editor edit = mPrefs.edit();
        int appCount = mApps.size();
        edit.putInt(userPrefixed(APP_COUNT_PREF), appCount);
        for (int i = 0; i < appCount; i++) {
            final AppInfo appInfo = mApps.get(i);
            String componentNameString = appInfo.getComponentName().flattenToString();
            edit.putString(prefNameForApp(i), componentNameString);
            edit.putLong(prefUserForApp(i), appInfo.getUserSerialNumber());
        }
        // Start an asynchronous disk write.
        edit.apply();
    }

    /** Loads the list of apps from SharedPreferences. */
    private void loadAppsFromPrefs() {
        int appCount = mPrefs.getInt(userPrefixed(APP_COUNT_PREF), -1);
        for (int i = 0; i < appCount; i++) {
            String prefValue = mPrefs.getString(prefNameForApp(i), null);
            if (prefValue == null) {
                Slog.w(TAG, "Couldn't find pref " + prefNameForApp(i));
                // Couldn't find the saved state. Just skip this item.
                continue;
            }
            ComponentName componentName = ComponentName.unflattenFromString(prefValue);
            long userSerialNumber = mPrefs.getLong(prefUserForApp(i), -1);
            if (userSerialNumber == -1) {
                Slog.w(TAG, "Couldn't find pref " + prefUserForApp(i));
                // Couldn't find the saved state. Just skip this item.
                continue;
            }
            mApps.add(new AppInfo(componentName, userSerialNumber));
        }
    }

    /** Adds the first few apps from the owner profile. Used for demo purposes. */
    private void addDefaultApps() {
        // Get a list of all app activities.
        final Intent queryIntent = new Intent(Intent.ACTION_MAIN, null);
        queryIntent.addCategory(Intent.CATEGORY_LAUNCHER);

        final List<ResolveInfo> apps = mContext.getPackageManager().queryIntentActivitiesAsUser(
                queryIntent, 0 /* flags */, mCurrentUserId);
        final int appCount = apps.size();
        for (int i = 0; i < NUM_INITIAL_APPS && i < appCount; i++) {
            ResolveInfo ri = apps.get(i);
            ComponentName componentName = new ComponentName(
                    ri.activityInfo.packageName, ri.activityInfo.name);
            mApps.add(new AppInfo(componentName, mCurrentUserSerialNumber));
        }

        savePrefs();
    }

    /** Returns a pref prefixed with the serial number of the current user. */
    private String userPrefixed(String pref) {
        return Long.toString(mCurrentUserSerialNumber) + USER_SEPARATOR + pref;
    }

    /** Returns the pref name for the app at a given index. */
    private String prefNameForApp(int index) {
        return userPrefixed(APP_PREF_PREFIX + Integer.toString(index));
    }

    /** Returns the pref name for the app's user at a given index. */
    private String prefUserForApp(int index) {
        return userPrefixed(APP_USER_PREFIX + Integer.toString(index));
    }

}
