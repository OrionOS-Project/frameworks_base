/*
 * Copyright (C) 2012 The Android Open Source Project
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

import static android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DEFAULT;
import static android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
import static android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED;
import static android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER;
import static android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED;
import static android.content.pm.PackageManager.MATCH_DISABLED_COMPONENTS;
import static android.content.pm.PackageManager.MATCH_DISABLED_UNTIL_USED_COMPONENTS;
import static android.content.pm.PackageManager.MATCH_ENCRYPTION_AWARE;
import static android.content.pm.PackageManager.MATCH_ENCRYPTION_UNAWARE;
import static android.content.pm.PackageManager.MATCH_SYSTEM_ONLY;

import android.util.ArraySet;

import com.android.internal.util.ArrayUtils;

/**
 * Per-user state information about a package.
 * @hide
 */
public class PackageUserState {
    public boolean stopped;
    public boolean notLaunched;
    public boolean installed;
    public boolean hidden; // Is the app restricted by owner / admin
    public boolean suspended;
    public int enabled;
    public boolean blockUninstall;

    public String lastDisableAppCaller;

    public ArraySet<String> disabledComponents;
    public ArraySet<String> enabledComponents;

    public int domainVerificationStatus;
    public int appLinkGeneration;

    public PackageUserState() {
        installed = true;
        hidden = false;
        suspended = false;
        enabled = COMPONENT_ENABLED_STATE_DEFAULT;
        domainVerificationStatus =
                PackageManager.INTENT_FILTER_DOMAIN_VERIFICATION_STATUS_UNDEFINED;
    }

    public PackageUserState(PackageUserState o) {
        installed = o.installed;
        stopped = o.stopped;
        notLaunched = o.notLaunched;
        enabled = o.enabled;
        hidden = o.hidden;
        suspended = o.suspended;
        lastDisableAppCaller = o.lastDisableAppCaller;
        disabledComponents = ArrayUtils.cloneOrNull(o.disabledComponents);
        enabledComponents = ArrayUtils.cloneOrNull(o.enabledComponents);
        blockUninstall = o.blockUninstall;
        domainVerificationStatus = o.domainVerificationStatus;
        appLinkGeneration = o.appLinkGeneration;
    }

    /**
     * Test if this package is installed.
     */
    public boolean isInstalled(int flags) {
        return (this.installed && !this.hidden)
                || (flags & PackageManager.MATCH_UNINSTALLED_PACKAGES) != 0;
    }

    /**
     * Test if the given component is considered installed, enabled and a match
     * for the given flags.
     */
    public boolean isMatch(ComponentInfo componentInfo, int flags) {
        if (!isInstalled(flags)) return false;
        if (!isEnabled(componentInfo, flags)) return false;

        if ((flags & MATCH_SYSTEM_ONLY) != 0) {
            if (!componentInfo.applicationInfo.isSystemApp()) {
                return false;
            }
        }

        final boolean matchesUnaware = ((flags & MATCH_ENCRYPTION_UNAWARE) != 0)
                && !componentInfo.encryptionAware;
        final boolean matchesAware = ((flags & MATCH_ENCRYPTION_AWARE) != 0)
                && componentInfo.encryptionAware;
        return matchesUnaware || matchesAware;
    }

    /**
     * Test if the given component is considered enabled.
     */
    public boolean isEnabled(ComponentInfo componentInfo, int flags) {
        if ((flags & MATCH_DISABLED_COMPONENTS) != 0) {
            return true;
        }

        // First check if the overall package is disabled; if the package is
        // enabled then fall through to check specific component
        switch (this.enabled) {
            case COMPONENT_ENABLED_STATE_DISABLED:
            case COMPONENT_ENABLED_STATE_DISABLED_USER:
                return false;
            case COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED:
                if ((flags & MATCH_DISABLED_UNTIL_USED_COMPONENTS) == 0) {
                    return false;
                }
            case COMPONENT_ENABLED_STATE_DEFAULT:
                if (!componentInfo.applicationInfo.enabled) {
                    return false;
                }
            case COMPONENT_ENABLED_STATE_ENABLED:
                break;
        }

        // Check if component has explicit state before falling through to
        // the manifest default
        if (ArrayUtils.contains(this.enabledComponents, componentInfo.name)) {
            return true;
        }
        if (ArrayUtils.contains(this.disabledComponents, componentInfo.name)) {
            return false;
        }

        return componentInfo.enabled;
    }
}
