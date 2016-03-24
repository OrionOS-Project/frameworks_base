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

package com.android.systemui.recents.views;

import android.annotation.IntDef;
import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Path;
import android.graphics.Rect;
import android.util.ArraySet;
import android.util.SparseArray;
import android.util.SparseIntArray;
import android.view.ViewDebug;

import com.android.systemui.R;
import com.android.systemui.recents.Recents;
import com.android.systemui.recents.RecentsActivityLaunchState;
import com.android.systemui.recents.RecentsConfiguration;
import com.android.systemui.recents.RecentsDebugFlags;
import com.android.systemui.recents.misc.FreePathInterpolator;
import com.android.systemui.recents.misc.SystemServicesProxy;
import com.android.systemui.recents.misc.Utilities;
import com.android.systemui.recents.model.Task;
import com.android.systemui.recents.model.TaskStack;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.List;

/**
 * Used to describe a visible range that can be normalized to [0, 1].
 */
class Range {
    final float relativeMin;
    final float relativeMax;
    float origin;
    float min;
    float max;

    public Range(float relMin, float relMax) {
        min = relativeMin = relMin;
        max = relativeMax = relMax;
    }

    /**
     * Offsets this range to a given absolute position.
     */
    public void offset(float x) {
        this.origin = x;
        min = x + relativeMin;
        max = x + relativeMax;
    }

    /**
     * Returns x normalized to the range 0 to 1 such that 0 = min, 0.5 = origin and 1 = max
     *
     * @param x is an absolute value in the same domain as origin
     */
    public float getNormalizedX(float x) {
        if (x < origin) {
            return 0.5f + 0.5f * (x - origin) / -relativeMin;
        } else {
            return 0.5f + 0.5f * (x - origin) / relativeMax;
        }
    }

    /**
     * Given a normalized {@param x} value in this range, projected onto the full range to get an
     * absolute value about the given {@param origin}.
     */
    public float getAbsoluteX(float normX) {
        if (normX < 0.5f) {
            return (normX - 0.5f) / 0.5f * -relativeMin;
        } else {
            return (normX - 0.5f) / 0.5f * relativeMax;
        }
    }

    /**
     * Returns whether a value at an absolute x would be within range.
     */
    public boolean isInRange(float absX) {
        return (absX >= Math.floor(min)) && (absX <= Math.ceil(max));
    }
}

/**
 * The layout logic for a TaskStackView.  This layout needs to be able to calculate the stack layout
 * without an activity-specific context only with the information passed in.  This layout can have
 * two states focused and unfocused, and in the focused state, there is a task that is displayed
 * more prominently in the stack.
 */
public class TaskStackLayoutAlgorithm {

    // The distribution of view bounds alpha
    // XXX: This is a hack because you can currently set the max alpha to be > 1f
    public static final float OUTLINE_ALPHA_MIN_VALUE = 0f;
    public static final float OUTLINE_ALPHA_MAX_VALUE = 2f;

    // The medium/maximum dim on the tasks
    private static final float MED_DIM = 0.15f;
    private static final float MAX_DIM = 0.25f;

    // The various focus states
    public static final int STATE_FOCUSED = 1;
    public static final int STATE_UNFOCUSED = 0;

    // The side that an offset is anchored
    @Retention(RetentionPolicy.SOURCE)
    @IntDef({FROM_TOP, FROM_BOTTOM})
    public @interface AnchorSide {}
    private static final int FROM_TOP = 0;
    private static final int FROM_BOTTOM = 1;

    // The extent that we care about when calculating fractions
    @Retention(RetentionPolicy.SOURCE)
    @IntDef({WIDTH, HEIGHT})
    public @interface Extent {}
    private static final int WIDTH = 0;
    private static final int HEIGHT = 1;

    public interface TaskStackLayoutAlgorithmCallbacks {
        void onFocusStateChanged(int prevFocusState, int curFocusState);
    }

    /**
     * The various stack/freeform states.
     */
    public static class StackState {

        public static final StackState FREEFORM_ONLY = new StackState(1f, 255);
        public static final StackState STACK_ONLY = new StackState(0f, 0);
        public static final StackState SPLIT = new StackState(0.5f, 255);

        public final float freeformHeightPct;
        public final int freeformBackgroundAlpha;

        /**
         * @param freeformHeightPct the percentage of the stack height (not including paddings) to
         *                          allocate to the freeform workspace
         * @param freeformBackgroundAlpha the background alpha for the freeform workspace
         */
        private StackState(float freeformHeightPct, int freeformBackgroundAlpha) {
            this.freeformHeightPct = freeformHeightPct;
            this.freeformBackgroundAlpha = freeformBackgroundAlpha;
        }

        /**
         * Resolves the stack state for the layout given a task stack.
         */
        public static StackState getStackStateForStack(TaskStack stack) {
            SystemServicesProxy ssp = Recents.getSystemServices();
            boolean hasFreeformWorkspaces = ssp.hasFreeformWorkspaceSupport();
            int freeformCount = stack.getFreeformTaskCount();
            int stackCount = stack.getStackTaskCount();
            if (hasFreeformWorkspaces && stackCount > 0 && freeformCount > 0) {
                return SPLIT;
            } else if (hasFreeformWorkspaces && freeformCount > 0) {
                return FREEFORM_ONLY;
            } else {
                return STACK_ONLY;
            }
        }

