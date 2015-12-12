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

package com.android.systemui.statusbar.stack;

import android.content.Context;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import com.android.systemui.R;
import com.android.systemui.ViewInvertHelper;
import com.android.systemui.statusbar.ExpandableNotificationRow;
import com.android.systemui.statusbar.notification.HybridNotificationView;
import com.android.systemui.statusbar.notification.HybridNotificationViewManager;
import com.android.systemui.statusbar.phone.NotificationPanelView;

import java.util.ArrayList;
import java.util.List;

/**
 * A container containing child notifications
 */
public class NotificationChildrenContainer extends ViewGroup {

    private static final int NUMBER_OF_CHILDREN_WHEN_COLLAPSED = 2;
    private static final int NUMBER_OF_CHILDREN_WHEN_SYSTEM_EXPANDED = 5;
    private static final int NUMBER_OF_CHILDREN_WHEN_CHILDREN_EXPANDED = 8;

    private final int mChildPadding;
    private final int mDividerHeight;
    private final int mMaxNotificationHeight;
    private final List<View> mDividers = new ArrayList<>();
    private final List<ExpandableNotificationRow> mChildren = new ArrayList<>();
    private final int mNotificationHeaderHeight;
    private final int mNotificationAppearDistance;
    private final int mNotificatonTopPadding;
    private final HybridNotificationViewManager mHybridViewManager;
    private final float mCollapsedBottompadding;
    private ViewInvertHelper mOverflowInvertHelper;
    private boolean mChildrenExpanded;
    private ExpandableNotificationRow mNotificationParent;
    private HybridNotificationView mGroupOverflowContainer;
    private ViewState mGroupOverFlowState;

    public NotificationChildrenContainer(Context context) {
        this(context, null);
    }

