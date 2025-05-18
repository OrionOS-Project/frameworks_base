/*
 * Copyright (C) 2015 The Dirty Unicorns Project
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

package com.android.systemui.qs.tiles;

import android.content.Context;
import android.content.ComponentName;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.service.quicksettings.Tile;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.Nullable;

import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.nano.MetricsProto.MetricsEvent;
import com.android.internal.util.derp.derpUtils;
import com.android.systemui.SysUIToast;
import com.android.systemui.animation.Expandable;
import com.android.systemui.res.R;
import com.android.systemui.dagger.qualifiers.Background;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.plugins.FalsingManager;
import com.android.systemui.plugins.qs.QSIconView;
import com.android.systemui.plugins.qs.QSTile;
import com.android.systemui.plugins.qs.QSTile.State;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.qs.QSHost;
import com.android.systemui.qs.QsEventLogger;
import com.android.systemui.qs.logging.QSLogger;
import com.android.systemui.qs.tileimpl.QSTileImpl;

import javax.inject.Inject;

public class DerpFestTile extends QSTileImpl<State> {

    public static final String TILE_SPEC = "derpfest";

    private final ActivityStarter mActivityStarter;
    private final String mDerpFestLabel;
    private final String mNotSupportedToast;

    private static final String TAG = "DerpFestTile";

    private static final String DERPFEST_PKG_NAME = "com.android.settings";
    private static final String OTA_PKG_NAME = "org.lineageos.updater";

    private static final Intent DERPFEST_INTENT = new Intent()
            .setComponent(new ComponentName(DERPFEST_PKG_NAME,
                "com.android.settings.Settings$DerpFestCustomizationsActivity"));
    private static final Intent OTA_INTENT = new Intent()
            .setComponent(new ComponentName(OTA_PKG_NAME,
                "org.lineageos.updater.UpdatesActivity"));

    @Inject
    public DerpFestTile(
            QSHost host,
            QsEventLogger uiEventLogger,
            @Background Looper backgroundLooper,
            @Main Handler mainHandler,
            FalsingManager falsingManager,
            MetricsLogger metricsLogger,
            StatusBarStateController statusBarStateController,
            ActivityStarter activityStarter,
            QSLogger qsLogger
    ) {
        super(host, uiEventLogger, backgroundLooper, mainHandler, falsingManager, metricsLogger,
                statusBarStateController, activityStarter, qsLogger);
        mActivityStarter = activityStarter;
        mDerpFestLabel = mContext.getString(R.string.quick_derpfest_label);
        mNotSupportedToast = mContext.getString(R.string.quick_derpfest_toast);
    }

    @Override
    public State newTileState() {
        State state = new State();
        state.handlesLongClick = isOTABundled();
        return state;
    }

    @Override
    protected void handleClick(@Nullable Expandable expandable) {
        try {
            startDerpFest();
            refreshState();
        } catch (Exception e) {
            Log.e(TAG, "Error launching DerpFest customizations", e);
        }
    }

    @Override
    public Intent getLongClickIntent() {
        if (isOTABundled()) {
            return OTA_INTENT;
        }
        showNotSupportedToast();
        return null;
    }

    @Override
    protected void handleSecondaryClick(@Nullable Expandable expandable) {
        if (isOTABundled()) {
            try {
                startDerpFestOTA();
            } catch (Exception e) {
                Log.e(TAG, "Error launching OTA updater", e);
            }
        }
    }

    @Override
    public CharSequence getTileLabel() {
        return mDerpFestLabel;
    }

    protected void startDerpFest() {
        if (mActivityStarter != null) {
            mActivityStarter.postStartActivityDismissingKeyguard(DERPFEST_INTENT, 0);
        }
    }

    protected void startDerpFestOTA() {
        if (mActivityStarter != null) {
            mActivityStarter.postStartActivityDismissingKeyguard(OTA_INTENT, 0);
        }
    }

    private void showNotSupportedToast() {
        if (mContext != null) {
            SysUIToast.makeText(mContext, mNotSupportedToast, Toast.LENGTH_LONG).show();
        }
    }

    private boolean isOTABundled() {
        return mContext != null && derpUtils.isPackageAvailable(mContext, OTA_PKG_NAME);
    }

    private boolean isDerpFestAvailable() {
        if (mContext == null) return false;
        return derpUtils.isPackageInstalled(mContext, DERPFEST_PKG_NAME) ||
               derpUtils.isPackageAvailable(mContext, DERPFEST_PKG_NAME);
    }

    @Override
    public boolean isAvailable() {
        return isDerpFestAvailable();
    }

    @Override
    protected void handleUpdateState(State state, Object arg) {
        state.icon = ResourceIcon.get(R.drawable.ic_qs_derpfest);
        state.label = mDerpFestLabel;
        state.state = Tile.STATE_ACTIVE;
    }

    @Override
    public int getMetricsCategory() {
        return MetricsEvent.DERPFEST;
    }
}
