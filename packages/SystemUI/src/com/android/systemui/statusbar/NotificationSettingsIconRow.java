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

package com.android.systemui.statusbar;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.widget.FrameLayout;

import com.android.systemui.Interpolators;
import com.android.systemui.R;

public class NotificationSettingsIconRow extends FrameLayout implements View.OnClickListener {

    public interface SettingsIconRowListener {
        /**
         * Called when the gear behind a notification is touched.
         */
        public void onGearTouched(ExpandableNotificationRow row);
    }

    private ExpandableNotificationRow mParent;
    private AlphaOptimizedImageView mGearIcon;
    private float mHorizSpaceForGear;
    private SettingsIconRowListener mListener;

    private ValueAnimator mFadeAnimator;
    private boolean mSettingsFadedIn = false;
    private boolean mAnimating = false;
    private boolean mOnLeft = true;

    public NotificationSettingsIconRow(Context context) {
        this(context, null);
    }

    public NotificationSettingsIconRow(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public NotificationSettingsIconRow(Context context, AttributeSet attrs, int defStyleAttr) {
        this(context, attrs, defStyleAttr, 0);
    }

    public NotificationSettingsIconRow(Context context, AttributeSet attrs, int defStyleAttr,
            int defStyleRes) {
        super(context, attrs);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        mGearIcon = (AlphaOptimizedImageView) findViewById(R.id.gear_icon);
        mGearIcon.setOnClickListener(this);
        setOnClickListener(this);
        mHorizSpaceForGear =
                getResources().getDimensionPixelOffset(R.dimen.notification_gear_width);
        resetState();
    }

    public void setGearListener(SettingsIconRowListener listener) {
        mListener = listener;
    }

    public void setNotificationRowParent(ExpandableNotificationRow parent) {
        mParent = parent;
    }

    public ExpandableNotificationRow getNotificationParent() {
        return mParent;
    }

    public void resetState() {
        setGearAlpha(0f);
        mAnimating = false;
        setIconLocation(true /* on left */);
    }

    private void setGearAlpha(float alpha) {
        if (alpha == 0) {
            mSettingsFadedIn = false; // Can fade in again once it's gone.
            mGearIcon.setVisibility(View.INVISIBLE);
        } else {
            if (alpha == 1) {
                mSettingsFadedIn = true;
            }
            mGearIcon.setVisibility(View.VISIBLE);
        }
        mGearIcon.setAlpha(alpha);
    }

    /**
     * Returns the horizontal space in pixels required to display the gear behind a notification.
     */
    public float getSpaceForGear() {
        return mHorizSpaceForGear;
    }

    /**
     * Indicates whether the gear is visible at 1 alpha. Does not indicate
     * if entire view is visible.
     */
    public boolean isVisible() {
        return mSettingsFadedIn;
    }

    public void cancelFadeAnimator() {
        if (mFadeAnimator != null) {
            mFadeAnimator.cancel();
        }
    }

    public void updateSettingsIcons(final float transX, final float size) {
        if (mAnimating || (mGearIcon.getAlpha() == 0)) {
            // Don't adjust when animating or settings aren't visible
            return;
        }
        setIconLocation(transX > 0 /* fromLeft */);
        final float fadeThreshold = size * 0.3f;
        final float absTrans = Math.abs(transX);
        float desiredAlpha = 0;

        if (absTrans <= fadeThreshold) {
            desiredAlpha = 1;
        } else {
            desiredAlpha = 1 - ((absTrans - fadeThreshold) / (size - fadeThreshold));
        }
        setGearAlpha(desiredAlpha);
    }

    public void fadeInSettings(final boolean fromLeft, final float transX,
            final float notiThreshold) {
        setIconLocation(transX > 0 /* fromLeft */);
        mFadeAnimator = ValueAnimator.ofFloat(mGearIcon.getAlpha(), 1);
        mFadeAnimator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator animation) {
                final float absTrans = Math.abs(transX);

                boolean pastGear = (fromLeft && transX <= notiThreshold)
                        || (!fromLeft && absTrans <= notiThreshold);
                if (pastGear && !mSettingsFadedIn) {
                    setGearAlpha((float) animation.getAnimatedValue());
                }
            }
        });
        mFadeAnimator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationCancel(Animator animation) {
                super.onAnimationCancel(animation);
                mAnimating = false;
                mSettingsFadedIn = false;
            }

            @Override
            public void onAnimationStart(Animator animation) {
                super.onAnimationStart(animation);
                mAnimating = true;
            }

            @Override
            public void onAnimationEnd(Animator animation) {
                super.onAnimationEnd(animation);
                mAnimating = false;
                mSettingsFadedIn = true;
            }
        });
        mFadeAnimator.setInterpolator(Interpolators.ALPHA_IN);
        mFadeAnimator.setDuration(200);
        mFadeAnimator.start();
    }

    private void setIconLocation(boolean onLeft) {
        if (onLeft == mOnLeft) {
            // Same side? Do nothing.
            return;
        }

        setTranslationX(onLeft ? 0 : (mParent.getWidth() - mHorizSpaceForGear));
        mOnLeft = onLeft;
    }

    @Override
    public void onClick(View v) {
        if (v.getId() == R.id.gear_icon) {
            if (mListener != null) {
                mListener.onGearTouched(mParent);
            }
        } else {
            // Do nothing when the background is touched.
        }
    }
}