    public NotificationChildrenContainer(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public NotificationChildrenContainer(Context context, AttributeSet attrs, int defStyleAttr) {
        this(context, attrs, defStyleAttr, 0);
    }

    public NotificationChildrenContainer(Context context, AttributeSet attrs, int defStyleAttr,
            int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        mChildPadding = getResources().getDimensionPixelSize(
                R.dimen.notification_children_padding);
        mDividerHeight = getResources().getDimensionPixelSize(
                R.dimen.notification_children_divider_height);
        mMaxNotificationHeight = getResources().getDimensionPixelSize(
                R.dimen.notification_max_height);
        mNotificationAppearDistance = getResources().getDimensionPixelSize(
                R.dimen.notification_appear_distance);
        mNotificationHeaderHeight = getResources().getDimensionPixelSize(
                com.android.internal.R.dimen.notification_content_margin_top);
        mNotificatonTopPadding = getResources().getDimensionPixelSize(
                R.dimen.notification_children_container_top_padding);
        mCollapsedBottompadding = 11.5f * getResources().getDisplayMetrics().density;
        mHybridViewManager = new HybridNotificationViewManager(getContext(), this);
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int childCount = Math.min(mChildren.size(), NUMBER_OF_CHILDREN_WHEN_CHILDREN_EXPANDED);
        for (int i = 0; i < childCount; i++) {
            View child = mChildren.get(i);
            if (child.getVisibility() == View.GONE) {
                continue;
            }
            child.layout(0, 0, getWidth(), child.getMeasuredHeight());
            mDividers.get(i).layout(0, 0, getWidth(), mDividerHeight);
        }
        if (mGroupOverflowContainer != null) {
            mGroupOverflowContainer.layout(0, 0, getWidth(),
                    mGroupOverflowContainer.getMeasuredHeight());
        }
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int ownMaxHeight = mMaxNotificationHeight;
        int heightMode = MeasureSpec.getMode(heightMeasureSpec);
        boolean hasFixedHeight = heightMode == MeasureSpec.EXACTLY;
        boolean isHeightLimited = heightMode == MeasureSpec.AT_MOST;
        if (hasFixedHeight || isHeightLimited) {
            int size = MeasureSpec.getSize(heightMeasureSpec);
            ownMaxHeight = Math.min(ownMaxHeight, size);
        }
        int newHeightSpec = MeasureSpec.makeMeasureSpec(ownMaxHeight, MeasureSpec.AT_MOST);
        int dividerHeightSpec = MeasureSpec.makeMeasureSpec(mDividerHeight, MeasureSpec.EXACTLY);
        int height = mNotificationHeaderHeight + mNotificatonTopPadding;
        int childCount = Math.min(mChildren.size(), NUMBER_OF_CHILDREN_WHEN_CHILDREN_EXPANDED);
        for (int i = 0; i < childCount; i++) {
            View child = mChildren.get(i);
            child.measure(widthMeasureSpec, newHeightSpec);
            height += child.getMeasuredHeight();

            // layout the divider
            View divider = mDividers.get(i);
            divider.measure(widthMeasureSpec, dividerHeightSpec);
            height += mDividerHeight;
        }
        int width = MeasureSpec.getSize(widthMeasureSpec);
        if (mGroupOverflowContainer != null) {
            mGroupOverflowContainer.measure(widthMeasureSpec, newHeightSpec);
        }
        setMeasuredDimension(width, height);
    }

    /**
     * Add a child notification to this view.
     *
     * @param row the row to add
     * @param childIndex the index to add it at, if -1 it will be added at the end
     */
    public void addNotification(ExpandableNotificationRow row, int childIndex) {
        int newIndex = childIndex < 0 ? mChildren.size() : childIndex;
        mChildren.add(newIndex, row);
        addView(row);

        View divider = inflateDivider();
        addView(divider);
        mDividers.add(newIndex, divider);

        updateGroupOverflow();
    }

    public void removeNotification(ExpandableNotificationRow row) {
        int childIndex = mChildren.indexOf(row);
        mChildren.remove(row);
        removeView(row);

        View divider = mDividers.remove(childIndex);
        removeView(divider);

        row.setSystemChildExpanded(false);
        updateGroupOverflow();
    }

    public void updateGroupOverflow() {
        int childCount = mChildren.size();
        int maxAllowedVisibleChildren = getMaxAllowedVisibleChildren(true /* likeCollapsed */);
        boolean hasOverflow = childCount > maxAllowedVisibleChildren;
        int lastVisibleIndex = hasOverflow ? maxAllowedVisibleChildren - 2
                : maxAllowedVisibleChildren - 1;
        if (hasOverflow) {
            mGroupOverflowContainer = mHybridViewManager.bindFromNotificationGroup(
                    mGroupOverflowContainer, mChildren, lastVisibleIndex + 1);
            if (mOverflowInvertHelper == null) {
                mOverflowInvertHelper= new ViewInvertHelper(mGroupOverflowContainer,
                        NotificationPanelView.DOZE_ANIMATION_DURATION);
            }
            if (mGroupOverFlowState == null) {
                mGroupOverFlowState = new ViewState();
            }
        } else if (mGroupOverflowContainer != null) {
            removeView(mGroupOverflowContainer);
            mGroupOverflowContainer = null;
            mOverflowInvertHelper = null;
            mGroupOverFlowState = null;
        }
    }

    private View inflateDivider() {
        return LayoutInflater.from(mContext).inflate(
                R.layout.notification_children_divider, this, false);
    }

    public List<ExpandableNotificationRow> getNotificationChildren() {
        return mChildren;
    }

    /**
     * Apply the order given in the list to the children.
     *
     * @param childOrder the new list order
     * @return whether the list order has changed
     */
    public boolean applyChildOrder(List<ExpandableNotificationRow> childOrder) {
        if (childOrder == null) {
            return false;
        }
        boolean result = false;
        for (int i = 0; i < mChildren.size() && i < childOrder.size(); i++) {
            ExpandableNotificationRow child = mChildren.get(i);
            ExpandableNotificationRow desiredChild = childOrder.get(i);
            if (child != desiredChild) {
                mChildren.remove(desiredChild);
                mChildren.add(i, desiredChild);
                result = true;
            }
        }
        updateExpansionStates();
        return result;
    }

    private void updateExpansionStates() {
        // Let's make the first child expanded if the parent is
        for (int i = 0; i < mChildren.size(); i++) {
            ExpandableNotificationRow child = mChildren.get(i);
            child.setSystemChildExpanded(false);
        }
    }

    /**
     *
     * @return the intrinsic size of this children container, i.e the natural fully expanded state
     */
    public int getIntrinsicHeight() {
        int maxAllowedVisibleChildren = getMaxAllowedVisibleChildren();
        return getIntrinsicHeight(maxAllowedVisibleChildren);
    }

    /**
     * @return the intrinsic height with a number of children given
     *         in @param maxAllowedVisibleChildren
     */
    private int getIntrinsicHeight(float maxAllowedVisibleChildren) {
        int intrinsicHeight = mNotificationHeaderHeight;
        if (mChildrenExpanded) {
            intrinsicHeight += mNotificatonTopPadding;
        }
        int visibleChildren = 0;
        int childCount = mChildren.size();
        for (int i = 0; i < childCount; i++) {
            if (visibleChildren >= maxAllowedVisibleChildren) {
                break;
            }
            ExpandableNotificationRow child = mChildren.get(i);
            intrinsicHeight += child.getIntrinsicHeight();
            visibleChildren++;
        }
        if (visibleChildren > 0) {
            if (mChildrenExpanded) {
                intrinsicHeight += visibleChildren * mDividerHeight;
            } else {
                intrinsicHeight += (visibleChildren - 1) * mChildPadding;
            }
        }
        if (!mChildrenExpanded) {
            intrinsicHeight += mCollapsedBottompadding;
        }
        return intrinsicHeight;
    }

    /**
     * Update the state of all its children based on a linear layout algorithm.
     *
     * @param resultState the state to update
     * @param parentState the state of the parent
     */
    public void getState(StackScrollState resultState, StackViewState parentState) {
        int childCount = mChildren.size();
        int yPosition = mNotificationHeaderHeight;
        boolean firstChild = true;
        int maxAllowedVisibleChildren = getMaxAllowedVisibleChildren();
        boolean hasOverflow = !mChildrenExpanded && childCount > maxAllowedVisibleChildren
                && maxAllowedVisibleChildren != NUMBER_OF_CHILDREN_WHEN_CHILDREN_EXPANDED;
        int lastVisibleIndex = hasOverflow
                ? maxAllowedVisibleChildren - 2
                : maxAllowedVisibleChildren - 1;
        for (int i = 0; i < childCount; i++) {
            ExpandableNotificationRow child = mChildren.get(i);
            if (!firstChild) {
                yPosition += mChildrenExpanded ? mDividerHeight : mChildPadding;
            } else {
                yPosition += mChildrenExpanded ? mNotificatonTopPadding + mDividerHeight : 0;
                firstChild = false;
            }
            StackViewState childState = resultState.getViewStateForView(child);
            int intrinsicHeight = child.getIntrinsicHeight();
            childState.yTranslation = yPosition;
            childState.zTranslation = 0;
            childState.height = intrinsicHeight;
            childState.dimmed = parentState.dimmed;
            childState.dark = parentState.dark;
            childState.hideSensitive = parentState.hideSensitive;
            childState.belowSpeedBump = parentState.belowSpeedBump;
            childState.scale =  1.0f;
            childState.clipTopAmount = 0;
            childState.topOverLap = 0;
            boolean visible = i <= lastVisibleIndex;
            childState.alpha = visible ? 1 : 0;
            childState.location = parentState.location;
            yPosition += intrinsicHeight;
        }
        if (mGroupOverflowContainer != null) {
            mGroupOverFlowState.initFrom(mGroupOverflowContainer);
            if (hasOverflow) {
                StackViewState firstOverflowState =
                        resultState.getViewStateForView(mChildren.get(lastVisibleIndex + 1));
                mGroupOverFlowState.yTranslation = firstOverflowState.yTranslation;
            }
            mGroupOverFlowState.alpha = mChildrenExpanded || !hasOverflow ? 0.0f : 1.0f;
        }
    }

    private int getMaxAllowedVisibleChildren() {
        return getMaxAllowedVisibleChildren(false /* likeCollapsed */);
    }

    private int getMaxAllowedVisibleChildren(boolean likeCollapsed) {
        if (!likeCollapsed && (mChildrenExpanded || mNotificationParent.isUserLocked())) {
            return NUMBER_OF_CHILDREN_WHEN_CHILDREN_EXPANDED;
        }
        if (mNotificationParent.isExpanded() || mNotificationParent.isHeadsUp()) {
            return NUMBER_OF_CHILDREN_WHEN_SYSTEM_EXPANDED;
        }
        return NUMBER_OF_CHILDREN_WHEN_COLLAPSED;
    }

    public void applyState(StackScrollState state) {
        int childCount = mChildren.size();
        ViewState tmpState = new ViewState();
        for (int i = 0; i < childCount; i++) {
            ExpandableNotificationRow child = mChildren.get(i);
            StackViewState viewState = state.getViewStateForView(child);
            state.applyState(child, viewState);

            // layout the divider
            View divider = mDividers.get(i);
            tmpState.initFrom(divider);
            tmpState.yTranslation = viewState.yTranslation - mDividerHeight;
            tmpState.alpha = mChildrenExpanded && viewState.alpha != 0 ? 0.5f : 0;
            state.applyViewState(divider, tmpState);
        }
        if (mGroupOverflowContainer != null) {
            state.applyViewState(mGroupOverflowContainer, mGroupOverFlowState);
        }
    }

    /**
     * This is called when the children expansion has changed and positions the children properly
     * for an appear animation.
     *
     * @param state the new state we animate to
     */
    public void prepareExpansionChanged(StackScrollState state) {
        if (true) {
            // TODO: do something that makes sense
            return;
        }
        int childCount = mChildren.size();
        StackViewState sourceState = new StackViewState();
        ViewState dividerState = new ViewState();
        for (int i = 0; i < childCount; i++) {
            ExpandableNotificationRow child = mChildren.get(i);
            StackViewState viewState = state.getViewStateForView(child);
            sourceState.copyFrom(viewState);
            sourceState.alpha = 0;
            sourceState.yTranslation += mNotificationAppearDistance;
            state.applyState(child, sourceState);

            // layout the divider
            View divider = mDividers.get(i);
            dividerState.initFrom(divider);
            dividerState.yTranslation = viewState.yTranslation - mDividerHeight
                    + mNotificationAppearDistance;
            dividerState.alpha = 0;
            state.applyViewState(divider, dividerState);

        }
    }

    public void startAnimationToState(StackScrollState state, StackStateAnimator stateAnimator,
            boolean withDelays, long baseDelay, long duration) {
        int childCount = mChildren.size();
        ViewState tmpState = new ViewState();
        int delayIndex = 0;
        int maxAllowChildCount = getMaxAllowedVisibleChildren(true /* likeCollapsed */);
        for (int i = childCount - 1; i >= 0; i--) {
            ExpandableNotificationRow child = mChildren.get(i);
            StackViewState viewState = state.getViewStateForView(child);
            int difference = Math.min(StackStateAnimator.DELAY_EFFECT_MAX_INDEX_DIFFERENCE_CHILDREN,
                    delayIndex);
            long delay = withDelays
                    ? difference * StackStateAnimator.ANIMATION_DELAY_PER_ELEMENT_EXPAND_CHILDREN
                    : 0;
            delay = (long) (delay * (mChildrenExpanded ? 1.0f : 0.5f) + baseDelay);
            stateAnimator.startStackAnimations(child, viewState, state, -1, delay);

            // layout the divider
            View divider = mDividers.get(i);
            tmpState.initFrom(divider);
            tmpState.yTranslation = viewState.yTranslation - mDividerHeight;
            tmpState.alpha = mChildrenExpanded && viewState.alpha != 0 ? 0.5f : 0;
            stateAnimator.startViewAnimations(divider, tmpState, delay, duration);
            if (i < maxAllowChildCount) {
                delayIndex++;
            }
        }
        if (mGroupOverflowContainer != null) {
            stateAnimator.startViewAnimations(mGroupOverflowContainer, mGroupOverFlowState,
                    baseDelay, duration);
        }
    }

    public ExpandableNotificationRow getViewAtPosition(float y) {
        // find the view under the pointer, accounting for GONE views
        final int count = mChildren.size();
        for (int childIdx = 0; childIdx < count; childIdx++) {
            ExpandableNotificationRow slidingChild = mChildren.get(childIdx);
            float childTop = slidingChild.getTranslationY();
            float top = childTop + slidingChild.getClipTopAmount();
            float bottom = childTop + slidingChild.getActualHeight();
            if (y >= top && y <= bottom) {
                return slidingChild;
            }
        }
        return null;
    }

    public void setChildrenExpanded(boolean childrenExpanded) {
        mChildrenExpanded = childrenExpanded;
    }

    public void setNotificationParent(ExpandableNotificationRow parent) {
        mNotificationParent = parent;
    }

    public int getMaxContentHeight() {
        return getIntrinsicHeight(NUMBER_OF_CHILDREN_WHEN_CHILDREN_EXPANDED);
    }

    public int getMinHeight() {
        return getIntrinsicHeight(getMaxAllowedVisibleChildren(true /* forceCollapsed */));
    }

    public void setDark(boolean dark, boolean fade, long delay) {
        if (mGroupOverflowContainer != null) {
            mOverflowInvertHelper.setInverted(dark, fade, delay);
        }
    }
}