        /**
         * Computes the freeform and stack rect for this state.
         *
         * @param freeformRectOut the freeform rect to be written out
         * @param stackRectOut the stack rect, we only write out the top of the stack
         * @param taskStackBounds the full rect that the freeform rect can take up
         */
        public void computeRects(Rect freeformRectOut, Rect stackRectOut,
                Rect taskStackBounds, int topMargin, int freeformGap, int stackBottomOffset) {
            // The freeform height is the visible height (not including system insets) - padding
            // above freeform and below stack - gap between the freeform and stack
            int availableHeight = taskStackBounds.height() - topMargin - stackBottomOffset;
            int ffPaddedHeight = (int) (availableHeight * freeformHeightPct);
            int ffHeight = Math.max(0, ffPaddedHeight - freeformGap);
            freeformRectOut.set(taskStackBounds.left,
                    taskStackBounds.top + topMargin,
                    taskStackBounds.right,
                    taskStackBounds.top + topMargin + ffHeight);
            stackRectOut.set(taskStackBounds.left,
                    taskStackBounds.top,
                    taskStackBounds.right,
                    taskStackBounds.bottom);
            if (ffPaddedHeight > 0) {
                stackRectOut.top += ffPaddedHeight;
            } else {
                stackRectOut.top += topMargin;
            }
        }
    }

    // A report of the visibility state of the stack
    public class VisibilityReport {
        public int numVisibleTasks;
        public int numVisibleThumbnails;

        /** Package level ctor */
        VisibilityReport(int tasks, int thumbnails) {
            numVisibleTasks = tasks;
            numVisibleThumbnails = thumbnails;
        }
    }

    Context mContext;
    private StackState mState = StackState.SPLIT;
    private TaskStackLayoutAlgorithmCallbacks mCb;

    // The task bounds (untransformed) for layout.  This rect is anchored at mTaskRoot.
    @ViewDebug.ExportedProperty(category="recents")
    public Rect mTaskRect = new Rect();
    // The freeform workspace bounds, inset by the top system insets and is a fixed height
    @ViewDebug.ExportedProperty(category="recents")
    public Rect mFreeformRect = new Rect();
    // The stack bounds, inset from the top system insets, and runs to the bottom of the screen
    @ViewDebug.ExportedProperty(category="recents")
    public Rect mStackRect = new Rect();
    // This is the current system insets
    @ViewDebug.ExportedProperty(category="recents")
    public Rect mSystemInsets = new Rect();
    // This is the bounds of the history button above the stack rect
    @ViewDebug.ExportedProperty(category="recents")
    public Rect mHistoryButtonRect = new Rect();

    // The visible ranges when the stack is focused and unfocused
    private Range mUnfocusedRange;
    private Range mFocusedRange;

    // The base top margin for the stack from the system insets
    @ViewDebug.ExportedProperty(category="recents")
    private int mBaseTopMargin;
    // The base side margin for the stack from the system insets
    @ViewDebug.ExportedProperty(category="recents")
    private int mBaseSideMargin;
    // The base bottom margin for the stack from the system insets
    @ViewDebug.ExportedProperty(category="recents")
    private int mBaseBottomMargin;
    private int mMinMargin;

    // The gap between the freeform and stack layouts
    @ViewDebug.ExportedProperty(category="recents")
    private int mFreeformStackGap;

    // The initial offset that the focused task is from the top
    @ViewDebug.ExportedProperty(category="recents")
    private int mInitialTopOffset;
    private int mBaseInitialTopOffset;
    // The initial offset that the launch-from task is from the bottom
    @ViewDebug.ExportedProperty(category="recents")
    private int mInitialBottomOffset;
    private int mBaseInitialBottomOffset;

    // The height between the top margin and the top of the focused task
    @ViewDebug.ExportedProperty(category="recents")
    private int mFocusedTopPeekHeight;
    // The height between the bottom margin and the top of task in front of the focused task
    @ViewDebug.ExportedProperty(category="recents")
    private int mFocusedBottomPeekHeight;

    // The offset from the bottom of the stack to the bottom of the bounds when the stack is
    // scrolled to the front
    @ViewDebug.ExportedProperty(category="recents")
    private int mStackBottomOffset;

    // The paths defining the motion of the tasks when the stack is focused and unfocused
    private Path mUnfocusedCurve;
    private Path mFocusedCurve;
    private FreePathInterpolator mUnfocusedCurveInterpolator;
    private FreePathInterpolator mFocusedCurveInterpolator;

    // The paths defining the distribution of the dim to apply to tasks in the stack when focused
    // and unfocused
    private Path mUnfocusedDimCurve;
    private Path mFocusedDimCurve;
    private FreePathInterpolator mUnfocusedDimCurveInterpolator;
    private FreePathInterpolator mFocusedDimCurveInterpolator;

    // Indexed from the front of the stack, the normalized x in the unfocused range for each task
    private float[] mInitialNormX;

    // The state of the stack focus (0..1), which controls the transition of the stack from the
    // focused to non-focused state
    @ViewDebug.ExportedProperty(category="recents")
    private int mFocusState;

    // The smallest scroll progress, at this value, the back most task will be visible
    @ViewDebug.ExportedProperty(category="recents")
    float mMinScrollP;
    // The largest scroll progress, at this value, the front most task will be visible above the
    // navigation bar
    @ViewDebug.ExportedProperty(category="recents")
    float mMaxScrollP;
    // The initial progress that the scroller is set when you first enter recents
    @ViewDebug.ExportedProperty(category="recents")
    float mInitialScrollP;
    // The task progress for the front-most task in the stack
    @ViewDebug.ExportedProperty(category="recents")
    float mFrontMostTaskP;

    // The last computed task counts
    @ViewDebug.ExportedProperty(category="recents")
    int mNumStackTasks;
    @ViewDebug.ExportedProperty(category="recents")
    int mNumFreeformTasks;

    // The min/max z translations
    @ViewDebug.ExportedProperty(category="recents")
    int mMinTranslationZ;
    @ViewDebug.ExportedProperty(category="recents")
    int mMaxTranslationZ;

    // Optimization, allows for quick lookup of task -> index
    private SparseIntArray mTaskIndexMap = new SparseIntArray();
    private SparseArray<Float> mTaskIndexOverrideMap = new SparseArray<>();

    // The freeform workspace layout
    FreeformWorkspaceLayoutAlgorithm mFreeformLayoutAlgorithm;

