/*
 * Copyright (C) 2016 The Android Open Source Project
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
package android.content.pm;

import android.content.pm.ParceledListSlice;
import android.content.pm.ShortcutInfo;

/**
 * {@hide}
 */
interface IShortcutService {

    boolean setDynamicShortcuts(String packageName, in ParceledListSlice shortcutInfoList,
            int userId);

    ParceledListSlice getDynamicShortcuts(String packageName, int userId);

    boolean addDynamicShortcut(String packageName, in ShortcutInfo shortcutInfo, int userId);

    void deleteDynamicShortcut(String packageName, in String shortcutId, int userId);

    void deleteAllDynamicShortcuts(String packageName, int userId);

    ParceledListSlice getPinnedShortcuts(String packageName, int userId);

    boolean updateShortcuts(String packageName, in ParceledListSlice shortcuts, int userId);

    int getMaxDynamicShortcutCount(String packageName, int userId);

    int getRemainingCallCount(String packageName, int userId);

    long getRateLimitResetTime(String packageName, int userId);

    int getIconMaxDimensions(String packageName, int userId);

    void resetThrottling(); // system only API for developer opsions
}