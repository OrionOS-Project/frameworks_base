/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.android.systemui.recents;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.res.Configuration;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.Log;
import android.view.Display;
import android.view.View;
import com.android.systemui.RecentsComponent;
import com.android.systemui.SystemUI;
import com.android.systemui.recents.events.EventBus;
import com.android.systemui.recents.events.component.RecentsVisibilityChangedEvent;
import com.android.systemui.recents.events.component.ScreenPinningRequestEvent;
import com.android.systemui.recents.misc.SystemServicesProxy;
import com.android.systemui.recents.model.RecentsTaskLoader;

import java.util.ArrayList;


/**
 * An implementation of the SystemUI recents component, which supports both system and secondary
 * users.
 */
public class Recents extends SystemUI
        implements RecentsComponent {

    private final static String TAG = "Recents";
    private final static boolean DEBUG = false;

    public final static int EVENT_BUS_PRIORITY = 1;
    public final static int BIND_TO_SYSTEM_USER_RETRY_DELAY = 5000;

    // Purely for experimentation
    private final static String RECENTS_OVERRIDE_SYSPROP_KEY = "persist.recents_override_pkg";
    private final static String ACTION_SHOW_RECENTS = "com.android.systemui.recents.ACTION_SHOW";
    private final static String ACTION_HIDE_RECENTS = "com.android.systemui.recents.ACTION_HIDE";
    private final static String ACTION_TOGGLE_RECENTS = "com.android.systemui.recents.ACTION_TOGGLE";

    private static SystemServicesProxy sSystemServicesProxy;
    private static RecentsTaskLoader sTaskLoader;
    private static RecentsConfiguration sConfiguration;

    // For experiments only, allows another package to handle recents if it is defined in the system
    // properties.  This is limited to show/toggle/hide, and does not tie into the ActivityManager,
    // and does not reside in the home stack.
    private String mOverrideRecentsPackageName;

    private Handler mHandler;
    private RecentsImpl mImpl;

    // Only For system user, this is the callbacks instance we return to each secondary user
    private RecentsSystemUser mSystemUserCallbacks;

    // Only for secondary users, this is the callbacks instance provided by the system user to make
    // calls back
    private IRecentsSystemUserCallbacks mCallbacksToSystemUser;

    // The set of runnables to run after binding to the system user's service.
    private final ArrayList<Runnable> mOnConnectRunnables = new ArrayList<>();

    // Only for secondary users, this is the death handler for the binder from the system user
    private final IBinder.DeathRecipient mCallbacksToSystemUserDeathRcpt = new IBinder.DeathRecipient() {
        @Override
        public void binderDied() {
            mCallbacksToSystemUser = null;

            // Retry after a fixed duration
            mHandler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    registerWithSystemUser();
                }
            }, BIND_TO_SYSTEM_USER_RETRY_DELAY);
        }
    };

    // Only for secondary users, this is the service connection we use to connect to the system user
    private final ServiceConnection mServiceConnectionToSystemUser = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            if (service != null) {
                mCallbacksToSystemUser = IRecentsSystemUserCallbacks.Stub.asInterface(
                        service);

                // Listen for system user's death, so that we can reconnect later
                try {
                    service.linkToDeath(mCallbacksToSystemUserDeathRcpt, 0);
                } catch (RemoteException e) {
                    Log.e(TAG, "Lost connection to (System) SystemUI", e);
                }

                // Run each of the queued runnables
                runAndFlushOnConnectRunnables();
            }

            // Unbind ourselves now that we've registered our callbacks.  The
            // binder to the system user are still valid at this point.
            mContext.unbindService(this);
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            // Do nothing
        }
    };

    /**
     * Returns the callbacks interface that non-system users can call.
     */
    public IBinder getSystemUserCallbacks() {
        return mSystemUserCallbacks;
    }

    public static RecentsTaskLoader getTaskLoader() {
        return sTaskLoader;
    }

    public static SystemServicesProxy getSystemServices() {
        return sSystemServicesProxy;
    }

    public static RecentsConfiguration getConfiguration() {
        return sConfiguration;
    }

    @Override
    public void start() {
        sSystemServicesProxy = new SystemServicesProxy(mContext);
        sTaskLoader = new RecentsTaskLoader(mContext);
        sConfiguration = new RecentsConfiguration(mContext);
        mHandler = new Handler();
        mImpl = new RecentsImpl(mContext);

        // Check if there is a recents override package
        if ("userdebug".equals(Build.TYPE) || "eng".equals(Build.TYPE)) {
            String cnStr = SystemProperties.get(RECENTS_OVERRIDE_SYSPROP_KEY);
            if (!cnStr.isEmpty()) {
                mOverrideRecentsPackageName = cnStr;
            }
        }

        // Register with the event bus
        EventBus.getDefault().register(this, EVENT_BUS_PRIORITY);
        EventBus.getDefault().register(sTaskLoader, EVENT_BUS_PRIORITY);

        // Due to the fact that RecentsActivity is per-user, we need to establish and interface for
        // the system user's Recents component to pass events (like show/hide/toggleRecents) to the
        // secondary user, and vice versa (like visibility change, screen pinning).
        final int processUser = sSystemServicesProxy.getProcessUser();
        if (sSystemServicesProxy.isSystemUser(processUser)) {
            // For the system user, initialize an instance of the interface that we can pass to the
            // secondary user
            mSystemUserCallbacks = new RecentsSystemUser(mContext, mImpl);
        } else {
            // For the secondary user, bind to the primary user's service to get a persistent
            // interface to register its implementation and to later update its state
            registerWithSystemUser();
        }
        putComponent(Recents.class, this);
    }

    @Override
    public void onBootCompleted() {
        mImpl.onBootCompleted();
    }

    /**
     * Shows the Recents.
     */
    @Override
    public void showRecents(boolean triggeredFromAltTab, View statusBarView) {
        // Ensure the device has been provisioned before allowing the user to interact with
        // recents
        if (!isDeviceProvisioned()) {
            return;
        }

        if (proxyToOverridePackage(ACTION_SHOW_RECENTS)) {
            return;
        }

        int currentUser = sSystemServicesProxy.getCurrentUser();
        if (sSystemServicesProxy.isSystemUser(currentUser)) {
            mImpl.showRecents(triggeredFromAltTab);
        } else {
            if (mSystemUserCallbacks != null) {
                IRecentsNonSystemUserCallbacks callbacks =
                        mSystemUserCallbacks.getNonSystemUserRecentsForUser(currentUser);
                if (callbacks != null) {
                    try {
                        callbacks.showRecents(triggeredFromAltTab);
                    } catch (RemoteException e) {
                        Log.e(TAG, "Callback failed", e);
                    }
                } else {
                    Log.e(TAG, "No SystemUI callbacks found for user: " + currentUser);
                }
            }
        }
    }

    /**
     * Hides the Recents.
     */
    @Override
    public void hideRecents(boolean triggeredFromAltTab, boolean triggeredFromHomeKey) {
        // Ensure the device has been provisioned before allowing the user to interact with
        // recents
        if (!isDeviceProvisioned()) {
            return;
        }

        if (proxyToOverridePackage(ACTION_HIDE_RECENTS)) {
            return;
        }

        int currentUser = sSystemServicesProxy.getCurrentUser();
        if (sSystemServicesProxy.isSystemUser(currentUser)) {
            mImpl.hideRecents(triggeredFromAltTab, triggeredFromHomeKey);
        } else {
            if (mSystemUserCallbacks != null) {
                IRecentsNonSystemUserCallbacks callbacks =
                        mSystemUserCallbacks.getNonSystemUserRecentsForUser(currentUser);
                if (callbacks != null) {
                    try {
                        callbacks.hideRecents(triggeredFromAltTab, triggeredFromHomeKey);
                    } catch (RemoteException e) {
                        Log.e(TAG, "Callback failed", e);
                    }
                } else {
                    Log.e(TAG, "No SystemUI callbacks found for user: " + currentUser);
                }
            }
        }
    }

    /**
     * Toggles the Recents activity.
     */
    @Override
    public void toggleRecents(Display display, int layoutDirection, View statusBarView) {
        // Ensure the device has been provisioned before allowing the user to interact with
        // recents
        if (!isDeviceProvisioned()) {
            return;
        }

        if (proxyToOverridePackage(ACTION_TOGGLE_RECENTS)) {
            return;
        }

        int currentUser = sSystemServicesProxy.getCurrentUser();
        if (sSystemServicesProxy.isSystemUser(currentUser)) {
            mImpl.toggleRecents();
        } else {
            if (mSystemUserCallbacks != null) {
                IRecentsNonSystemUserCallbacks callbacks =
                        mSystemUserCallbacks.getNonSystemUserRecentsForUser(currentUser);
                if (callbacks != null) {
                    try {
                        callbacks.toggleRecents();
                    } catch (RemoteException e) {
                        Log.e(TAG, "Callback failed", e);
                    }
                } else {
                    Log.e(TAG, "No SystemUI callbacks found for user: " + currentUser);
                }
            }
        }
    }

    /**
     * Preloads info for the Recents activity.
     */
    @Override
    public void preloadRecents() {
        // Ensure the device has been provisioned before allowing the user to interact with
        // recents
        if (!isDeviceProvisioned()) {
            return;
        }

        int currentUser = sSystemServicesProxy.getCurrentUser();
        if (sSystemServicesProxy.isSystemUser(currentUser)) {
            mImpl.preloadRecents();
        } else {
            if (mSystemUserCallbacks != null) {
                IRecentsNonSystemUserCallbacks callbacks =
                        mSystemUserCallbacks.getNonSystemUserRecentsForUser(currentUser);
                if (callbacks != null) {
                    try {
                        callbacks.preloadRecents();
                    } catch (RemoteException e) {
                        Log.e(TAG, "Callback failed", e);
                    }
                } else {
                    Log.e(TAG, "No SystemUI callbacks found for user: " + currentUser);
                }
            }
        }
    }

    @Override
    public void cancelPreloadingRecents() {
        // Ensure the device has been provisioned before allowing the user to interact with
        // recents
        if (!isDeviceProvisioned()) {
            return;
        }

        int currentUser = sSystemServicesProxy.getCurrentUser();
        if (sSystemServicesProxy.isSystemUser(currentUser)) {
            mImpl.cancelPreloadingRecents();
        } else {
            if (mSystemUserCallbacks != null) {
                IRecentsNonSystemUserCallbacks callbacks =
                        mSystemUserCallbacks.getNonSystemUserRecentsForUser(currentUser);
                if (callbacks != null) {
                    try {
                        callbacks.cancelPreloadingRecents();
                    } catch (RemoteException e) {
                        Log.e(TAG, "Callback failed", e);
                    }
                } else {
                    Log.e(TAG, "No SystemUI callbacks found for user: " + currentUser);
                }
            }
        }
    }

    @Override
    public void dockTopTask() {
        mImpl.dockTopTask();
    }

    @Override
    public void showNextAffiliatedTask() {
        // Ensure the device has been provisioned before allowing the user to interact with
        // recents
        if (!isDeviceProvisioned()) {
            return;
        }

        mImpl.showNextAffiliatedTask();
    }

    @Override
    public void showPrevAffiliatedTask() {
        // Ensure the device has been provisioned before allowing the user to interact with
        // recents
        if (!isDeviceProvisioned()) {
            return;
        }

        mImpl.showPrevAffiliatedTask();
    }

    /**
     * Updates on configuration change.
     */
    public void onConfigurationChanged(Configuration newConfig) {
        int currentUser = sSystemServicesProxy.getCurrentUser();
        if (sSystemServicesProxy.isSystemUser(currentUser)) {
            mImpl.onConfigurationChanged();
        } else {
            if (mSystemUserCallbacks != null) {
                IRecentsNonSystemUserCallbacks callbacks =
                        mSystemUserCallbacks.getNonSystemUserRecentsForUser(currentUser);
                if (callbacks != null) {
                    try {
                        callbacks.onConfigurationChanged();
                    } catch (RemoteException e) {
                        Log.e(TAG, "Callback failed", e);
                    }
                } else {
                    Log.e(TAG, "No SystemUI callbacks found for user: " + currentUser);
                }
            }
        }
    }

    /**
     * Handle Recents activity visibility changed.
     */
    public final void onBusEvent(final RecentsVisibilityChangedEvent event) {
        int processUser = event.systemServicesProxy.getProcessUser();
        if (event.systemServicesProxy.isSystemUser(processUser)) {
            mImpl.onVisibilityChanged(event.applicationContext, event.visible);
        } else {
            postToSystemUser(new Runnable() {
                @Override
                public void run() {
                    try {
                        mCallbacksToSystemUser.updateRecentsVisibility(event.visible);
                    } catch (RemoteException e) {
                        Log.e(TAG, "Callback failed", e);
                    }
                }
            });
        }
    }

    /**
     * Handle screen pinning request.
     */
    public final void onBusEvent(final ScreenPinningRequestEvent event) {
        int processUser = sSystemServicesProxy.getProcessUser();
        if (sSystemServicesProxy.isSystemUser(processUser)) {
            mImpl.onStartScreenPinning(event.applicationContext);
        } else {
            postToSystemUser(new Runnable() {
                @Override
                public void run() {
                    try {
                        mCallbacksToSystemUser.startScreenPinning();
                    } catch (RemoteException e) {
                        Log.e(TAG, "Callback failed", e);
                    }
                }
            });
        }
    }

    /**
     * Attempts to register with the system user.
     */
    private void registerWithSystemUser() {
        final int processUser = sSystemServicesProxy.getProcessUser();
        postToSystemUser(new Runnable() {
            @Override
            public void run() {
                try {
                    mCallbacksToSystemUser.registerNonSystemUserCallbacks(mImpl, processUser);
                } catch (RemoteException e) {
                    Log.e(TAG, "Failed to register", e);
                }
            }
        });
    }

    /**
     * Runs the runnable in the system user's Recents context, connecting to the service if
     * necessary.
     */
    private void postToSystemUser(final Runnable onConnectRunnable) {
        mOnConnectRunnables.add(onConnectRunnable);
        if (mCallbacksToSystemUser == null) {
            Intent systemUserServiceIntent = new Intent();
            systemUserServiceIntent.setClass(mContext, RecentsSystemUserService.class);
            boolean bound = mContext.bindServiceAsUser(systemUserServiceIntent,
                    mServiceConnectionToSystemUser, Context.BIND_AUTO_CREATE, UserHandle.SYSTEM);
            if (!bound) {
                // Retry after a fixed duration
                mHandler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        registerWithSystemUser();
                    }
                }, BIND_TO_SYSTEM_USER_RETRY_DELAY);
            }
        } else {
            runAndFlushOnConnectRunnables();
        }
    }

    /**
     * Runs all the queued runnables after a service connection is made.
     */
    private void runAndFlushOnConnectRunnables() {
        for (Runnable r : mOnConnectRunnables) {
            r.run();
        }
        mOnConnectRunnables.clear();
    }

    /**
     * @return whether this device is provisioned.
     */
    private boolean isDeviceProvisioned() {
        return Settings.Global.getInt(mContext.getContentResolver(),
                Settings.Global.DEVICE_PROVISIONED, 0) != 0;
    }

    /**
     * Attempts to proxy the following action to the override recents package.
     * @return whether the proxying was successful
     */
    private boolean proxyToOverridePackage(String action) {
        if (mOverrideRecentsPackageName != null) {
            Intent intent = new Intent(action);
            intent.setPackage(mOverrideRecentsPackageName);
            intent.addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
            mContext.sendBroadcast(intent);
            return true;
        }
        return false;
    }
}