    // The transform to place TaskViews at the front and back of the stack respectively
    TaskViewTransform mBackOfStackTransform = new TaskViewTransform();
    TaskViewTransform mFrontOfStackTransform = new TaskViewTransform();

    public TaskStackLayoutAlgorithm(Context context, TaskStackLayoutAlgorithmCallbacks cb) {
        Resources res = context.getResources();
        mContext = context;
        mCb = cb;
        mFreeformLayoutAlgorithm = new FreeformWorkspaceLayoutAlgorithm(context);
        mMinMargin = res.getDimensionPixelSize(R.dimen.recents_layout_min_margin);
        mBaseTopMargin = getDimensionForDevice(res,
                R.dimen.recents_layout_top_margin_phone,
                R.dimen.recents_layout_top_margin_tablet,
                R.dimen.recents_layout_top_margin_tablet_xlarge);
        mBaseSideMargin = getDimensionForDevice(res,
                R.dimen.recents_layout_side_margin_phone,
                R.dimen.recents_layout_side_margin_tablet,
                R.dimen.recents_layout_side_margin_tablet_xlarge);
        mBaseBottomMargin = res.getDimensionPixelSize(R.dimen.recents_layout_bottom_margin);
        mFreeformStackGap =
                res.getDimensionPixelSize(R.dimen.recents_freeform_layout_bottom_margin);

        reloadOnConfigurationChange(context);
    }

    /**
     * Reloads the layout for the current configuration.
     */
    public void reloadOnConfigurationChange(Context context) {
        Resources res = context.getResources();
        mFocusedRange = new Range(res.getFloat(R.integer.recents_layout_focused_range_min),
                res.getFloat(R.integer.recents_layout_focused_range_max));
        mUnfocusedRange = new Range(res.getFloat(R.integer.recents_layout_unfocused_range_min),
                res.getFloat(R.integer.recents_layout_unfocused_range_max));
        mFocusState = getInitialFocusState();
        mFocusedTopPeekHeight = res.getDimensionPixelSize(R.dimen.recents_layout_top_peek_size);
        mFocusedBottomPeekHeight =
                res.getDimensionPixelSize(R.dimen.recents_layout_bottom_peek_size);
        mMinTranslationZ = res.getDimensionPixelSize(R.dimen.recents_layout_z_min);
        mMaxTranslationZ = res.getDimensionPixelSize(R.dimen.recents_layout_z_max);
        mBaseInitialTopOffset = getDimensionForDevice(res,
                R.dimen.recents_layout_initial_top_offset_phone_port,
                R.dimen.recents_layout_initial_top_offset_phone_land,
                R.dimen.recents_layout_initial_top_offset_tablet,
                R.dimen.recents_layout_initial_top_offset_tablet,
                R.dimen.recents_layout_initial_top_offset_tablet,
                R.dimen.recents_layout_initial_top_offset_tablet);
        mBaseInitialBottomOffset = getDimensionForDevice(res,
                R.dimen.recents_layout_initial_bottom_offset_phone_port,
                R.dimen.recents_layout_initial_bottom_offset_phone_land,
                R.dimen.recents_layout_initial_bottom_offset_tablet,
                R.dimen.recents_layout_initial_bottom_offset_tablet,
                R.dimen.recents_layout_initial_bottom_offset_tablet,
                R.dimen.recents_layout_initial_bottom_offset_tablet);
        mFreeformLayoutAlgorithm.reloadOnConfigurationChange(context);
    }

    /**
     * Resets this layout when the stack view is reset.
     */
    public void reset() {
        mTaskIndexOverrideMap.clear();
        setFocusState(getInitialFocusState());
    }

    /**
     * Sets the system insets.
     */
    public void setSystemInsets(Rect systemInsets) {
        mSystemInsets.set(systemInsets);
    }

    /**
     * Sets the focused state.
     */
    public void setFocusState(int focusState) {
        int prevFocusState = mFocusState;
        mFocusState = focusState;
        updateFrontBackTransforms();
        if (mCb != null) {
            mCb.onFocusStateChanged(prevFocusState, focusState);
        }
    }

    /**
     * Gets the focused state.
     */
    public int getFocusState() {
        return mFocusState;
    }

    /**
     * Computes the stack and task rects.  The given task stack bounds already has the top/right
     * insets and left/right padding already applied.
     */
    public void initialize(Rect windowRect, Rect taskStackBounds, StackState state) {
        SystemServicesProxy ssp = Recents.getSystemServices();
        Rect lastStackRect = new Rect(mStackRect);
        Rect displayRect = ssp.getDisplayRect();

        int topMargin = getScaleForExtent(windowRect, displayRect, mBaseTopMargin, mMinMargin, HEIGHT);
        int bottomMargin = getScaleForExtent(windowRect, displayRect, mBaseBottomMargin, mMinMargin,
                HEIGHT);
        mInitialTopOffset = getScaleForExtent(windowRect, displayRect, mBaseInitialTopOffset,
                mMinMargin, HEIGHT);
        mInitialBottomOffset = mBaseInitialBottomOffset;

        // Compute the stack bounds
        mState = state;
        mStackBottomOffset = mSystemInsets.bottom + bottomMargin;
        state.computeRects(mFreeformRect, mStackRect, taskStackBounds, topMargin,
                mFreeformStackGap, mStackBottomOffset);

        // The history button will take the full un-padded header space above the stack
        mHistoryButtonRect.set(mStackRect.left, mStackRect.top - topMargin,
                mStackRect.right, mStackRect.top + mFocusedTopPeekHeight);

        // Anchor the task rect top aligned to the non-freeform stack rect
        float aspect = (float) (windowRect.width() - (mSystemInsets.left + mSystemInsets.right)) /
                (windowRect.height() - (mSystemInsets.top + mSystemInsets.bottom));
        int minHeight = mStackRect.height() - mInitialTopOffset - mStackBottomOffset;
        int height = (int) Math.min(mStackRect.width() / aspect, minHeight);
        mTaskRect.set(mStackRect.left, mStackRect.top, mStackRect.right, mStackRect.top + height);

        // Short circuit here if the stack rects haven't changed so we don't do all the work below
        if (!lastStackRect.equals(mStackRect)) {
            // Reinitialize the focused and unfocused curves
            mUnfocusedCurve = constructUnfocusedCurve();
            mUnfocusedCurveInterpolator = new FreePathInterpolator(mUnfocusedCurve);
            mFocusedCurve = constructFocusedCurve();
            mFocusedCurveInterpolator = new FreePathInterpolator(mFocusedCurve);
            mUnfocusedDimCurve = constructUnfocusedDimCurve();
            mUnfocusedDimCurveInterpolator = new FreePathInterpolator(mUnfocusedDimCurve);
            mFocusedDimCurve = constructFocusedDimCurve();
            mFocusedDimCurveInterpolator = new FreePathInterpolator(mFocusedDimCurve);

            updateFrontBackTransforms();
        }
    }

