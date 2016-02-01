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

package com.android.systemui.qs;

import android.animation.Animator;
import android.animation.Animator.AnimatorListener;
import android.animation.AnimatorListenerAdapter;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.os.Handler;
import android.os.Message;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityEvent;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.MetricsProto.MetricsEvent;
import com.android.systemui.FontSizeUtils;
import com.android.systemui.R;
import com.android.systemui.qs.QSTile.DetailAdapter;
import com.android.systemui.qs.customize.QSCustomizer;
import com.android.systemui.qs.external.CustomTile;
import com.android.systemui.settings.BrightnessController;
import com.android.systemui.settings.ToggleSlider;
import com.android.systemui.statusbar.phone.QSTileHost;
import com.android.systemui.statusbar.policy.BrightnessMirrorController;
import com.android.systemui.tuner.TunerService;
import com.android.systemui.tuner.TunerService.Tunable;

import java.util.ArrayList;
import java.util.Collection;

/** View that represents the quick settings tile panel. **/
public class QSPanel extends FrameLayout implements Tunable {

    public static final String QS_SHOW_BRIGHTNESS = "qs_show_brightness";

    protected final Context mContext;
    protected final ArrayList<TileRecord> mRecords = new ArrayList<TileRecord>();
    private final View mDetail;
    private final ViewGroup mDetailContent;
    private final TextView mDetailSettingsButton;
    private final TextView mDetailDoneButton;
    protected final View mBrightnessView;
    private final QSDetailClipper mClipper;
    private final H mHandler = new H();

    private int mPanelPaddingBottom;
    private int mBrightnessPaddingTop;
    private boolean mExpanded;
    private boolean mListening;
    private boolean mClosingDetail;

    private Record mDetailRecord;
    private Callback mCallback;
    private BrightnessController mBrightnessController;
    protected QSTileHost mHost;

    protected QSFooter mFooter;
    private boolean mGridContentVisible = true;

    protected LinearLayout mQsContainer;
    protected QSTileLayout mTileLayout;

    private QSCustomizer mCustomizePanel;

    public QSPanel(Context context) {
        this(context, null);
    }

