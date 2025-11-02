/*
 * SPDX-FileCopyrightText: DerpFest AOSP
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.statusbar.phone.ui;

import android.content.Context;
import android.database.ContentObserver;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.UserHandle;
import android.provider.Settings;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.FrameLayout;
import android.widget.TextView;

import com.android.settingslib.Utils;
import com.android.systemui.res.R;
import com.android.systemui.statusbar.StatusIconDisplayable;
import com.android.systemui.statusbar.StatusBarIconView;
import com.android.systemui.statusbar.notification.headsup.HeadsUpManager;
import com.android.systemui.statusbar.notification.headsup.OnHeadsUpChangedListener;
import com.android.systemui.statusbar.policy.KeyguardStateController;

import java.text.NumberFormat;
import java.util.ArrayList;

/**
 * A view that displays the combined count of all notifications as a single chip.
 * This replaces individual notification counters when combined mode is enabled.
 */
public class CombinedNotificationCounter extends FrameLayout 
        implements StatusIconDisplayable, OnHeadsUpChangedListener {
    private static final int DEBOUNCE_DELAY_MS = 150;
    
    private final Context mContext;
    private final Handler mHandler;
    private final Object mLock = new Object();
    private TextView mCountText;
    private int mTotalCount = 0;
    private boolean mShowCombinedCount = false;
    private boolean mIsForceHidden = false;
    private boolean mUpdatePending = false;
    private long mLastUpdateTime = 0;
    private IconManager mIconManager;
    private com.android.systemui.statusbar.phone.NotificationIconContainer mNotificationContainer;
    private KeyguardStateController mKeyguardStateController;
    private KeyguardStateController.Callback mKeyguardCallback;
    private HeadsUpManager mHeadsUpManager;
    private boolean mHeadsUpPinned = false;
    
    // Callback interface for notification count updates
    public interface NotificationCountCallback {
        void onNotificationCountChanged(int count);
    }
    
    private NotificationCountCallback mCountCallback = new NotificationCountCallback() {
        @Override
        public void onNotificationCountChanged(int count) {
            updateNotificationCount(count);
        }
    };
    
    // StatusIconDisplayable implementation
    private String mSlot = "combined_notification_counter";
    private int mVisibleState = StatusBarIconView.STATE_ICON;
    private int mStaticDrawableColor = Color.WHITE;
    private int mDecorColor = Color.WHITE;
    
    private final ContentObserver mSettingsObserver;
    private final ContentObserver mAccentColorSettingsObserver;
    
    private final Runnable mUiUpdateRunnable = new Runnable() {
        @Override
        public void run() {
            synchronized (mLock) {
                mUpdatePending = false;
                mLastUpdateTime = System.currentTimeMillis();
                updateViews();
            }
        }
    };

    public CombinedNotificationCounter(Context context) {
        this(context, null);
    }

    public CombinedNotificationCounter(Context context, android.util.AttributeSet attrs) {
        super(context, attrs);
        mContext = context;
        mHandler = new Handler(android.os.Looper.getMainLooper());
        
        mSettingsObserver = new ContentObserver(mHandler) {
            @Override
            public void onChange(boolean selfChange) {
                updateCombinedCountSetting();
            }
        };
        
        mAccentColorSettingsObserver = new ContentObserver(mHandler) {
            @Override
            public void onChange(boolean selfChange) {
                requestLayout();
            }
        };
        
        initialize();
    }

    private void initialize() {
        // When inflated from XML, children are already inflated
        // Just set up observers and listeners
        
        // Register observers
        mContext.getContentResolver().registerContentObserver(
            Settings.System.getUriFor(Settings.System.STATUSBAR_COMBINED_NOTIF_COUNT),
            false, mSettingsObserver, UserHandle.USER_CURRENT);
        mContext.getContentResolver().registerContentObserver(
            Settings.System.getUriFor(Settings.System.TINT_STATUSBAR_ICONS_WITH_ACCENT),
            false, mAccentColorSettingsObserver, UserHandle.USER_ALL);
        
        updateCombinedCountSetting();
        
        // Monitor notification container when attached
        addOnAttachStateChangeListener(new OnAttachStateChangeListener() {
            @Override
            public void onViewAttachedToWindow(View v) {
                // Get the TextView that was inflated from XML
                mCountText = findViewById(R.id.count_text);
                findAndMonitorNotificationContainer();
                
                // Trigger initial color update
                post(new Runnable() {
                    @Override
                    public void run() {
                        // Request initial dark mode update from parent
                        ViewParent parent = getParent();
                        if (parent instanceof View) {
                            requestLayout();
                        }
                    }
                });
            }
            
            @Override
            public void onViewDetachedFromWindow(View v) {
            }
        });
    }
    
    private void findAndMonitorNotificationContainer() {
        // Find the NotificationIconContainer in the parent
        android.view.ViewParent parent = getParent();
        if (parent instanceof android.view.ViewGroup) {
            android.view.ViewGroup parentGroup = (android.view.ViewGroup) parent;
            for (int i = 0; i < parentGroup.getChildCount(); i++) {
                android.view.View child = parentGroup.getChildAt(i);
                if (child instanceof com.android.systemui.statusbar.phone.NotificationIconContainer) {
                    monitorNotificationContainer((com.android.systemui.statusbar.phone.NotificationIconContainer) child);
                    return;
                }
            }
        }
    }
    
    private void monitorNotificationContainer(final com.android.systemui.statusbar.phone.NotificationIconContainer container) {
        mNotificationContainer = container;
        
        // Monitor children being added/removed
        container.setOnHierarchyChangeListener(new OnHierarchyChangeListener() {
            @Override
            public void onChildViewAdded(android.view.View parent, android.view.View child) {
                updateCountFromContainer(container);
            }
            
            @Override
            public void onChildViewRemoved(android.view.View parent, android.view.View child) {
                updateCountFromContainer(container);
            }
        });
        
        // Initial update
        requestUiUpdate();
    }
    
    private void updateCountFromContainer(com.android.systemui.statusbar.phone.NotificationIconContainer container) {
        int count = container.getChildCount();
        updateNotificationCount(count);
    }
    
    public void updateNotificationCount(int count) {
        if (mTotalCount != count) {
            mTotalCount = count;
            requestUiUpdate();
        }
    }
    
    public NotificationCountCallback getCountCallback() {
        return mCountCallback;
    }
    
    private void requestUiUpdate() {
        long currentTime = System.currentTimeMillis();
        synchronized (mLock) {
            if (!mUpdatePending && (currentTime - mLastUpdateTime > DEBOUNCE_DELAY_MS)) {
                mUpdatePending = false;
                mLastUpdateTime = currentTime;
                updateViews();
            } else if (!mUpdatePending) {
                mUpdatePending = true;
                mHandler.postDelayed(mUiUpdateRunnable, DEBOUNCE_DELAY_MS);
            }
        }
    }
    
    private void updateViews() {
        // Force hidden state (keyguard showing or heads-up pinned)
        if (mIsForceHidden || mHeadsUpPinned) {
            setVisibility(View.GONE);
            if (mNotificationContainer != null) {
                mNotificationContainer.setVisibility(View.VISIBLE);
            }
            return;
        }
        
        // Update counter visibility and text
        if (mShowCombinedCount && mTotalCount > 0) {
            setVisibility(View.VISIBLE);
            updateNumberText();
            // Hide individual notification icons
            if (mNotificationContainer != null) {
                mNotificationContainer.setVisibility(View.GONE);
            }
        } else {
            setVisibility(View.GONE);
            // Show individual notification icons
            if (mNotificationContainer != null) {
                mNotificationContainer.setVisibility(View.VISIBLE);
            }
        }
    }
    
    public void setForceHidden(boolean forceHidden) {
        if (mIsForceHidden != forceHidden) {
            mIsForceHidden = forceHidden;
            requestUiUpdate();
        }
    }

    private void updateCombinedCountSetting() {
        boolean newShowCombinedCount = Settings.System.getIntForUser(
            mContext.getContentResolver(),
            Settings.System.STATUSBAR_COMBINED_NOTIF_COUNT, 0,
            UserHandle.USER_CURRENT) == 1;
            
        if (mShowCombinedCount != newShowCombinedCount) {
            mShowCombinedCount = newShowCombinedCount;
            requestUiUpdate();
        }
    }

    private void updateNumberText() {
        if (mCountText == null) {
            return;
        }
        
        final String str;
        if (mTotalCount <= 0) {
            str = "0";
        } else if (mTotalCount >= 10) {
            str = getContext().getResources().getString(
                R.string.status_bar_notification_info_overflow);
        } else {
            NumberFormat f = NumberFormat.getIntegerInstance();
            str = f.format(mTotalCount);
        }
        
        mCountText.setText(str);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        mContext.getContentResolver().unregisterContentObserver(mSettingsObserver);
        mContext.getContentResolver().unregisterContentObserver(mAccentColorSettingsObserver);
        mHandler.removeCallbacksAndMessages(null);
        
        // Remove keyguard callback
        if (mKeyguardStateController != null && mKeyguardCallback != null) {
            mKeyguardStateController.removeCallback(mKeyguardCallback);
            mKeyguardCallback = null;
        }
        
        // Remove heads-up listener
        if (mHeadsUpManager != null) {
            mHeadsUpManager.removeListener(this);
        }
    }

    public void setParentIconManager(IconManager iconManager) {
        mIconManager = iconManager;
    }
    
    public void setKeyguardStateController(KeyguardStateController keyguardStateController) {
        mKeyguardStateController = keyguardStateController;
        
        if (mKeyguardStateController != null) {
            // Remove old callback if exists
            if (mKeyguardCallback != null) {
                mKeyguardStateController.removeCallback(mKeyguardCallback);
            }
            
            // Add new callback
            mKeyguardCallback = new KeyguardStateController.Callback() {
                @Override
                public void onKeyguardShowingChanged() {
                    boolean isShowing = mKeyguardStateController.isShowing();
                    setForceHidden(isShowing);
                }
            };
            mKeyguardStateController.addCallback(mKeyguardCallback);
            
            // Set initial state
            setForceHidden(mKeyguardStateController.isShowing());
        }
    }
    
    public void setHeadsUpManager(HeadsUpManager headsUpManager) {
        mHeadsUpManager = headsUpManager;
        
        if (mHeadsUpManager != null) {
            mHeadsUpManager.addListener(this);
        }
    }
    
    // OnHeadsUpChangedListener implementation
    @Override
    public void onHeadsUpPinnedModeChanged(boolean inPinnedMode) {
        mHeadsUpPinned = inPinnedMode;
        requestUiUpdate();
    }

    // StatusIconDisplayable implementation
    @Override
    public String getSlot() {
        return mSlot;
    }

    @Override
    public void setStaticDrawableColor(int color) {
        mStaticDrawableColor = color;
        if (mCountText != null) {
            mCountText.setTextColor(getContrastColor(color));
        }
    }

    @Override
    public void setStaticDrawableColor(int tintColor, int contrastColor) {
        mStaticDrawableColor = tintColor;
        if (mCountText != null) {
            mCountText.setTextColor(contrastColor);
        }
    }

    @Override
    public void setDecorColor(int color) {
        mDecorColor = color;
    }

    @Override
    public void setVisibleState(int state) {
        setVisibleState(state, false);
    }

    @Override
    public void setVisibleState(int state, boolean animate) {
        if (mVisibleState != state) {
            mVisibleState = state;
            switch (state) {
                case StatusBarIconView.STATE_ICON:
                    if (mShowCombinedCount && mTotalCount > 0) {
                        setVisibility(VISIBLE);
                    }
                    break;
                case StatusBarIconView.STATE_DOT:
                    if (mShowCombinedCount && mTotalCount > 0) {
                        setVisibility(VISIBLE);
                    }
                    break;
                case StatusBarIconView.STATE_HIDDEN:
                    setVisibility(GONE);
                    break;
            }
        }
    }

    @Override
    public int getVisibleState() {
        return mVisibleState;
    }

    @Override
    public boolean isIconVisible() {
        return mShowCombinedCount && mTotalCount > 0 && getVisibility() == VISIBLE;
    }

    @Override
    public boolean isIconBlocked() {
        return false;
    }

    @Override
    public void onDarkChanged(ArrayList<Rect> areas, float darkIntensity, int tint) {
        // Check if accent color tinting is enabled
        boolean useAccentColor = Settings.System.getIntForUser(
                mContext.getContentResolver(),
                Settings.System.TINT_STATUSBAR_ICONS_WITH_ACCENT,
                0,
                UserHandle.USER_CURRENT) == 1;
        
        int circleColor;
        if (useAccentColor) {
            // Use system accent color
            circleColor = Utils.getColorAccentDefaultColor(mContext);
        } else {
            // Use the tint provided by DarkIconDispatcher
            circleColor = tint;
        }
        
        // Update circle background color
        View circleContainer = findViewById(R.id.circle_container);
        if (circleContainer != null && circleContainer.getBackground() instanceof GradientDrawable) {
            GradientDrawable background = (GradientDrawable) circleContainer.getBackground();
            background.setColor(circleColor);
        }
        
        // Set text to contrasting color
        if (mCountText != null) {
            int textColor = getContrastColor(circleColor);
            mCountText.setTextColor(textColor);
        }
    }
    
    private int getContrastColor(int backgroundColor) {
        // Simple contrast calculation
        int red = Color.red(backgroundColor);
        int green = Color.green(backgroundColor);
        int blue = Color.blue(backgroundColor);
        double luminance = (0.299 * red + 0.587 * green + 0.114 * blue) / 255;
        return luminance > 0.5 ? Color.BLACK : Color.WHITE;
    }
} 