    /**
     * Computes the minimum and maximum scroll progress values and the progress values for each task
     * in the stack.
     */
    void update(TaskStack stack, ArraySet<Task.TaskKey> ignoreTasksSet) {
        SystemServicesProxy ssp = Recents.getSystemServices();
        RecentsActivityLaunchState launchState = Recents.getConfiguration().getLaunchState();

        // Clear the progress map
        mTaskIndexMap.clear();

        // Return early if we have no tasks
        ArrayList<Task> tasks = stack.getStackTasks();
        if (tasks.isEmpty()) {
            mFrontMostTaskP = 0;
            mMinScrollP = mMaxScrollP = mInitialScrollP = 0;
            mNumStackTasks = mNumFreeformTasks = 0;
            return;
        }

        // Filter the set of freeform and stack tasks
        ArrayList<Task> freeformTasks = new ArrayList<>();
        ArrayList<Task> stackTasks = new ArrayList<>();
        for (int i = 0; i < tasks.size(); i++) {
            Task task = tasks.get(i);
            if (ignoreTasksSet.contains(task.key)) {
                continue;
            }
            if (task.isFreeformTask()) {
                freeformTasks.add(task);
            } else {
                stackTasks.add(task);
            }
        }
        mNumStackTasks = stackTasks.size();
        mNumFreeformTasks = freeformTasks.size();

        // Put each of the tasks in the progress map at a fixed index (does not need to actually
        // map to a scroll position, just by index)
        int taskCount = stackTasks.size();
        for (int i = 0; i < taskCount; i++) {
            Task task = stackTasks.get(i);
            mTaskIndexMap.put(task.key.id, i);
        }

        // Update the freeform tasks
        if (!freeformTasks.isEmpty()) {
            mFreeformLayoutAlgorithm.update(freeformTasks, this);
        }

        // Calculate the min/max/initial scroll
        Task launchTask = stack.getLaunchTarget();
        int launchTaskIndex = launchTask != null
                ? stack.indexOfStackTask(launchTask)
                : mNumStackTasks - 1;
        if (getInitialFocusState() == STATE_FOCUSED) {
            mMinScrollP = 0;
            mMaxScrollP = Math.max(mMinScrollP, mNumStackTasks - 1);
            if (launchState.launchedFromHome) {
                mInitialScrollP = Utilities.clamp(launchTaskIndex, mMinScrollP, mMaxScrollP);
            } else {
                mInitialScrollP = Utilities.clamp(launchTaskIndex - 1, mMinScrollP, mMaxScrollP);
            }
        } else if (!ssp.hasFreeformWorkspaceSupport() && mNumStackTasks == 1) {
            // If there is one stack task, ignore the min/max/initial scroll positions
            mMinScrollP = 0;
            mMaxScrollP = 0;
            mInitialScrollP = 0;
        } else {
            // Set the max scroll to be the point where the front most task is visible with the
            // stack bottom offset
            int maxBottomOffset = mStackBottomOffset + mTaskRect.height();
            float maxBottomNormX = getNormalizedXFromUnfocusedY(maxBottomOffset, FROM_BOTTOM);
            mUnfocusedRange.offset(0f);
            mMinScrollP = 0;
            mMaxScrollP = Math.max(mMinScrollP, (mNumStackTasks - 1) -
                    Math.max(0, mUnfocusedRange.getAbsoluteX(maxBottomNormX)));
            boolean scrollToFront = launchState.launchedFromHome ||
                    launchState.launchedFromAppDocked;
            if (scrollToFront) {
                mInitialScrollP = Utilities.clamp(launchTaskIndex, mMinScrollP, mMaxScrollP);
                mInitialNormX = null;
            } else {
                float normX = getNormalizedXFromUnfocusedY(mInitialTopOffset, FROM_TOP);
                mInitialScrollP = Math.max(mMinScrollP, Math.min(mMaxScrollP, (mNumStackTasks - 2)) -
                        Math.max(0, mUnfocusedRange.getAbsoluteX(normX)));

                // Set the initial scroll to the predefined state (which differs from the stack)
                mInitialNormX = new float[] {
                        getNormalizedXFromUnfocusedY(mSystemInsets.bottom + mInitialBottomOffset,
                                FROM_BOTTOM),
                        normX
                };
            }
        }
    }

    public void updateToInitialState(List<Task> tasks) {
        if (mInitialNormX == null) {
            return;
        }

        mUnfocusedRange.offset(0f);
        int taskCount = tasks.size();
        for (int i = taskCount - 1; i >= 0; i--) {
            int indexFromFront = taskCount - i - 1;
            if (indexFromFront >= mInitialNormX.length) {
                break;
            }
            float newTaskProgress = mInitialScrollP +
                    mUnfocusedRange.getAbsoluteX(mInitialNormX[indexFromFront]);
            mTaskIndexOverrideMap.put(tasks.get(i).key.id, newTaskProgress);
        }
    }

