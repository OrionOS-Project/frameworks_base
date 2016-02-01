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
 * limitations under the License
 */
package com.android.systemui.qs.external;

import android.content.ComponentName;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.graphics.drawable.Drawable;
import android.os.Binder;
import android.os.IBinder;
import android.os.RemoteException;
import android.service.quicksettings.IQSTileService;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;
import android.text.SpannableStringBuilder;
import android.text.style.ForegroundColorSpan;
import android.util.Log;
import android.view.IWindowManager;
import android.view.WindowManager;
import android.view.WindowManagerGlobal;

import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.MetricsProto.MetricsEvent;
import com.android.systemui.R;
import com.android.systemui.qs.QSTile;
import com.android.systemui.statusbar.phone.QSTileHost;

public class CustomTile extends QSTile<QSTile.State> {
    public static final String PREFIX = "custom(";

    private static final boolean DEBUG = false;

    // We don't want to thrash binding and unbinding if the user opens and closes the panel a lot.
    // So instead we have a period of waiting.
    private static final long UNBIND_DELAY = 30000;

    private final ComponentName mComponent;
    private final Tile mTile;
    private final IWindowManager mWindowManager;
    private final IBinder mToken = new Binder();
    private final IQSTileService mService;
    private final TileServiceManager mServiceManager;

    private boolean mListening;
    private boolean mBound;
    private boolean mIsTokenGranted;
    private boolean mIsShowingDialog;

    private CustomTile(QSTileHost host, String action) {
        super(host);
        mWindowManager = WindowManagerGlobal.getWindowManagerService();
        mComponent = ComponentName.unflattenFromString(action);
        mServiceManager = host.getTileServices().getTileWrapper(this);
        mService = mServiceManager.getTileService();
        mTile = new Tile(mComponent);
        try {
            PackageManager pm = mContext.getPackageManager();
            ServiceInfo info = pm.getServiceInfo(mComponent, 0);
            mTile.setIcon(android.graphics.drawable.Icon
                    .createWithResource(mComponent.getPackageName(), info.icon));
            mTile.setLabel(info.loadLabel(pm));
        } catch (Exception e) {
        }
        try {
            mService.setQSTile(mTile);
        } catch (RemoteException e) {
            // Called through wrapper, won't happen here.
        }
    }

    public ComponentName getComponent() {
        return mComponent;
    }

    public Tile getQsTile() {
        return mTile;
    }

    public void updateState(Tile tile) {
        mTile.setIcon(tile.getIcon());
        mTile.setLabel(tile.getLabel());
        mTile.setContentDescription(tile.getContentDescription());
        mTile.setState(tile.getState());
    }

    public void onDialogShown() {
        mIsShowingDialog = true;
    }

    public void onDialogHidden() {
        mIsShowingDialog = false;
        try {
            if (DEBUG) Log.d(TAG, "Removing token");
            mWindowManager.removeWindowToken(mToken);
        } catch (RemoteException e) {
        }
    }

    @Override
    public void setListening(boolean listening) {
        if (mListening == listening) return;
        mListening = listening;
        try {
            if (listening) {
                if (mServiceManager.getType() == TileService.TILE_MODE_PASSIVE) {
                    mServiceManager.setBindRequested(true);
                    mService.onStartListening();
                }
            } else {
                mService.onStopListening();
                if (mIsTokenGranted && !mIsShowingDialog) {
                    try {
                        if (DEBUG) Log.d(TAG, "Removing token");
                        mWindowManager.removeWindowToken(mToken);
                    } catch (RemoteException e) {
                    }
                    mIsTokenGranted = false;
                }
                mIsShowingDialog = false;
                mServiceManager.setBindRequested(false);
            }
        } catch (RemoteException e) {
            // Called through wrapper, won't happen here.
        }
    }

    @Override
    protected void handleDestroy() {
        super.handleDestroy();
        if (mIsTokenGranted) {
            try {
                if (DEBUG) Log.d(TAG, "Removing token");
                mWindowManager.removeWindowToken(mToken);
            } catch (RemoteException e) {
            }
        }
        mHost.getTileServices().freeService(this, mServiceManager);
    }

    @Override
    protected State newTileState() {
        return new State();
    }

    @Override
    protected void handleUserSwitch(int newUserId) {
        super.handleUserSwitch(newUserId);
    }

    @Override
    protected void handleClick() {
        if (mTile.getState() == Tile.STATE_UNAVAILABLE) {
            return;
        }
        try {
            if (DEBUG) Log.d(TAG, "Adding token");
            mWindowManager.addWindowToken(mToken, WindowManager.LayoutParams.TYPE_QS_DIALOG);
            mIsTokenGranted = true;
        } catch (RemoteException e) {
        }
        try {
            if (mServiceManager.getType() == TileService.TILE_MODE_ACTIVE) {
                mServiceManager.setBindRequested(true);
                mService.onStartListening();
            }
            mService.onClick(mToken);
        } catch (RemoteException e) {
            // Called through wrapper, won't happen here.
        }
        MetricsLogger.action(mContext, getMetricsCategory(), mComponent.getPackageName());
    }

    @Override
    protected void handleLongClick() {
    }

    @Override
    protected void handleUpdateState(State state, Object arg) {
        Drawable drawable = mTile.getIcon().loadDrawable(mContext);
        int color = mContext.getColor(getColor(mTile.getState()));
        drawable.setTint(color);
        state.icon = new DrawableIcon(drawable);
        state.label = mTile.getLabel();
        if (mTile.getState() == Tile.STATE_UNAVAILABLE) {
            state.label = new SpannableStringBuilder().append(state.label,
                    new ForegroundColorSpan(color),
                    SpannableStringBuilder.SPAN_INCLUSIVE_INCLUSIVE);
        }
        if (mTile.getContentDescription() != null) {
            state.contentDescription = mTile.getContentDescription();
        } else {
            state.contentDescription = state.label;
        }
    }

    @Override
    public int getMetricsCategory() {
        return MetricsEvent.QS_CUSTOM;
    }

    public void startUnlockAndRun() {
        mHost.startRunnableDismissingKeyguard(new Runnable() {
            @Override
            public void run() {
                try {
                    mService.onUnlockComplete();
                } catch (RemoteException e) {
                }
            }
        });
    }

    private static int getColor(int state) {
        switch (state) {
            case Tile.STATE_UNAVAILABLE:
                return R.color.qs_tile_tint_unavailable;
            case Tile.STATE_INACTIVE:
                return R.color.qs_tile_tint_inactive;
            case Tile.STATE_ACTIVE:
                return R.color.qs_tile_tint_active;
        }
        return 0;
    }

    public static ComponentName getComponentFromSpec(String spec) {
        final String action = spec.substring(PREFIX.length(), spec.length() - 1);
        if (action.isEmpty()) {
            throw new IllegalArgumentException("Empty custom tile spec action");
        }
        return ComponentName.unflattenFromString(action);
    }

    public static QSTile<?> create(QSTileHost host, String spec) {
        if (spec == null || !spec.startsWith(PREFIX) || !spec.endsWith(")")) {
            throw new IllegalArgumentException("Bad custom tile spec: " + spec);
        }
        final String action = spec.substring(PREFIX.length(), spec.length() - 1);
        if (action.isEmpty()) {
            throw new IllegalArgumentException("Empty custom tile spec action");
        }
        return new CustomTile(host, action);
    }
}
