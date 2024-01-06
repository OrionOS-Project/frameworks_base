/*
 * Copyright (C) 2017 The Android Open Source Project
 * Copyright (C) 2022 FlamingoOS Project
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

package com.android.internal.util.orion;

import android.app.ActivityManager;
import android.app.role.RoleManager;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.os.UserHandle;

import com.android.internal.util.CollectionUtils;

/**
 * Some custom utilities
 * @hide
 */
public class orionUtils {
    public static boolean isPackageInstalled(Context context, String pkg, boolean ignoreState) {
        if (pkg != null) {
            try {
                final PackageInfo pi = context.getPackageManager().getPackageInfo(pkg, 0);
                return ignoreState || pi.applicationInfo.enabled;
            } catch (PackageManager.NameNotFoundException e) {
                // Do nothing
            }
        }
        return false;
    }

    public static boolean isPackageInstalled(Context context, String pkg) {
        return isPackageInstalled(context, pkg, true);
    }

    public static boolean isPackageAvailable(Context context, String packageName) {
        final PackageManager pm = context.getPackageManager();
        try {
            pm.getPackageInfo(packageName, PackageManager.GET_ACTIVITIES);
            int enabled = pm.getApplicationEnabledSetting(packageName);
            return enabled != PackageManager.COMPONENT_ENABLED_STATE_DISABLED &&
                enabled != PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER;
        } catch (NameNotFoundException e) {
            return false;
        }
    }

    public static String getDefaultLauncher(Context context) {
        final RoleManager roleManager = context.getSystemService(RoleManager.class);
        final String packageName = CollectionUtils.firstOrNull(
                roleManager.getRoleHolders(RoleManager.ROLE_HOME));
        return packageName != null ? packageName : "";
    }

    public static void forceStopDefaultLauncher(Context context) {
        final ActivityManager activityManager = context.getSystemService(ActivityManager.class);
        try {
            activityManager.forceStopPackageAsUser(getDefaultLauncher(context), UserHandle.USER_CURRENT);
        } catch (Exception ignored) {}
    }
}