    /**
     * Adds and override task progress for the given task when transitioning from focused to
     * unfocused state.
     */
    public void addUnfocusedTaskOverride(Task task, float stackScroll) {
        if (mFocusState != STATE_UNFOCUSED) {
            mFocusedRange.offset(stackScroll);
            mUnfocusedRange.offset(stackScroll);
            float focusedRangeX = mFocusedRange.getNormalizedX(mTaskIndexMap.get(task.key.id));
            float focusedY = mFocusedCurveInterpolator.getInterpolation(focusedRangeX);
            float unfocusedRangeX = mUnfocusedCurveInterpolator.getX(focusedY);
            float unfocusedTaskProgress = stackScroll + mUnfocusedRange.getAbsoluteX(unfocusedRangeX);
            if (Float.compare(focusedRangeX, unfocusedRangeX) != 0) {
                mTaskIndexOverrideMap.put(task.key.id, unfocusedTaskProgress);
            }
        }
    }

    public void clearUnfocusedTaskOverrides() {
        mTaskIndexOverrideMap.clear();
    }

    /**
     * Updates this stack when a scroll happens.
     */
    public void updateFocusStateOnScroll(float stackScroll, float deltaScroll) {
        if (deltaScroll == 0f) {
            return;
        }

        for (int i = mTaskIndexOverrideMap.size() - 1; i >= 0; i--) {
            int taskId = mTaskIndexOverrideMap.keyAt(i);
            float x = mTaskIndexMap.get(taskId);
            float overrideX = mTaskIndexOverrideMap.get(taskId, 0f);
            float newOverrideX = overrideX + deltaScroll;
            mUnfocusedRange.offset(stackScroll);
            boolean outOfBounds = mUnfocusedRange.getNormalizedX(newOverrideX) < 0f ||
                    mUnfocusedRange.getNormalizedX(newOverrideX) > 1f;
            if (outOfBounds || (overrideX >= x && x >= newOverrideX) ||
                    (overrideX <= x && x <= newOverrideX)) {
                // Remove the override once we reach the original task index
                mTaskIndexOverrideMap.removeAt(i);
            } else if ((overrideX >= x && deltaScroll <= 0f) ||
                    (overrideX <= x && deltaScroll >= 0f)) {
                // Scrolling from override x towards x, then lock the task in place
                mTaskIndexOverrideMap.put(taskId, newOverrideX);
            } else {
                // Scrolling override x away from x, we should still move the scroll towards x
                float deltaX = overrideX - x;
                newOverrideX = Math.signum(deltaX) * (Math.abs(deltaX) - Math.abs(deltaScroll));
                mTaskIndexOverrideMap.put(taskId, x + newOverrideX);
            }
        }
    }

    /**
     * Returns the default focus state.
     */
    public int getInitialFocusState() {
        RecentsDebugFlags debugFlags = Recents.getDebugFlags();
        if (debugFlags.isPagingEnabled()) {
            return STATE_FOCUSED;
        } else {
            return STATE_UNFOCUSED;
        }
    }

    /**
     * Returns the TaskViewTransform that would put the task just off the back of the stack.
     */
    public TaskViewTransform getBackOfStackTransform() {
        return mBackOfStackTransform;
    }

    /**
     * Returns the TaskViewTransform that would put the task just off the front of the stack.
     */
    public TaskViewTransform getFrontOfStackTransform() {
        return mFrontOfStackTransform;
    }

    /**
     *
     * Returns the current stack state.
     */
    public StackState getStackState() {
        return mState;
    }

    /**
     * Returns whether this stack layout has been initialized.
     */
    public boolean isInitialized() {
        return !mStackRect.isEmpty();
    }

    /**
     * Computes the maximum number of visible tasks and thumbnails when the scroll is at the initial
     * stack scroll.  Requires that update() is called first.
     */
    public VisibilityReport computeStackVisibilityReport(ArrayList<Task> tasks) {
        // Ensure minimum visibility count
        if (tasks.size() <= 1) {
            return new VisibilityReport(1, 1);
        }

        // Quick return when there are no stack tasks
        if (mNumStackTasks == 0) {
            return new VisibilityReport(Math.max(mNumFreeformTasks, 1),
                    Math.max(mNumFreeformTasks, 1));
        }

        // Otherwise, walk backwards in the stack and count the number of tasks and visible
        // thumbnails and add that to the total freeform task count
        TaskViewTransform tmpTransform = new TaskViewTransform();
        Range currentRange = getInitialFocusState() > 0f ? mFocusedRange : mUnfocusedRange;
        currentRange.offset(mInitialScrollP);
        int taskBarHeight = mContext.getResources().getDimensionPixelSize(
                R.dimen.recents_task_view_header_height);
        int numVisibleTasks = Math.max(mNumFreeformTasks, 1);
        int numVisibleThumbnails = Math.max(mNumFreeformTasks, 1);
        float prevScreenY = Integer.MAX_VALUE;
        for (int i = tasks.size() - 1; i >= 0; i--) {
            Task task = tasks.get(i);

            // Skip freeform
            if (task.isFreeformTask()) {
                continue;
            }

            // Skip invisible
            float taskProgress = getStackScrollForTask(task);
            if (!currentRange.isInRange(taskProgress)) {
                continue;
            }

            boolean isFrontMostTaskInGroup = task.group == null || task.group.isFrontMostTask(task);
            if (isFrontMostTaskInGroup) {
                getStackTransform(taskProgress, taskProgress, mInitialScrollP, mFocusState,
                        tmpTransform, null, false /* ignoreSingleTaskCase */,
                        false /* forceUpdate */);
                float screenY = tmpTransform.rect.top;
                boolean hasVisibleThumbnail = (prevScreenY - screenY) > taskBarHeight;
                if (hasVisibleThumbnail) {
                    numVisibleThumbnails++;
                    numVisibleTasks++;
                    prevScreenY = screenY;
                } else {
                    // Once we hit the next front most task that does not have a visible thumbnail,
                    // walk through remaining visible set
                    for (int j = i; j >= 0; j--) {
                        numVisibleTasks++;
                        taskProgress = getStackScrollForTask(tasks.get(j));
                        if (!currentRange.isInRange(taskProgress)) {
                            continue;
                        }
                    }
                    break;
                }
            } else if (!isFrontMostTaskInGroup) {
                // Affiliated task, no thumbnail
                numVisibleTasks++;
            }
        }
        return new VisibilityReport(numVisibleTasks, numVisibleThumbnails);
    }