    public QSPanel(Context context, AttributeSet attrs) {
        super(context, attrs);
        mContext = context;

        mDetail = LayoutInflater.from(context).inflate(R.layout.qs_detail, this, false);
        mDetailContent = (ViewGroup) mDetail.findViewById(android.R.id.content);
        mDetailSettingsButton = (TextView) mDetail.findViewById(android.R.id.button2);
        mDetailDoneButton = (TextView) mDetail.findViewById(android.R.id.button1);
        updateDetailText();
        mDetail.setVisibility(GONE);
        mDetail.setClickable(true);
        addView(mDetail);

        mQsContainer = new LinearLayout(mContext);
        mQsContainer.setOrientation(LinearLayout.VERTICAL);
        mQsContainer.setLayoutParams(new LayoutParams(LayoutParams.MATCH_PARENT,
                LayoutParams.WRAP_CONTENT));
        addView(mQsContainer);

        mBrightnessView = LayoutInflater.from(context).inflate(
                R.layout.quick_settings_brightness_dialog, this, false);
        mQsContainer.addView(mBrightnessView);

        mTileLayout = (QSTileLayout) LayoutInflater.from(mContext).inflate(
                R.layout.qs_paged_tile_layout, mQsContainer, false);
        mQsContainer.addView((View) mTileLayout);

        mFooter = new QSFooter(this, context);
        mQsContainer.addView(mFooter.getView());

        mClipper = new QSDetailClipper(mDetail);
        updateResources();

        mBrightnessController = new BrightnessController(getContext(),
                (ImageView) findViewById(R.id.brightness_icon),
                (ToggleSlider) findViewById(R.id.brightness_slider));

        mDetailDoneButton.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                announceForAccessibility(
                        mContext.getString(R.string.accessibility_desc_quick_settings));
                closeDetail();
            }
        });
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        TunerService.get(mContext).addTunable(this, QS_SHOW_BRIGHTNESS);
    }

    @Override
    protected void onDetachedFromWindow() {
        TunerService.get(mContext).removeTunable(this);
        super.onDetachedFromWindow();
    }

    @Override
    public void onTuningChanged(String key, String newValue) {
        if (QS_SHOW_BRIGHTNESS.equals(key)) {
            mBrightnessView.setVisibility(newValue == null || Integer.parseInt(newValue) != 0
                    ? VISIBLE : GONE);
        }
    }

    public void openDetails(String subPanel) {
        QSTile<?> tile = getTile(subPanel);
        showDetailAdapter(true, tile.getDetailAdapter(), new int[] {getWidth() / 2, 0});
    }

    private QSTile<?> getTile(String subPanel) {
        for (int i = 0; i < mRecords.size(); i++) {
            if (subPanel.equals(mRecords.get(i).tile.getTileSpec())) {
                return mRecords.get(i).tile;
            }
        }
        return mHost.createTile(subPanel);
    }

    protected void createCustomizePanel() {
        mCustomizePanel = (QSCustomizer) LayoutInflater.from(mContext)
                .inflate(R.layout.qs_customize_panel, null);
        mCustomizePanel.setHost(mHost);
    }

    private void updateDetailText() {
        mDetailDoneButton.setText(R.string.quick_settings_done);
        mDetailSettingsButton.setText(R.string.quick_settings_more_settings);
    }

    public void setBrightnessMirror(BrightnessMirrorController c) {
        super.onFinishInflate();
        ToggleSlider brightnessSlider = (ToggleSlider) findViewById(R.id.brightness_slider);
        ToggleSlider mirror = (ToggleSlider) c.getMirror().findViewById(R.id.brightness_slider);
        brightnessSlider.setMirror(mirror);
        brightnessSlider.setMirrorController(c);
    }

    public void setCallback(Callback callback) {
        mCallback = callback;
    }

    public void setHost(QSTileHost host) {
        mHost = host;
        mFooter.setHost(host);
        createCustomizePanel();
    }

    public QSTileHost getHost() {
        return mHost;
    }

    public void updateResources() {
        final Resources res = mContext.getResources();
        mPanelPaddingBottom = res.getDimensionPixelSize(R.dimen.qs_panel_padding_bottom);
        mBrightnessPaddingTop = res.getDimensionPixelSize(R.dimen.qs_brightness_padding_top);
        mQsContainer.setPadding(0, mBrightnessPaddingTop, 0, mPanelPaddingBottom);
        for (TileRecord r : mRecords) {
            r.tile.clearState();
        }
        if (mListening) {
            refreshAllTiles();
        }
        updateDetailText();
        if (mTileLayout != null) {
            mTileLayout.updateResources();
        }
    }

    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        FontSizeUtils.updateFontSize(mDetailDoneButton, R.dimen.qs_detail_button_text_size);
        FontSizeUtils.updateFontSize(mDetailSettingsButton, R.dimen.qs_detail_button_text_size);

        // We need to poke the detail views as well as they might not be attached to the view
        // hierarchy but reused at a later point.
        int count = mRecords.size();
        for (int i = 0; i < count; i++) {
            View detailView = mRecords.get(i).detailView;
            if (detailView != null) {
                detailView.dispatchConfigurationChanged(newConfig);
            }
        }
        mFooter.onConfigurationChanged();
    }

    public void onCollapse() {
        if (mCustomizePanel != null && mCustomizePanel.isCustomizing()) {
            mCustomizePanel.hide(mCustomizePanel.getWidth() / 2, mCustomizePanel.getHeight() / 2);
        }
    }

    public void setExpanded(boolean expanded) {
        if (mExpanded == expanded) return;
        mExpanded = expanded;
        MetricsLogger.visibility(mContext, MetricsEvent.QS_PANEL, mExpanded);
        if (!mExpanded) {
            closeDetail();
        } else {
            logTiles();
        }
    }

    public void setListening(boolean listening) {
        if (mListening == listening) return;
        mListening = listening;
        for (TileRecord r : mRecords) {
            r.tile.setListening(mListening);
        }
        mFooter.setListening(mListening);
        if (mListening) {
            refreshAllTiles();
        }
        if (listening) {
            mBrightnessController.registerCallbacks();
        } else {
            mBrightnessController.unregisterCallbacks();
        }
    }

    public void refreshAllTiles() {
        for (TileRecord r : mRecords) {
            r.tile.refreshState();
        }
        mFooter.refreshState();
    }

    public void showDetailAdapter(boolean show, DetailAdapter adapter, int[] locationInWindow) {
        int xInWindow = locationInWindow[0];
        int yInWindow = locationInWindow[1];
        mDetail.getLocationInWindow(locationInWindow);

        Record r = new Record();
        r.detailAdapter = adapter;
        r.x = xInWindow - locationInWindow[0];
        r.y = yInWindow - locationInWindow[1];

        locationInWindow[0] = xInWindow;
        locationInWindow[1] = yInWindow;

        showDetail(show, r);
    }

    protected void showDetail(boolean show, Record r) {
        mHandler.obtainMessage(H.SHOW_DETAIL, show ? 1 : 0, 0, r).sendToTarget();
    }

    public void setTiles(Collection<QSTile<?>> tiles) {
        for (TileRecord record : mRecords) {
            mTileLayout.removeTile(record);
        }
        mRecords.clear();
        for (QSTile<?> tile : tiles) {
            addTile(tile);
        }
        if (isShowingDetail()) {
            mDetail.bringToFront();
        }
    }

    private void drawTile(TileRecord r, QSTile.State state) {
        r.tileView.onStateChanged(state);
    }

    protected QSTileBaseView createTileView(QSTile<?> tile) {
        return new QSTileView(mContext, tile.createTileView(mContext));
    }

    protected void addTile(final QSTile<?> tile) {
        final TileRecord r = new TileRecord();
        r.tile = tile;
        r.tileView = createTileView(tile);
        final QSTile.Callback callback = new QSTile.Callback() {
            @Override
            public void onStateChanged(QSTile.State state) {
                if (!r.openingDetail) {
                    drawTile(r, state);
                }
            }

            @Override
            public void onShowDetail(boolean show) {
                QSPanel.this.showDetail(show, r);
            }

            @Override
            public void onToggleStateChanged(boolean state) {
                if (mDetailRecord == r) {
                    fireToggleStateChanged(state);
                }
            }

            @Override
            public void onScanStateChanged(boolean state) {
                r.scanState = state;
                if (mDetailRecord == r) {
                    fireScanStateChanged(r.scanState);
                }
            }

            @Override
            public void onAnnouncementRequested(CharSequence announcement) {
                announceForAccessibility(announcement);
            }
        };
        r.tile.addCallback(callback);
        final View.OnClickListener click = new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onTileClick(r.tile);
            }
        };
        final View.OnLongClickListener longClick = new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                if (mCustomizePanel != null) {
                    if (!mCustomizePanel.isCustomizing()) {
                        int[] loc = new int[2];
                        getLocationInWindow(loc);
                        int x = r.tileView.getLeft() + r.tileView.getWidth() / 2 + loc[0];
                        int y = r.tileView.getTop() + mTileLayout.getOffsetTop(r)
                                + r.tileView.getHeight() / 2 + loc[1];
                        mCustomizePanel.show(x, y);
                    }
                } else {
                    r.tile.longClick();
                }
                return true;
            }
        };
        r.tileView.init(click, longClick);
        r.tile.setListening(mListening);
        callback.onStateChanged(r.tile.getState());
        r.tile.refreshState();
        mRecords.add(r);

        if (mTileLayout != null) {
            mTileLayout.addTile(r);
        }
    }

    protected void onTileClick(QSTile<?> tile) {
        tile.click();
    }

    public boolean isShowingDetail() {
        return mDetailRecord != null
                || (mCustomizePanel != null && mCustomizePanel.isCustomizing());
    }

    public void closeDetail() {
        if (mCustomizePanel != null && mCustomizePanel.isCustomizing()) {
            // Treat this as a detail panel for now, to make things easy.
            mCustomizePanel.hide(mCustomizePanel.getWidth() / 2, mCustomizePanel.getHeight() / 2);
            return;
        }
        showDetail(false, mDetailRecord);
    }

    public boolean isClosingDetail() {
        return mClosingDetail;
    }

    public int getGridHeight() {
        return mQsContainer.getMeasuredHeight();
    }

    protected void handleShowDetail(Record r, boolean show) {
        if (show && !mExpanded) {
            mHost.animateExpandQS();
        }
        if (r instanceof TileRecord) {
            handleShowDetailTile((TileRecord) r, show);
        } else {
            int x = 0;
            int y = 0;
            if (r != null) {
                x = r.x;
                y = r.y;
            }
            handleShowDetailImpl(r, show, x, y);
        }
    }

    private void handleShowDetailTile(TileRecord r, boolean show) {
        if ((mDetailRecord != null) == show && mDetailRecord == r) return;

        if (show) {
            r.detailAdapter = r.tile.getDetailAdapter();
            if (r.detailAdapter == null) return;
        }
        r.tile.setDetailListening(show);
        int x = r.tileView.getLeft() + r.tileView.getWidth() / 2;
        int y = r.tileView.getTop() + mTileLayout.getOffsetTop(r) + r.tileView.getHeight() / 2;
        handleShowDetailImpl(r, show, x, y);
    }

    private void handleShowDetailImpl(Record r, boolean show, int x, int y) {
        boolean visibleDiff = (mDetailRecord != null) != show;
        if (!visibleDiff && mDetailRecord == r) return;  // already in right state
        DetailAdapter detailAdapter = null;
        AnimatorListener listener = null;
        if (show) {
            detailAdapter = r.detailAdapter;
            r.detailView = detailAdapter.createDetailView(mContext, r.detailView, mDetailContent);
            if (r.detailView == null) throw new IllegalStateException("Must return detail view");

            final Intent settingsIntent = detailAdapter.getSettingsIntent();
            mDetailSettingsButton.setVisibility(settingsIntent != null ? VISIBLE : GONE);
            mDetailSettingsButton.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    mHost.startActivityDismissingKeyguard(settingsIntent);
                }
            });

            mDetailContent.removeAllViews();
            mDetail.bringToFront();
            mDetailContent.addView(r.detailView);
            MetricsLogger.visible(mContext, detailAdapter.getMetricsCategory());
            announceForAccessibility(mContext.getString(
                    R.string.accessibility_quick_settings_detail,
                    detailAdapter.getTitle()));
            setDetailRecord(r);
            listener = mHideGridContentWhenDone;
            if (r instanceof TileRecord && visibleDiff) {
                ((TileRecord) r).openingDetail = true;
            }
        } else {
            if (mDetailRecord != null) {
                MetricsLogger.hidden(mContext, mDetailRecord.detailAdapter.getMetricsCategory());
            }
            mClosingDetail = true;
            setGridContentVisibility(true);
            listener = mTeardownDetailWhenDone;
            fireScanStateChanged(false);
        }
        sendAccessibilityEvent(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED);
        fireShowingDetail(show ? detailAdapter : null);
        if (visibleDiff) {
            mClipper.animateCircularClip(x, y, show, listener);
        }
    }

    private void setGridContentVisibility(boolean visible) {
        int newVis = visible ? VISIBLE : INVISIBLE;
        mQsContainer.setVisibility(newVis);
        if (mGridContentVisible != visible) {
            MetricsLogger.visibility(mContext, MetricsEvent.QS_PANEL, newVis);
        }
        mGridContentVisible = visible;
    }

    private void logTiles() {
        for (int i = 0; i < mRecords.size(); i++) {
            TileRecord tileRecord = mRecords.get(i);
            MetricsLogger.visible(mContext, tileRecord.tile.getMetricsCategory());
        }
    }

    private void fireShowingDetail(QSTile.DetailAdapter detail) {
        if (mCallback != null) {
            mCallback.onShowingDetail(detail);
        }
    }

    private void fireToggleStateChanged(boolean state) {
        if (mCallback != null) {
            mCallback.onToggleStateChanged(state);
        }
    }

    private void fireScanStateChanged(boolean state) {
        if (mCallback != null) {
            mCallback.onScanStateChanged(state);
        }
    }

    private void setDetailRecord(Record r) {
        if (r == mDetailRecord) return;
        mDetailRecord = r;
        final boolean scanState = mDetailRecord instanceof TileRecord
                && ((TileRecord) mDetailRecord).scanState;
        fireScanStateChanged(scanState);
    }

    public void clickTile(ComponentName tile) {
        final String spec = CustomTile.toSpec(tile);
        final int N = mRecords.size();
        for (int i = 0; i < N; i++) {
            if (mRecords.get(i).tile.getTileSpec().equals(spec)) {
                mRecords.get(i).tile.click();
                break;
            }
        }
    }

    private class H extends Handler {
        private static final int SHOW_DETAIL = 1;
        private static final int SET_TILE_VISIBILITY = 2;
        @Override
        public void handleMessage(Message msg) {
            if (msg.what == SHOW_DETAIL) {
                handleShowDetail((Record)msg.obj, msg.arg1 != 0);
            }
        }
    }

    protected static class Record {
        View detailView;
        DetailAdapter detailAdapter;
        int x;
        int y;
    }

    public static final class TileRecord extends Record {
        public QSTile<?> tile;
        public QSTileBaseView tileView;
        public boolean scanState;
        public boolean openingDetail;
    }

    private final AnimatorListenerAdapter mTeardownDetailWhenDone = new AnimatorListenerAdapter() {
        public void onAnimationEnd(Animator animation) {
            mDetailContent.removeAllViews();
            setDetailRecord(null);
            mClosingDetail = false;
        };
    };

    private final AnimatorListenerAdapter mHideGridContentWhenDone = new AnimatorListenerAdapter() {
        public void onAnimationCancel(Animator animation) {
            // If we have been cancelled, remove the listener so that onAnimationEnd doesn't get
            // called, this will avoid accidentally turning off the grid when we don't want to.
            animation.removeListener(this);
            redrawTile();
        };

        @Override
        public void onAnimationEnd(Animator animation) {
            // Only hide content if still in detail state.
            if (mDetailRecord != null) {
                setGridContentVisibility(false);
                redrawTile();
            }
        }

        private void redrawTile() {
            if (mDetailRecord instanceof TileRecord) {
                final TileRecord tileRecord = (TileRecord) mDetailRecord;
                tileRecord.openingDetail = false;
                drawTile(tileRecord, tileRecord.tile.getState());
            }
        }
    };

    public interface Callback {
        void onShowingDetail(QSTile.DetailAdapter detail);
        void onToggleStateChanged(boolean state);
        void onScanStateChanged(boolean state);
    }

    public interface QSTileLayout {
        void addTile(TileRecord tile);
        void removeTile(TileRecord tile);
        int getOffsetTop(TileRecord tile);
        boolean updateResources();
    }
}