    /**
     * Returns the transform for the given task.  This transform is relative to the mTaskRect, which
     * is what the view is measured and laid out with.
     */
    public TaskViewTransform getStackTransform(Task task, float stackScroll,
            TaskViewTransform transformOut, TaskViewTransform frontTransform) {
        return getStackTransform(task, stackScroll, mFocusState, transformOut, frontTransform,
                false /* forceUpdate */, false /* ignoreTaskOverrides */);
    }

    public TaskViewTransform getStackTransform(Task task, float stackScroll,
            TaskViewTransform transformOut, TaskViewTransform frontTransform,
            boolean ignoreTaskOverrides) {
        return getStackTransform(task, stackScroll, mFocusState, transformOut, frontTransform,
                false /* forceUpdate */, ignoreTaskOverrides);
    }

    public TaskViewTransform getStackTransform(Task task, float stackScroll, int focusState,
            TaskViewTransform transformOut, TaskViewTransform frontTransform, boolean forceUpdate,
            boolean ignoreTaskOverrides) {
        if (mFreeformLayoutAlgorithm.isTransformAvailable(task, this)) {
            mFreeformLayoutAlgorithm.getTransform(task, transformOut, this);
            return transformOut;
        } else {
            // Return early if we have an invalid index
            int nonOverrideTaskProgress = mTaskIndexMap.get(task.key.id, -1);
            if (task == null || nonOverrideTaskProgress == -1) {
                transformOut.reset();
                return transformOut;
            }
            float taskProgress = ignoreTaskOverrides
                    ? nonOverrideTaskProgress
                    : getStackScrollForTask(task);
            getStackTransform(taskProgress, nonOverrideTaskProgress, stackScroll, focusState,
                    transformOut, frontTransform, false /* ignoreSingleTaskCase */, forceUpdate);
            return transformOut;
        }
    }

    /**
     * Like {@link #getStackTransform}, but in screen coordinates
     */
    public TaskViewTransform getStackTransformScreenCoordinates(Task task, float stackScroll,
            TaskViewTransform transformOut, TaskViewTransform frontTransform) {
        Rect windowRect = Recents.getSystemServices().getWindowRect();
        TaskViewTransform transform = getStackTransform(task, stackScroll, transformOut,
                frontTransform);
        transform.rect.offset(windowRect.left, windowRect.top);
        return transform;
    }

    /**
     * Update/get the transform.
     *
     * @param ignoreSingleTaskCase When set, will ensure that the transform computed does not take
     *                             into account the special single-task case.  This is only used
     *                             internally to ensure that we can calculate the transform for any
     *                             position in the stack.
     */
    public void getStackTransform(float taskProgress, float nonOverrideTaskProgress,
            float stackScroll, int focusState, TaskViewTransform transformOut,
            TaskViewTransform frontTransform, boolean ignoreSingleTaskCase, boolean forceUpdate) {
        SystemServicesProxy ssp = Recents.getSystemServices();

        // Compute the focused and unfocused offset
        float boundedStackScroll = Utilities.clamp(stackScroll, mMinScrollP, mMaxScrollP);
        mUnfocusedRange.offset(boundedStackScroll);
        mFocusedRange.offset(boundedStackScroll);
        float boundedScrollUnfocusedRangeX = mUnfocusedRange.getNormalizedX(taskProgress);
        float boundedScrollFocusedRangeX = mFocusedRange.getNormalizedX(taskProgress);
        float boundedScrollUnfocusedNonOverrideRangeX =
                mUnfocusedRange.getNormalizedX(nonOverrideTaskProgress);
        mUnfocusedRange.offset(stackScroll);
        mFocusedRange.offset(stackScroll);
        boolean unfocusedVisible = mUnfocusedRange.isInRange(taskProgress);
        boolean focusedVisible = mFocusedRange.isInRange(taskProgress);

        // Skip if the task is not visible
        if (!forceUpdate && !unfocusedVisible && !focusedVisible) {
            transformOut.reset();
            return;
        }

        float unfocusedRangeX = mUnfocusedRange.getNormalizedX(taskProgress);
        float focusedRangeX = mFocusedRange.getNormalizedX(taskProgress);

        int x = (mStackRect.width() - mTaskRect.width()) / 2;
        int y;
        float z;
        float dimAlpha;
        float viewOutlineAlpha;
        if (!ssp.hasFreeformWorkspaceSupport() && mNumStackTasks == 1 && !ignoreSingleTaskCase) {
            // When there is exactly one task, then decouple the task from the stack and just move
            // in screen space
            float tmpP = (mMinScrollP - stackScroll) / mNumStackTasks;
            int centerYOffset = (mStackRect.top - mTaskRect.top) +
                    (mStackRect.height() - mTaskRect.height()) / 2;
            y = centerYOffset + getYForDeltaP(tmpP, 0);
            z = mMaxTranslationZ;
            dimAlpha = 0f;
            viewOutlineAlpha = (OUTLINE_ALPHA_MIN_VALUE + OUTLINE_ALPHA_MAX_VALUE) / 2f;

        } else {
            // Otherwise, update the task to the stack layout
            int unfocusedY = (int) ((1f - mUnfocusedCurveInterpolator.getInterpolation(
                    unfocusedRangeX)) * mStackRect.height());
            int focusedY = (int) ((1f - mFocusedCurveInterpolator.getInterpolation(
                    focusedRangeX)) * mStackRect.height());
            float unfocusedDim = mUnfocusedDimCurveInterpolator.getInterpolation(
                    boundedScrollUnfocusedRangeX);
            float focusedDim = mFocusedDimCurveInterpolator.getInterpolation(
                    boundedScrollFocusedRangeX);

            y = (mStackRect.top - mTaskRect.top) +
                    (int) Utilities.mapRange(focusState, unfocusedY, focusedY);
            z = Utilities.mapRange(Utilities.clamp01(boundedScrollUnfocusedNonOverrideRangeX),
                    mMinTranslationZ, mMaxTranslationZ);
            dimAlpha = Utilities.mapRange(focusState, unfocusedDim, focusedDim);
            viewOutlineAlpha = Utilities.mapRange(Utilities.clamp01(boundedScrollUnfocusedRangeX),
                    OUTLINE_ALPHA_MIN_VALUE, OUTLINE_ALPHA_MAX_VALUE);
        }

        // Fill out the transform
        transformOut.scale = 1f;
        transformOut.alpha = 1f;
        transformOut.translationZ = z;
        transformOut.dimAlpha = dimAlpha;
        transformOut.viewOutlineAlpha = viewOutlineAlpha;
        transformOut.rect.set(mTaskRect);
        transformOut.rect.offset(x, y);
        Utilities.scaleRectAboutCenter(transformOut.rect, transformOut.scale);
        transformOut.visible = (transformOut.rect.top < mStackRect.bottom) &&
                (frontTransform == null || transformOut.rect.top != frontTransform.rect.top);
    }

    /**
     * Returns the untransformed task view bounds.
     */
    public Rect getUntransformedTaskViewBounds() {
        return new Rect(mTaskRect);
    }

    /**
     * Returns the scroll progress to scroll to such that the top of the task is at the top of the
     * stack.
     */
    float getStackScrollForTask(Task t) {
        return mTaskIndexOverrideMap.get(t.key.id, (float) mTaskIndexMap.get(t.key.id, 0));
    }

    /**
     * Returns the original scroll progress to scroll to such that the top of the task is at the top
     * of the stack.
     */
    float getStackScrollForTaskIgnoreOverrides(Task t) {
        return (float) mTaskIndexMap.get(t.key.id, 0);
    }

    /**
     * Returns the scroll progress to scroll to such that the top of the task at the initial top
     * offset (which is at the task's brightest point).
     */
    float getStackScrollForTaskAtInitialOffset(Task t) {
        float normX = getNormalizedXFromUnfocusedY(mInitialTopOffset, FROM_TOP);
        mUnfocusedRange.offset(0f);
        return (float) mTaskIndexMap.get(t.key.id, 0) - Math.max(0,
                mUnfocusedRange.getAbsoluteX(normX));
    }

    /**
     * Maps a movement in screen y, relative to {@param downY}, to a movement in along the arc
     * length of the curve.  We know the curve is mostly flat, so we just map the length of the
     * screen along the arc-length proportionally (1/arclength).
     */
    public float getDeltaPForY(int downY, int y) {
        float deltaP = (float) (y - downY) / mStackRect.height() *
                mUnfocusedCurveInterpolator.getArcLength();
        return -deltaP;
    }

    /**
     * This is the inverse of {@link #getDeltaPForY}.  Given a movement along the arc length
     * of the curve, map back to the screen y.
     */
    public int getYForDeltaP(float downScrollP, float p) {
        int y = (int) ((p - downScrollP) * mStackRect.height() *
                (1f / mUnfocusedCurveInterpolator.getArcLength()));
        return -y;
    }

    /**
     * Returns the task stack bounds in the current orientation.  This rect takes into account the
     * top and right system insets (but not the bottom inset) and left/right paddings, but _not_
     * the top/bottom padding or insets.
     */
    public void getTaskStackBounds(Rect windowRect, int topInset, int rightInset,
            Rect taskStackBounds) {
        RecentsConfiguration config = Recents.getConfiguration();
        if (config.hasTransposedNavBar) {
            taskStackBounds.set(windowRect.left, windowRect.top + topInset,
                    windowRect.right - rightInset, windowRect.bottom);
        } else {
            taskStackBounds.set(windowRect.left, windowRect.top + topInset,
                    windowRect.right - rightInset, windowRect.bottom);
        }

        // Ensure that the new width is at most the smaller display edge size
        Rect displayRect = Recents.getSystemServices().getDisplayRect();
        int sideMargin = getScaleForExtent(windowRect, displayRect, mBaseSideMargin, mMinMargin,
                WIDTH);
        int targetStackWidth = Math.min(taskStackBounds.width() - 2 * sideMargin,
                Math.min(displayRect.width(), displayRect.height()));
        taskStackBounds.inset((taskStackBounds.width() - targetStackWidth) / 2, 0);
    }

    /**
     * Retrieves resources that are constant regardless of the current configuration of the device.
     */
    public static int getDimensionForDevice(Resources res, int phoneResId,
            int tabletResId, int xlargeTabletResId) {
        return getDimensionForDevice(res, phoneResId, phoneResId, tabletResId, tabletResId,
                xlargeTabletResId, xlargeTabletResId);
    }

    /**
     * Retrieves resources that are constant regardless of the current configuration of the device.
     */
    public static int getDimensionForDevice(Resources res, int phonePortResId, int phoneLandResId,
            int tabletPortResId, int tabletLandResId, int xlargeTabletPortResId,
            int xlargeTabletLandResId) {
        RecentsConfiguration config = Recents.getConfiguration();
        boolean isLandscape = Recents.getSystemServices().getDisplayOrientation() ==
                Configuration.ORIENTATION_LANDSCAPE;
        if (config.isXLargeScreen) {
            return res.getDimensionPixelSize(isLandscape
                    ? xlargeTabletLandResId
                    : xlargeTabletPortResId);
        } else if (config.isLargeScreen) {
            return res.getDimensionPixelSize(isLandscape
                    ? tabletLandResId
                    : tabletPortResId);
        } else {
            return res.getDimensionPixelSize(isLandscape
                    ? phoneLandResId
                    : phonePortResId);
        }
    }

    /**
     * Returns the normalized x on the unfocused curve given an absolute Y position (relative to the
     * stack height).
     */
    private float getNormalizedXFromUnfocusedY(float y, @AnchorSide int fromSide) {
        float offset = (fromSide == FROM_TOP)
                ? mStackRect.height() - y
                : y;
        float offsetPct = offset / mStackRect.height();
        return mUnfocusedCurveInterpolator.getX(offsetPct);
    }

    /**
     * Creates a new path for the focused curve.
     */
    private Path constructFocusedCurve() {
        // Initialize the focused curve. This curve is a piecewise curve composed of several
        // linear pieces that goes from (0,1) through (0.5, peek height offset),
        // (0.5, bottom task offsets), and (1,0).
        float topPeekHeightPct = (float) mFocusedTopPeekHeight / mStackRect.height();
        float bottomPeekHeightPct = (float) (mStackBottomOffset + mFocusedBottomPeekHeight) /
                mStackRect.height();
        Path p = new Path();
        p.moveTo(0f, 1f);
        p.lineTo(0.5f, 1f - topPeekHeightPct);
        p.lineTo(1f - (0.5f / mFocusedRange.relativeMax), bottomPeekHeightPct);
        p.lineTo(1f, 0f);
        return p;
    }

    /**
     * Creates a new path for the unfocused curve.
     */
    private Path constructUnfocusedCurve() {
        // Initialize the unfocused curve. This curve is a piecewise curve composed of two quadradic
        // beziers that goes from (0,1) through (0.5, peek height offset) and ends at (1,0).  This
        // ensures that we match the range, at which 0.5 represents the stack scroll at the current
        // task progress.  Because the height offset can change depending on a resource, we compute
        // the control point of the second bezier such that between it and a first known point,
        // there is a tangent at (0.5, peek height offset).
        float cpoint1X = 0.4f;
        float cpoint1Y = 0.975f;
        float topPeekHeightPct = (float) mFocusedTopPeekHeight / mStackRect.height();
        float slope = ((1f - topPeekHeightPct) - cpoint1Y) / (0.5f - cpoint1X);
        float b = 1f - slope * cpoint1X;
        float cpoint2X = 0.65f;
        float cpoint2Y = slope * cpoint2X + b;
        Path p = new Path();
        p.moveTo(0f, 1f);
        p.cubicTo(0f, 1f, cpoint1X, cpoint1Y, 0.5f, 1f - topPeekHeightPct);
        p.cubicTo(0.5f, 1f - topPeekHeightPct, cpoint2X, cpoint2Y, 1f, 0f);
        return p;
    }

    /**
     * Creates a new path for the focused dim curve.
     */
    private Path constructFocusedDimCurve() {
        Path p = new Path();
        // The focused dim interpolator starts at max dim, reduces to zero at 0.5 (the focused
        // task), then goes back to max dim at the next task
        p.moveTo(0f, MAX_DIM);
        p.lineTo(0.5f, 0f);
        p.lineTo(0.5f + (0.5f / mFocusedRange.relativeMax), MAX_DIM);
        p.lineTo(1f, MAX_DIM);
        return p;
    }

    /**
     * Creates a new path for the unfocused dim curve.
     */
    private Path constructUnfocusedDimCurve() {
        float focusX = getNormalizedXFromUnfocusedY(mInitialTopOffset, FROM_TOP);
        float cpoint2X = focusX + (1f - focusX) / 2;
        Path p = new Path();
        // The unfocused dim interpolator starts at max dim, reduces to zero at 0.5 (the focused
        // task), then goes back to max dim towards the front of the stack
        p.moveTo(0f, MAX_DIM);
        p.cubicTo(focusX * 0.5f, MAX_DIM, focusX * 0.75f, MAX_DIM * 0.75f, focusX, 0f);
        p.cubicTo(cpoint2X, 0f, cpoint2X, MED_DIM, 1f, MED_DIM);
        return p;
    }

    /**
     * Scales the given {@param value} to the scale of the {@param instance} rect relative to the
     * {@param other} rect in the {@param extent} side.
     */
    private int getScaleForExtent(Rect instance, Rect other, int value, int minValue,
                                  @Extent int extent) {
        if (extent == WIDTH) {
            return Math.max(minValue, (int) (((float) instance.width() / other.width()) * value));
        } else if (extent == HEIGHT) {
            return Math.max(minValue, (int) (((float) instance.height() / other.height()) * value));
        }
        return value;
    }

    /**
     * Updates the current transforms that would put a TaskView at the front and back of the stack.
     */
    private void updateFrontBackTransforms() {
        // Return early if we have not yet initialized
        if (mStackRect.isEmpty()) {
            return;
        }

        float min = Utilities.mapRange(mFocusState, mUnfocusedRange.relativeMin,
                mFocusedRange.relativeMin);
        float max = Utilities.mapRange(mFocusState, mUnfocusedRange.relativeMax,
                mFocusedRange.relativeMax);
        getStackTransform(min, min, 0f, mFocusState, mBackOfStackTransform, null,
                true /* ignoreSingleTaskCase */, true /* forceUpdate */);
        getStackTransform(max, max, 0f, mFocusState, mFrontOfStackTransform, null,
                true /* ignoreSingleTaskCase */, true /* forceUpdate */);
        mBackOfStackTransform.visible = true;
        mFrontOfStackTransform.visible = true;
    }
}
