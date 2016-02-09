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

import static android.app.ActivityManager.StackId.FREEFORM_WORKSPACE_STACK_ID;
import static android.app.ActivityManager.StackId.FULLSCREEN_WORKSPACE_STACK_ID;
import static android.app.ActivityManager.StackId.INVALID_STACK_ID;

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.ComponentName;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Parcelable;
import android.provider.Settings;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.MutableBoolean;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.FrameLayout;

import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.MetricsProto.MetricsEvent;
import com.android.systemui.Interpolators;
import com.android.systemui.R;
import com.android.systemui.recents.Recents;
import com.android.systemui.recents.RecentsActivity;
import com.android.systemui.recents.RecentsActivityLaunchState;
import com.android.systemui.recents.RecentsConfiguration;
import com.android.systemui.recents.RecentsDebugFlags;
import com.android.systemui.recents.events.EventBus;
import com.android.systemui.recents.events.activity.CancelEnterRecentsWindowAnimationEvent;
import com.android.systemui.recents.events.activity.DismissRecentsToHomeAnimationStarted;
import com.android.systemui.recents.events.activity.EnterRecentsTaskStackAnimationCompletedEvent;
import com.android.systemui.recents.events.activity.EnterRecentsWindowAnimationCompletedEvent;
import com.android.systemui.recents.events.activity.HideHistoryButtonEvent;
import com.android.systemui.recents.events.activity.HideHistoryEvent;
import com.android.systemui.recents.events.activity.IterateRecentsEvent;
import com.android.systemui.recents.events.activity.LaunchNextTaskRequestEvent;
import com.android.systemui.recents.events.activity.LaunchTaskEvent;
import com.android.systemui.recents.events.activity.LaunchTaskStartedEvent;
import com.android.systemui.recents.events.activity.PackagesChangedEvent;
import com.android.systemui.recents.events.activity.ShowHistoryButtonEvent;
import com.android.systemui.recents.events.activity.ShowHistoryEvent;
import com.android.systemui.recents.events.activity.TaskStackUpdatedEvent;
import com.android.systemui.recents.events.component.RecentsVisibilityChangedEvent;
import com.android.systemui.recents.events.ui.AllTaskViewsDismissedEvent;
import com.android.systemui.recents.events.ui.DeleteTaskDataEvent;
import com.android.systemui.recents.events.ui.DismissTaskViewEvent;
import com.android.systemui.recents.events.ui.StackViewScrolledEvent;
import com.android.systemui.recents.events.ui.TaskViewDismissedEvent;
import com.android.systemui.recents.events.ui.UpdateFreeformTaskViewVisibilityEvent;
import com.android.systemui.recents.events.ui.UserInteractionEvent;
import com.android.systemui.recents.events.ui.dragndrop.DragDropTargetChangedEvent;
import com.android.systemui.recents.events.ui.dragndrop.DragEndEvent;
import com.android.systemui.recents.events.ui.dragndrop.DragStartEvent;
import com.android.systemui.recents.events.ui.dragndrop.DragStartInitializeDropTargetsEvent;
import com.android.systemui.recents.events.ui.focus.DismissFocusedTaskViewEvent;
import com.android.systemui.recents.events.ui.focus.FocusNextTaskViewEvent;
import com.android.systemui.recents.events.ui.focus.FocusPreviousTaskViewEvent;
import com.android.systemui.recents.misc.DozeTrigger;
import com.android.systemui.recents.misc.ReferenceCountedTrigger;
import com.android.systemui.recents.misc.SystemServicesProxy;
import com.android.systemui.recents.misc.Utilities;
import com.android.systemui.recents.model.Task;
import com.android.systemui.recents.model.TaskStack;

import java.util.ArrayList;
import java.util.List;


/* The visual representation of a task stack view */
public class TaskStackView extends FrameLayout implements TaskStack.TaskStackCallbacks,
        TaskView.TaskViewCallbacks, TaskStackViewScroller.TaskStackViewScrollerCallbacks,
        TaskStackLayoutAlgorithm.TaskStackLayoutAlgorithmCallbacks,
        ViewPool.ViewPoolConsumer<TaskView, Task> {

    private final static String KEY_SAVED_STATE_SUPER = "saved_instance_state_super";
    private final static String KEY_SAVED_STATE_LAYOUT_FOCUSED_STATE =
            "saved_instance_state_layout_focused_state";
    private final static String KEY_SAVED_STATE_LAYOUT_STACK_SCROLL =
            "saved_instance_state_layout_stack_scroll";

    // The thresholds at which to show/hide the history button.
    private static final float SHOW_HISTORY_BUTTON_SCROLL_THRESHOLD = 0.3f;
    private static final float HIDE_HISTORY_BUTTON_SCROLL_THRESHOLD = 0.3f;

    public static final int DEFAULT_SYNC_STACK_DURATION = 200;
    private static final int DRAG_SCALE_DURATION = 175;
    private static final float DRAG_SCALE_FACTOR = 1.05f;

    private static final ArraySet<Task.TaskKey> EMPTY_TASK_SET = new ArraySet<>();

    TaskStack mStack;
    TaskStackLayoutAlgorithm mLayoutAlgorithm;
    TaskStackViewScroller mStackScroller;
    TaskStackViewTouchHandler mTouchHandler;
    TaskStackAnimationHelper mAnimationHelper;
    GradientDrawable mFreeformWorkspaceBackground;
    ObjectAnimator mFreeformWorkspaceBackgroundAnimator;
    ViewPool<TaskView, Task> mViewPool;

    ArrayList<TaskView> mTaskViews = new ArrayList<>();
    ArrayList<TaskViewTransform> mCurrentTaskTransforms = new ArrayList<>();
    ArraySet<Task.TaskKey> mIgnoreTasks = new ArraySet<>();
    AnimationProps mDeferredTaskViewLayoutAnimation = null;

    DozeTrigger mUIDozeTrigger;
    Task mFocusedTask;

    int mTaskCornerRadiusPx;
    private int mDividerSize;
    private int mStartTimerIndicatorDuration;

    boolean mTaskViewsClipDirty = true;
    boolean mAwaitingFirstLayout = true;
    boolean mInMeasureLayout = false;
    boolean mEnterAnimationComplete = false;
    boolean mTouchExplorationEnabled;
    boolean mScreenPinningEnabled;

    // The stable stack bounds are the full bounds that we were measured with from RecentsView
    Rect mStableStackBounds = new Rect();
    // The current stack bounds are dynamic and may change as the user drags and drops
    Rect mStackBounds = new Rect();

    int[] mTmpVisibleRange = new int[2];
    Rect mTmpRect = new Rect();
    ArrayMap<Task.TaskKey, TaskView> mTmpTaskViewMap = new ArrayMap<>();
    List<TaskView> mTmpTaskViews = new ArrayList<>();
    TaskViewTransform mTmpTransform = new TaskViewTransform();
    LayoutInflater mInflater;

    // A convenience update listener to request updating clipping of tasks
    private ValueAnimator.AnimatorUpdateListener mRequestUpdateClippingListener =
            new ValueAnimator.AnimatorUpdateListener() {
                @Override
                public void onAnimationUpdate(ValueAnimator animation) {
                    if (!mTaskViewsClipDirty) {
                        mTaskViewsClipDirty = true;
                        invalidate();
                    }
                }
            };

    // The drop targets for a task drag
    private DropTarget mFreeformWorkspaceDropTarget = new DropTarget() {
        @Override
        public boolean acceptsDrop(int x, int y, int width, int height, boolean isCurrentTarget) {
            // This drop target has a fixed bounds and should be checked last, so just fall through
            // if it is the current target
            if (!isCurrentTarget) {
                return mLayoutAlgorithm.mFreeformRect.contains(x, y);
            }
            return false;
        }
    };

    private DropTarget mStackDropTarget = new DropTarget() {
        @Override
        public boolean acceptsDrop(int x, int y, int width, int height, boolean isCurrentTarget) {
            // This drop target has a fixed bounds and should be checked last, so just fall through
            // if it is the current target
            if (!isCurrentTarget) {
                return mLayoutAlgorithm.mStackRect.contains(x, y);
            }
            return false;
        }
    };

    public TaskStackView(Context context, TaskStack stack) {
        super(context);
        SystemServicesProxy ssp = Recents.getSystemServices();
        Resources res = context.getResources();

        // Set the stack first
        setStack(stack);
        mViewPool = new ViewPool<>(context, this);
        mInflater = LayoutInflater.from(context);
        mLayoutAlgorithm = new TaskStackLayoutAlgorithm(context, this);
        mStackScroller = new TaskStackViewScroller(context, this, mLayoutAlgorithm);
        mTouchHandler = new TaskStackViewTouchHandler(context, this, mStackScroller);
        mAnimationHelper = new TaskStackAnimationHelper(context, this);
        mTaskCornerRadiusPx = res.getDimensionPixelSize(
                R.dimen.recents_task_view_rounded_corners_radius);
        mDividerSize = ssp.getDockedDividerSize(context);

        int taskBarDismissDozeDelaySeconds = getResources().getInteger(
                R.integer.recents_task_bar_dismiss_delay_seconds);
        mUIDozeTrigger = new DozeTrigger(taskBarDismissDozeDelaySeconds, new Runnable() {
            @Override
            public void run() {
                // Show the task bar dismiss buttons
                List<TaskView> taskViews = getTaskViews();
                int taskViewCount = taskViews.size();
                for (int i = 0; i < taskViewCount; i++) {
                    TaskView tv = taskViews.get(i);
                    tv.startNoUserInteractionAnimation();
                }
            }
        });
        setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);

        mFreeformWorkspaceBackground = (GradientDrawable) getContext().getDrawable(
                R.drawable.recents_freeform_workspace_bg);
        mFreeformWorkspaceBackground.setCallback(this);
        if (ssp.hasFreeformWorkspaceSupport()) {
            mFreeformWorkspaceBackground.setColor(
                    getContext().getColor(R.color.recents_freeform_workspace_bg_color));
        }
    }

    @Override
    protected void onAttachedToWindow() {
        EventBus.getDefault().register(this, RecentsActivity.EVENT_BUS_PRIORITY + 1);
        super.onAttachedToWindow();
        readSystemFlags();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        EventBus.getDefault().unregister(this);
    }

    /** Sets the task stack */
    void setStack(TaskStack stack) {
        // Set the new stack
        mStack = stack;
        if (mStack != null) {
            mStack.setCallbacks(this);
        }
        // Layout again with the new stack
        requestLayout();
    }

    /** Returns the task stack. */
    TaskStack getStack() {
        return mStack;
    }

    /** Updates the list of task views */
    void updateTaskViewsList() {
        mTaskViews.clear();
        int childCount = getChildCount();
        for (int i = 0; i < childCount; i++) {
            View v = getChildAt(i);
            if (v instanceof TaskView) {
                mTaskViews.add((TaskView) v);
            }
        }
    }

    /** Gets the list of task views */
    List<TaskView> getTaskViews() {
        return mTaskViews;
    }

    /**
     * Returns the front most task view.
     *
     * @param stackTasksOnly if set, will return the front most task view in the stack (by default
     *                       the front most task view will be freeform since they are placed above
     *                       stack tasks)
     */
    private TaskView getFrontMostTaskView(boolean stackTasksOnly) {
        List<TaskView> taskViews = getTaskViews();
        int taskViewCount = taskViews.size();
        for (int i = taskViewCount - 1; i >= 0; i--) {
            TaskView tv = taskViews.get(i);
            Task task = tv.getTask();
            if (stackTasksOnly && task.isFreeformTask()) {
                continue;
            }
            return tv;
        }
        return null;
    }

    /**
     * Finds the child view given a specific {@param task}.
     */
    public TaskView getChildViewForTask(Task t) {
        List<TaskView> taskViews = getTaskViews();
        int taskViewCount = taskViews.size();
        for (int i = 0; i < taskViewCount; i++) {
            TaskView tv = taskViews.get(i);
            if (tv.getTask() == t) {
                return tv;
            }
        }
        return null;
    }

    /** Resets this TaskStackView for reuse. */
    void reset() {
        // Reset the focused task
        resetFocusedTask(getFocusedTask());

        // Return all the views to the pool
        List<TaskView> taskViews = getTaskViews();
        int taskViewCount = taskViews.size();
        for (int i = taskViewCount - 1; i >= 0; i--) {
            mViewPool.returnViewToPool(taskViews.get(i));
        }

        // Mark each task view for relayout
        List<TaskView> poolViews = mViewPool.getViews();
        for (TaskView tv : poolViews) {
            tv.reset();
        }

        // Reset the stack state
        mStack.reset();
        mTaskViewsClipDirty = true;
        mAwaitingFirstLayout = true;
        mEnterAnimationComplete = false;
        mUIDozeTrigger.stopDozing();
        mUIDozeTrigger.resetTrigger();
        mStackScroller.reset();
        mLayoutAlgorithm.reset();
        readSystemFlags();
        requestLayout();
    }

    /** Returns the stack algorithm for this task stack. */
    public TaskStackLayoutAlgorithm getStackAlgorithm() {
        return mLayoutAlgorithm;
    }

    /**
     * Adds a task to the ignored set.
     */
    void addIgnoreTask(Task task) {
        mIgnoreTasks.add(task.key);
    }

    /**
     * Removes a task from the ignored set.
     */
    void removeIgnoreTask(Task task) {
        mIgnoreTasks.remove(task.key);
    }

    /**
     * Returns whether the specified {@param task} is ignored.
     */
    boolean isIgnoredTask(Task task) {
        return mIgnoreTasks.contains(task.key);
    }

    /**
     * Computes the task transforms at the current stack scroll for all visible tasks. If a valid
     * target stack scroll is provided (ie. is different than {@param curStackScroll}), then the
     * visible range includes all tasks at the target stack scroll. This is useful for ensure that
     * all views necessary for a transition or animation will be visible at the start.
     *
     * This call ignores freeform tasks.
     *
     * @param taskTransforms The set of task view transforms to reuse, this list will be sized to
     *                       match the size of {@param tasks}
     * @param tasks The set of tasks for which to generate transforms
     * @param curStackScroll The current stack scroll
     * @param targetStackScroll The stack scroll that we anticipate we are going to be scrolling to.
     *                          The range of the union of the visible views at the current and
     *                          target stack scrolls will be returned.
     * @param ignoreTasksSet The set of tasks to skip for purposes of calculaing the visible range.
     *                       Transforms will still be calculated for the ignore tasks.
     */
    boolean computeVisibleTaskTransforms(ArrayList<TaskViewTransform> taskTransforms,
            ArrayList<Task> tasks, float curStackScroll, float targetStackScroll,
            int[] visibleRangeOut, ArraySet<Task.TaskKey> ignoreTasksSet) {
        int taskCount = tasks.size();
        int frontMostVisibleIndex = -1;
        int backMostVisibleIndex = -1;
        boolean useTargetStackScroll = Float.compare(curStackScroll, targetStackScroll) != 0;

        // We can reuse the task transforms where possible to reduce object allocation
        Utilities.matchTaskListSize(tasks, taskTransforms);

        // Update the stack transforms
        TaskViewTransform frontTransform = null;
        TaskViewTransform frontTransformAtTarget = null;
        TaskViewTransform transform = null;
        TaskViewTransform transformAtTarget = null;
        for (int i = taskCount - 1; i >= 0; i--) {
            Task task = tasks.get(i);

            // Calculate the current and (if necessary) the target transform for the task
            transform = mLayoutAlgorithm.getStackTransform(task, curStackScroll,
                    taskTransforms.get(i), frontTransform);
            if (useTargetStackScroll && !transform.visible) {
                // If we have a target stack scroll and the task is not currently visible, then we
                // just update the transform at the new scroll
                // TODO: Optimize this
                transformAtTarget = mLayoutAlgorithm.getStackTransform(task,
                        targetStackScroll, new TaskViewTransform(), frontTransformAtTarget);
                if (transformAtTarget.visible) {
                    transform.copyFrom(transformAtTarget);
                }
            }

            // For ignore tasks, only calculate the stack transform and skip the calculation of the
            // visible stack indices
            if (ignoreTasksSet.contains(task.key)) {
                continue;
            }

            // For freeform tasks, only calculate the stack transform and skip the calculation of
            // the visible stack indices
            if (task.isFreeformTask()) {
                continue;
            }

            if (transform.visible) {
                if (frontMostVisibleIndex < 0) {
                    frontMostVisibleIndex = i;
                }
                backMostVisibleIndex = i;
            } else {
                if (backMostVisibleIndex != -1) {
                    // We've reached the end of the visible range, so going down the rest of the
                    // stack, we can just reset the transforms accordingly
                    while (i >= 0) {
                        taskTransforms.get(i).reset();
                        i--;
                    }
                    break;
                }
            }

            frontTransform = transform;
            frontTransformAtTarget = transformAtTarget;
        }
        if (visibleRangeOut != null) {
            visibleRangeOut[0] = frontMostVisibleIndex;
            visibleRangeOut[1] = backMostVisibleIndex;
        }
        return frontMostVisibleIndex != -1 && backMostVisibleIndex != -1;
    }

    /**
     * Binds the visible {@link TaskView}s at the given target scroll.
     *
     * @see #bindVisibleTaskViews(float, ArraySet<Task.TaskKey>)
     */
    void bindVisibleTaskViews(float targetStackScroll) {
        bindVisibleTaskViews(targetStackScroll, mIgnoreTasks);
    }

    /**
     * Synchronizes the set of children {@link TaskView}s to match the visible set of tasks in the
     * current {@link TaskStack}. This call does not continue on to update their position to the
     * computed {@link TaskViewTransform}s of the visible range, but only ensures that they will
     * be added/removed from the view hierarchy and placed in the correct Z order and initial
     * position (if not currently on screen).
     *
     * @param targetStackScroll If provided, will ensure that the set of visible {@link TaskView}s
     *                          includes those visible at the current stack scroll, and all at the
     *                          target stack scroll.
     * @param ignoreTasksSet The set of tasks to ignore in this rebinding of the visible
     *                       {@link TaskView}s
     */
    void bindVisibleTaskViews(float targetStackScroll, ArraySet<Task.TaskKey> ignoreTasksSet) {
        final int[] visibleStackRange = mTmpVisibleRange;

        // Get all the task transforms
        final ArrayList<Task> tasks = mStack.getStackTasks();
        final boolean isValidVisibleRange = computeVisibleTaskTransforms(mCurrentTaskTransforms,
                tasks, mStackScroller.getStackScroll(), targetStackScroll, visibleStackRange,
                ignoreTasksSet);

        // Return all the invisible children to the pool
        mTmpTaskViewMap.clear();
        List<TaskView> taskViews = getTaskViews();
        int lastFocusedTaskIndex = -1;
        int taskViewCount = taskViews.size();
        for (int i = taskViewCount - 1; i >= 0; i--) {
            TaskView tv = taskViews.get(i);
            Task task = tv.getTask();
            int taskIndex = mStack.indexOfStackTask(task);

            // Skip ignored tasks
            if (ignoreTasksSet.contains(task.key)) {
                continue;
            }

            if (task.isFreeformTask() ||
                    visibleStackRange[1] <= taskIndex && taskIndex <= visibleStackRange[0]) {
                mTmpTaskViewMap.put(task.key, tv);
            } else {
                if (mTouchExplorationEnabled) {
                    lastFocusedTaskIndex = taskIndex;
                    resetFocusedTask(task);
                }
                mViewPool.returnViewToPool(tv);
            }
        }

        // Pick up all the newly visible children
        int lastVisStackIndex = isValidVisibleRange ? visibleStackRange[1] : 0;
        for (int i = tasks.size() - 1; i >= lastVisStackIndex; i--) {
            Task task = tasks.get(i);
            TaskViewTransform transform = mCurrentTaskTransforms.get(i);

            // Skip ignored tasks
            if (ignoreTasksSet.contains(task.key)) {
                continue;
            }

            // Skip the invisible non-freeform stack tasks
            if (i > visibleStackRange[0] && !task.isFreeformTask()) {
                continue;
            }

            TaskView tv = mTmpTaskViewMap.get(task.key);
            if (tv == null) {
                tv = mViewPool.pickUpViewFromPool(task, task);
                if (task.isFreeformTask()) {
                    tv.updateViewPropertiesToTaskTransform(transform, AnimationProps.IMMEDIATE,
                            mRequestUpdateClippingListener);
                } else {
                    if (Float.compare(transform.p, 0f) <= 0) {
                        tv.updateViewPropertiesToTaskTransform(
                                mLayoutAlgorithm.getBackOfStackTransform(),
                                AnimationProps.IMMEDIATE, mRequestUpdateClippingListener);
                    } else {
                        tv.updateViewPropertiesToTaskTransform(
                                mLayoutAlgorithm.getFrontOfStackTransform(),
                                AnimationProps.IMMEDIATE, mRequestUpdateClippingListener);
                    }
                }
            } else {
                // Reattach it in the right z order
                final int taskIndex = mStack.indexOfStackTask(task);
                final int insertIndex = findTaskViewInsertIndex(task, taskIndex);
                if (insertIndex != getTaskViews().indexOf(tv)){
                    detachViewFromParent(tv);
                    attachViewToParent(tv, insertIndex, tv.getLayoutParams());
                    updateTaskViewsList();
                }
            }
        }

        // Update the focus if the previous focused task was returned to the view pool
        if (lastFocusedTaskIndex != -1) {
            if (lastFocusedTaskIndex < visibleStackRange[1]) {
                setFocusedTask(visibleStackRange[1], false /* scrollToTask */,
                        true /* requestViewFocus */);
            } else {
                setFocusedTask(visibleStackRange[0], false /* scrollToTask */,
                        true /* requestViewFocus */);
            }
        }
    }

    /**
     * Relayout the the visible {@link TaskView}s to their current transforms as specified by the
     * {@link TaskStackLayoutAlgorithm} with the given {@param animation}. This call cancels any
     * animations that are current running on those task views, and will ensure that the children
     * {@link TaskView}s will match the set of visible tasks in the stack.
     *
     * @see #relayoutTaskViews(AnimationProps, ArraySet<Task.TaskKey>)
     */
    void relayoutTaskViews(AnimationProps animation) {
        relayoutTaskViews(animation, mIgnoreTasks);
    }

    /**
     * Relayout the the visible {@link TaskView}s to their current transforms as specified by the
     * {@link TaskStackLayoutAlgorithm} with the given {@param animation}. This call cancels any
     * animations that are current running on those task views, and will ensure that the children
     * {@link TaskView}s will match the set of visible tasks in the stack.
     *
     * @param ignoreTasksSet the set of tasks to ignore in the relayout
     */
    void relayoutTaskViews(AnimationProps animation, ArraySet<Task.TaskKey> ignoreTasksSet) {
        // If we had a deferred animation, cancel that
        mDeferredTaskViewLayoutAnimation = null;

        // Cancel all task view animations
        cancelAllTaskViewAnimations();

        // Synchronize the current set of TaskViews
        bindVisibleTaskViews(mStackScroller.getStackScroll(), ignoreTasksSet);

        // Animate them to their final transforms with the given animation
        List<TaskView> taskViews = getTaskViews();
        int taskViewCount = taskViews.size();
        for (int i = 0; i < taskViewCount; i++) {
            final TaskView tv = taskViews.get(i);
            final int taskIndex = mStack.indexOfStackTask(tv.getTask());
            final TaskViewTransform transform = mCurrentTaskTransforms.get(taskIndex);

            if (ignoreTasksSet.contains(tv.getTask().key)) {
                continue;
            }

            updateTaskViewToTransform(tv, transform, animation);
        }
    }

    /**
     * Posts an update to synchronize the {@link TaskView}s with the stack on the next frame.
     */
    void relayoutTaskViewsOnNextFrame(AnimationProps animation) {
        mDeferredTaskViewLayoutAnimation = animation;
        invalidate();
    }

    /**
     * Called to update a specific {@link TaskView} to a given {@link TaskViewTransform} with a
     * given set of {@link AnimationProps} properties.
     */
    public void updateTaskViewToTransform(TaskView taskView, TaskViewTransform transform,
            AnimationProps animation) {
        taskView.updateViewPropertiesToTaskTransform(transform, animation,
                mRequestUpdateClippingListener);
    }

    /**
     * Returns the current task transforms of all tasks, falling back to the stack layout if there
     * is no {@link TaskView} for the task.
     */
    public void getCurrentTaskTransforms(ArrayList<Task> tasks,
            ArrayList<TaskViewTransform> transformsOut) {
        Utilities.matchTaskListSize(tasks, transformsOut);
        for (int i = tasks.size() - 1; i >= 0; i--) {
            Task task = tasks.get(i);
            TaskViewTransform transform = transformsOut.get(i);
            TaskView tv = getChildViewForTask(task);
            if (tv != null) {
                transform.fillIn(tv);
            } else {
                mLayoutAlgorithm.getStackTransform(task, mStackScroller.getStackScroll(),
                        transform, null, true /* forceUpdate */);
            }
            transform.visible = true;
        }
    }

    /**
     * Returns the task transforms for all the tasks in the stack if the stack was at the given
     * {@param stackScroll}.
     */
    public void getLayoutTaskTransforms(float stackScroll, ArrayList<Task> tasks,
            ArrayList<TaskViewTransform> transformsOut) {
        Utilities.matchTaskListSize(tasks, transformsOut);
        for (int i = tasks.size() - 1; i >= 0; i--) {
            Task task = tasks.get(i);
            TaskViewTransform transform = transformsOut.get(i);
            mLayoutAlgorithm.getStackTransform(task, stackScroll, transform, null);
            transform.visible = true;
        }
    }

    /**
     * Cancels all {@link TaskView} animations.
     *
     * @see #cancelAllTaskViewAnimations(ArraySet<Task.TaskKey>)
     */
    void cancelAllTaskViewAnimations() {
        cancelAllTaskViewAnimations(mIgnoreTasks);
    }

    /**
     * Cancels all {@link TaskView} animations.
     *
     * @param ignoreTasksSet The set of tasks to continue running their animations.
     */
    void cancelAllTaskViewAnimations(ArraySet<Task.TaskKey> ignoreTasksSet) {
        List<TaskView> taskViews = getTaskViews();
        for (int i = taskViews.size() - 1; i >= 0; i--) {
            final TaskView tv = taskViews.get(i);
            if (!ignoreTasksSet.contains(tv.getTask().key)) {
                tv.cancelTransformAnimation();
            }
        }
    }

    /**
     * Updates the clip for each of the task views from back to front.
     */
    private void clipTaskViews() {
        RecentsConfiguration config = Recents.getConfiguration();

        // Update the clip on each task child
        List<TaskView> taskViews = getTaskViews();
        TaskView tmpTv = null;
        TaskView prevVisibleTv = null;
        int taskViewCount = taskViews.size();
        for (int i = 0; i < taskViewCount; i++) {
            TaskView tv = taskViews.get(i);
            TaskView frontTv = null;
            int clipBottom = 0;

            if (mIgnoreTasks.contains(tv.getTask().key)) {
                // For each of the ignore tasks, update the translationZ of its TaskView to be
                // between the translationZ of the tasks immediately underneath it
                if (prevVisibleTv != null) {
                    tv.setTranslationZ(Math.max(tv.getTranslationZ(),
                            prevVisibleTv.getTranslationZ() + 0.1f));
                }
            }

            if (i < (taskViewCount - 1) && tv.shouldClipViewInStack()) {
                // Find the next view to clip against
                for (int j = i + 1; j < taskViewCount; j++) {
                    tmpTv = taskViews.get(j);

                    if (tmpTv.shouldClipViewInStack()) {
                        frontTv = tmpTv;
                        break;
                    }
                }

                // Clip against the next view, this is just an approximation since we are
                // stacked and we can make assumptions about the visibility of the this
                // task relative to the ones in front of it.
                if (frontTv != null) {
                    float taskBottom = tv.getBottom();
                    float frontTaskTop = frontTv.getTop();
                    if (frontTaskTop < taskBottom) {
                        // Map the stack view space coordinate (the rects) to view space
                        clipBottom = (int) (taskBottom - frontTaskTop) - mTaskCornerRadiusPx;
                    }
                }
            }
            tv.getViewBounds().setClipBottom(clipBottom);
            if (!config.useHardwareLayers) {
                tv.mThumbnailView.updateThumbnailVisibility(clipBottom - tv.getPaddingBottom());
            }
            prevVisibleTv = tv;
        }
        mTaskViewsClipDirty = false;
    }

    /**
     * Updates the layout algorithm min and max virtual scroll bounds.
     *
     * @see #updateLayoutAlgorithm(boolean, ArraySet<Task.TaskKey>)
     */
    void updateLayoutAlgorithm(boolean boundScrollToNewMinMax) {
        updateLayoutAlgorithm(boundScrollToNewMinMax, mIgnoreTasks);
    }

    /**
     * Updates the min and max virtual scroll bounds.
     *
     * @param ignoreTasksSet the set of tasks to ignore in the relayout
     */
    void updateLayoutAlgorithm(boolean boundScrollToNewMinMax,
            ArraySet<Task.TaskKey> ignoreTasksSet) {
        // Compute the min and max scroll values
        mLayoutAlgorithm.update(mStack, ignoreTasksSet);

        // Update the freeform workspace background
        SystemServicesProxy ssp = Recents.getSystemServices();
        if (ssp.hasFreeformWorkspaceSupport()) {
            mTmpRect.set(mLayoutAlgorithm.mFreeformRect);
            mFreeformWorkspaceBackground.setBounds(mTmpRect);
        }

        if (boundScrollToNewMinMax) {
            mStackScroller.boundScroll();
        }
    }

    /** Returns the scroller. */
    public TaskStackViewScroller getScroller() {
        return mStackScroller;
    }

    /**
     * Sets the focused task to the provided (bounded taskIndex).
     *
     * @return whether or not the stack will scroll as a part of this focus change
     */
    private boolean setFocusedTask(int taskIndex, boolean scrollToTask,
            final boolean requestViewFocus) {
        return setFocusedTask(taskIndex, scrollToTask, requestViewFocus, 0);
    }

    /**
     * Sets the focused task to the provided (bounded taskIndex).
     *
     * @return whether or not the stack will scroll as a part of this focus change
     */
    private boolean setFocusedTask(int taskIndex, boolean scrollToTask,
            final boolean requestViewFocus, final int timerIndicatorDuration) {
        // Find the next task to focus
        int newFocusedTaskIndex = mStack.getTaskCount() > 0 ?
                Math.max(0, Math.min(mStack.getTaskCount() - 1, taskIndex)) : -1;
        final Task newFocusedTask = (newFocusedTaskIndex != -1) ?
                mStack.getStackTasks().get(newFocusedTaskIndex) : null;

        // Reset the last focused task state if changed
        if (mFocusedTask != null) {
            // Cancel the timer indicator, if applicable
            if (timerIndicatorDuration > 0) {
                final TaskView tv = getChildViewForTask(mFocusedTask);
                if (tv != null) {
                    tv.getHeaderView().cancelFocusTimerIndicator();
                }
            }

            resetFocusedTask(mFocusedTask);
        }

        boolean willScroll = false;

        mFocusedTask = newFocusedTask;

        if (newFocusedTask != null) {
            // Start the timer indicator, if applicable
            if (timerIndicatorDuration > 0) {
                final TaskView tv = getChildViewForTask(mFocusedTask);
                if (tv != null) {
                    tv.getHeaderView().startFocusTimerIndicator(timerIndicatorDuration);
                } else {
                    // The view is null; set a flag for later
                    mStartTimerIndicatorDuration = timerIndicatorDuration;
                }
            }

            Runnable focusTaskRunnable = new Runnable() {
                @Override
                public void run() {
                    final TaskView tv = getChildViewForTask(newFocusedTask);
                    if (tv != null) {
                        tv.setFocusedState(true, requestViewFocus);
                    }
                }
            };

            if (scrollToTask) {
                // Cancel any running enter animations at this point when we scroll or change focus
                if (!mEnterAnimationComplete) {
                    cancelAllTaskViewAnimations();
                }

                // TODO: Center the newly focused task view, only if not freeform
                float newScroll = mLayoutAlgorithm.getStackScrollForTask(newFocusedTask);
                if (Float.compare(newScroll, mStackScroller.getStackScroll()) != 0) {
                    mStackScroller.animateScroll(newScroll, focusTaskRunnable);
                    willScroll = true;
                } else {
                    focusTaskRunnable.run();
                }
                mLayoutAlgorithm.animateFocusState(TaskStackLayoutAlgorithm.STATE_FOCUSED);
            } else {
                focusTaskRunnable.run();
            }
        }
        return willScroll;
    }

    /**
     * Sets the focused task relative to the currently focused task.
     *
     * @param forward whether to go to the next task in the stack (along the curve) or the previous
     * @param stackTasksOnly if set, will ensure that the traversal only goes along stack tasks, and
     *                       if the currently focused task is not a stack task, will set the focus
     *                       to the first visible stack task
     * @param animated determines whether to actually draw the highlight along with the change in
     *                            focus.
     */
    public void setRelativeFocusedTask(boolean forward, boolean stackTasksOnly, boolean animated) {
        setRelativeFocusedTask(forward, stackTasksOnly, animated, false);
    }

    /**
     * Sets the focused task relative to the currently focused task.
     *
     * @param forward whether to go to the next task in the stack (along the curve) or the previous
     * @param stackTasksOnly if set, will ensure that the traversal only goes along stack tasks, and
     *                       if the currently focused task is not a stack task, will set the focus
     *                       to the first visible stack task
     * @param animated determines whether to actually draw the highlight along with the change in
     *                            focus.
     * @param cancelWindowAnimations if set, will attempt to cancel window animations if a scroll
     *                               happens.
     */
    public void setRelativeFocusedTask(boolean forward, boolean stackTasksOnly, boolean animated,
                                       boolean cancelWindowAnimations) {
        setRelativeFocusedTask(forward, stackTasksOnly, animated, cancelWindowAnimations, 0);
    }

    /**
     * Sets the focused task relative to the currently focused task.
     *
     * @param forward whether to go to the next task in the stack (along the curve) or the previous
     * @param stackTasksOnly if set, will ensure that the traversal only goes along stack tasks, and
     *                       if the currently focused task is not a stack task, will set the focus
     *                       to the first visible stack task
     * @param animated determines whether to actually draw the highlight along with the change in
     *                            focus.
     * @param cancelWindowAnimations if set, will attempt to cancel window animations if a scroll
     *                               happens.
     * @param timerIndicatorDuration the duration to initialize the auto-advance timer indicator
     */
    public void setRelativeFocusedTask(boolean forward, boolean stackTasksOnly, boolean animated,
                                       boolean cancelWindowAnimations,
                                       int timerIndicatorDuration) {
        int newIndex = mStack.indexOfStackTask(mFocusedTask);
        if (mFocusedTask != null) {
            if (stackTasksOnly) {
                List<Task> tasks =  mStack.getStackTasks();
                if (mFocusedTask.isFreeformTask()) {
                    // Try and focus the front most stack task
                    TaskView tv = getFrontMostTaskView(stackTasksOnly);
                    if (tv != null) {
                        newIndex = mStack.indexOfStackTask(tv.getTask());
                    }
                } else {
                    // Try the next task if it is a stack task
                    int tmpNewIndex = newIndex + (forward ? -1 : 1);
                    if (0 <= tmpNewIndex && tmpNewIndex < tasks.size()) {
                        Task t = tasks.get(tmpNewIndex);
                        if (!t.isFreeformTask()) {
                            newIndex = tmpNewIndex;
                        }
                    }
                }
            } else {
                // No restrictions, lets just move to the new task (looping forward/backwards if
                // necessary)
                int taskCount = mStack.getTaskCount();
                newIndex = (newIndex + (forward ? -1 : 1) + taskCount) % taskCount;
            }
        } else {
            // We don't have a focused task, so focus the first visible task view
            TaskView tv = getFrontMostTaskView(stackTasksOnly);
            if (tv != null) {
                newIndex = mStack.indexOfStackTask(tv.getTask());
            }
        }
        if (newIndex != -1) {
            boolean willScroll = setFocusedTask(newIndex, true /* scrollToTask */,
                    true /* requestViewFocus */, timerIndicatorDuration);
            if (willScroll && cancelWindowAnimations) {
                // As we iterate to the next/previous task, cancel any current/lagging window
                // transition animations
                EventBus.getDefault().send(new CancelEnterRecentsWindowAnimationEvent(null));
            }
        }
    }

    /**
     * Resets the focused task.
     */
    void resetFocusedTask(Task task) {
        if (task != null) {
            TaskView tv = getChildViewForTask(task);
            if (tv != null) {
                tv.setFocusedState(false, false /* requestViewFocus */);
            }
        }
        mFocusedTask = null;
    }

    /**
     * Returns the focused task.
     */
    Task getFocusedTask() {
        return mFocusedTask;
    }

    @Override
    public void onInitializeAccessibilityEvent(AccessibilityEvent event) {
        super.onInitializeAccessibilityEvent(event);
        List<TaskView> taskViews = getTaskViews();
        int taskViewCount = taskViews.size();
        if (taskViewCount > 0) {
            TaskView backMostTask = taskViews.get(0);
            TaskView frontMostTask = taskViews.get(taskViewCount - 1);
            event.setFromIndex(mStack.indexOfStackTask(backMostTask.getTask()));
            event.setToIndex(mStack.indexOfStackTask(frontMostTask.getTask()));
            event.setContentDescription(frontMostTask.getTask().title);
        }
        event.setItemCount(mStack.getTaskCount());
        event.setScrollY(mStackScroller.mScroller.getCurrY());
        event.setMaxScrollY(mStackScroller.progressToScrollRange(mLayoutAlgorithm.mMaxScrollP));
    }

    @Override
    public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        List<TaskView> taskViews = getTaskViews();
        int taskViewCount = taskViews.size();
        if (taskViewCount > 1 && mFocusedTask != null) {
            info.setScrollable(true);
            int focusedTaskIndex = mStack.indexOfStackTask(mFocusedTask);
            if (focusedTaskIndex > 0) {
                info.addAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD);
            }
            if (focusedTaskIndex < mStack.getTaskCount() - 1) {
                info.addAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD);
            }
        }
    }

    @Override
    protected Parcelable onSaveInstanceState() {
        Bundle savedState = new Bundle();
        savedState.putParcelable(KEY_SAVED_STATE_SUPER, super.onSaveInstanceState());
        savedState.putFloat(KEY_SAVED_STATE_LAYOUT_FOCUSED_STATE, mLayoutAlgorithm.getFocusState());
        savedState.putFloat(KEY_SAVED_STATE_LAYOUT_STACK_SCROLL, mStackScroller.getStackScroll());
        return super.onSaveInstanceState();
    }

    @Override
    protected void onRestoreInstanceState(Parcelable state) {
        Bundle savedState = (Bundle) state;
        super.onRestoreInstanceState(savedState.getParcelable(KEY_SAVED_STATE_SUPER));

        mLayoutAlgorithm.setFocusState(savedState.getFloat(KEY_SAVED_STATE_LAYOUT_FOCUSED_STATE));
        mStackScroller.setStackScroll(savedState.getFloat(KEY_SAVED_STATE_LAYOUT_STACK_SCROLL));
    }

    @Override
    public CharSequence getAccessibilityClassName() {
        return TaskStackView.class.getName();
    }

    @Override
    public boolean performAccessibilityAction(int action, Bundle arguments) {
        if (super.performAccessibilityAction(action, arguments)) {
            return true;
        }
        switch (action) {
            case AccessibilityNodeInfo.ACTION_SCROLL_FORWARD: {
                setRelativeFocusedTask(true, false /* stackTasksOnly */, false /* animated */);
                return true;
            }
            case AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD: {
                setRelativeFocusedTask(false, false /* stackTasksOnly */, false /* animated */);
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        return mTouchHandler.onInterceptTouchEvent(ev);
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        return mTouchHandler.onTouchEvent(ev);
    }

    @Override
    public boolean onGenericMotionEvent(MotionEvent ev) {
        return mTouchHandler.onGenericMotionEvent(ev);
    }

    @Override
    public void computeScroll() {
        if (mStackScroller.computeScroll()) {
            // Notify accessibility
            sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_SCROLLED);
        }
        if (mDeferredTaskViewLayoutAnimation != null) {
            relayoutTaskViews(mDeferredTaskViewLayoutAnimation);
            mTaskViewsClipDirty = true;
            mDeferredTaskViewLayoutAnimation = null;
        }
        if (mTaskViewsClipDirty) {
            clipTaskViews();
        }
    }

    /**
     * This is ONLY used from the Recents component to update the dummy stack view for purposes
     * of getting the task rect to animate to.
     */
    public void updateLayoutForStack(TaskStack stack) {
        mStack = stack;
        updateLayoutAlgorithm(false /* boundScroll */, EMPTY_TASK_SET);
    }

    /**
     * Computes the maximum number of visible tasks and thumbnails. Requires that
     * updateLayoutForStack() is called first.
     */
    public TaskStackLayoutAlgorithm.VisibilityReport computeStackVisibilityReport() {
        return mLayoutAlgorithm.computeStackVisibilityReport(mStack.getStackTasks());
    }

    /**
     * Updates the expected task stack bounds for this stack view.
     */
    public void setTaskStackBounds(Rect taskStackBounds, Rect systemInsets) {
        // We can get spurious measure passes with the old bounds when docking, and since we are
        // using the current stack bounds during drag and drop, don't overwrite them until we
        // actually get new bounds
        boolean requiresLayout = false;
        if (!taskStackBounds.equals(mStableStackBounds)) {
            mStableStackBounds.set(taskStackBounds);
            mStackBounds.set(taskStackBounds);
            requiresLayout = true;
        }
        if (!systemInsets.equals(mLayoutAlgorithm.mSystemInsets)) {
            mLayoutAlgorithm.setSystemInsets(systemInsets);
            requiresLayout = true;
        }
        if (requiresLayout) {
            requestLayout();
        }
    }

    /**
     * This is called with the full window width and height to allow stack view children to
     * perform the full screen transition down.
     */
    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        mInMeasureLayout = true;
        int width = MeasureSpec.getSize(widthMeasureSpec);
        int height = MeasureSpec.getSize(heightMeasureSpec);

        // Compute the rects in the stack algorithm
        mLayoutAlgorithm.initialize(mStackBounds,
                TaskStackLayoutAlgorithm.StackState.getStackStateForStack(mStack));
        updateLayoutAlgorithm(false /* boundScroll */, EMPTY_TASK_SET);

        // If this is the first layout, then scroll to the front of the stack, then update the
        // TaskViews with the stack so that we can lay them out
        if (mAwaitingFirstLayout) {
            mStackScroller.setStackScrollToInitialState();
        }
        // Rebind all the views, including the ignore ones
        bindVisibleTaskViews(mStackScroller.getStackScroll(), EMPTY_TASK_SET);

        // Measure each of the TaskViews
        mTmpTaskViews.clear();
        mTmpTaskViews.addAll(getTaskViews());
        mTmpTaskViews.addAll(mViewPool.getViews());
        int taskViewCount = mTmpTaskViews.size();
        for (int i = 0; i < taskViewCount; i++) {
            measureTaskView(mTmpTaskViews.get(i));
        }

        setMeasuredDimension(width, height);
        mInMeasureLayout = false;
    }

    /**
     * Measures a TaskView.
     */
    private void measureTaskView(TaskView tv) {
        if (tv.getBackground() != null) {
            tv.getBackground().getPadding(mTmpRect);
        } else {
            mTmpRect.setEmpty();
        }
        tv.measure(
                MeasureSpec.makeMeasureSpec(
                        mLayoutAlgorithm.mTaskRect.width() + mTmpRect.left + mTmpRect.right,
                        MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(
                        mLayoutAlgorithm.mTaskRect.height() + mTmpRect.top + mTmpRect.bottom,
                        MeasureSpec.EXACTLY));
    }

    /**
     * This is called with the size of the space not including the top or right insets, or the
     * search bar height in portrait (but including the search bar width in landscape, since we want
     * to draw under it.
     */
    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        // Layout each of the TaskViews
        mTmpTaskViews.clear();
        mTmpTaskViews.addAll(getTaskViews());
        mTmpTaskViews.addAll(mViewPool.getViews());
        int taskViewCount = mTmpTaskViews.size();
        for (int i = 0; i < taskViewCount; i++) {
            layoutTaskView(mTmpTaskViews.get(i));
        }

        if (changed) {
            if (mStackScroller.isScrollOutOfBounds()) {
                mStackScroller.boundScroll();
            }
        }
        // Relayout all of the task views including the ignored ones
        relayoutTaskViews(AnimationProps.IMMEDIATE, EMPTY_TASK_SET);
        clipTaskViews();

        if (mAwaitingFirstLayout || !mEnterAnimationComplete) {
            mAwaitingFirstLayout = false;
            onFirstLayout();
        }
    }

    /**
     * Lays out a TaskView.
     */
    private void layoutTaskView(TaskView tv) {
        if (tv.getBackground() != null) {
            tv.getBackground().getPadding(mTmpRect);
        } else {
            mTmpRect.setEmpty();
        }
        Rect taskRect = mLayoutAlgorithm.mTaskRect;
        tv.layout(taskRect.left - mTmpRect.left, taskRect.top - mTmpRect.top,
                taskRect.right + mTmpRect.right, taskRect.bottom + mTmpRect.bottom);
    }

    /** Handler for the first layout. */
    void onFirstLayout() {
        // Setup the view for the enter animation
        mAnimationHelper.prepareForEnterAnimation();

        // Animate in the freeform workspace
        int ffBgAlpha = mLayoutAlgorithm.getStackState().freeformBackgroundAlpha;
        animateFreeformWorkspaceBackgroundAlpha(ffBgAlpha, new AnimationProps(150,
                Interpolators.FAST_OUT_SLOW_IN));

        // Set the task focused state without requesting view focus, and leave the focus animations
        // until after the enter-animation
        RecentsConfiguration config = Recents.getConfiguration();
        RecentsActivityLaunchState launchState = config.getLaunchState();
        int focusedTaskIndex = launchState.getInitialFocusTaskIndex(mStack.getTaskCount());
        if (focusedTaskIndex != -1) {
            setFocusedTask(focusedTaskIndex, false /* scrollToTask */,
                    false /* requestViewFocus */);
        }

        // Update the history button visibility
        if (shouldShowHistoryButton() &&
                mStackScroller.getStackScroll() < SHOW_HISTORY_BUTTON_SCROLL_THRESHOLD) {
            EventBus.getDefault().send(new ShowHistoryButtonEvent(false /* translate */));
        } else {
            EventBus.getDefault().send(new HideHistoryButtonEvent());
        }
    }

    public boolean isTouchPointInView(float x, float y, TaskView tv) {
        mTmpRect.set(tv.getLeft(), tv.getTop(), tv.getRight(), tv.getBottom());
        mTmpRect.offset((int) tv.getTranslationX(), (int) tv.getTranslationY());
        return mTmpRect.contains((int) x, (int) y);
    }

    /**
     * Returns a non-ignored task in the {@param tasks} list that can be used as an achor when
     * calculating the scroll position before and after a layout change.
     */
    public Task findAnchorTask(List<Task> tasks, MutableBoolean isFrontMostTask) {
        for (int i = tasks.size() - 1; i >= 0; i--) {
            Task task = tasks.get(i);

            // Ignore deleting tasks
            if (mIgnoreTasks.contains(task.key)) {
                if (i == tasks.size() - 1) {
                    isFrontMostTask.value = true;
                }
                continue;
            }
            return task;
        }
        return null;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        // Draw the freeform workspace background
        SystemServicesProxy ssp = Recents.getSystemServices();
        if (ssp.hasFreeformWorkspaceSupport()) {
            if (mFreeformWorkspaceBackground.getAlpha() > 0) {
                mFreeformWorkspaceBackground.draw(canvas);
            }
        }
    }

    @Override
    protected boolean verifyDrawable(Drawable who) {
        if (who == mFreeformWorkspaceBackground) {
            return true;
        }
        return super.verifyDrawable(who);
    }

    /**
     * Launches the freeform tasks.
     */
    public boolean launchFreeformTasks() {
        ArrayList<Task> tasks = mStack.getFreeformTasks();
        if (!tasks.isEmpty()) {
            Task frontTask = tasks.get(tasks.size() - 1);
            if (frontTask != null && frontTask.isFreeformTask()) {
                EventBus.getDefault().send(new LaunchTaskEvent(getChildViewForTask(frontTask),
                        frontTask, null, INVALID_STACK_ID, false));
                return true;
            }
        }
        return false;
    }

    /**** TaskStackCallbacks Implementation ****/

    @Override
    public void onStackTaskAdded(TaskStack stack, Task newTask) {
        // Update the min/max scroll and animate other task views into their new positions
        updateLayoutAlgorithm(true /* boundScroll */);

        // Animate all the tasks into place
        relayoutTaskViews(new AnimationProps(DEFAULT_SYNC_STACK_DURATION,
                Interpolators.FAST_OUT_SLOW_IN));
    }

    /**
     * We expect that the {@link TaskView} associated with the removed task is already hidden.
     */
    @Override
    public void onStackTaskRemoved(TaskStack stack, Task removedTask, boolean wasFrontMostTask,
            Task newFrontMostTask, AnimationProps animation) {
        if (mFocusedTask == removedTask) {
            resetFocusedTask(removedTask);
        }

        // Remove the view associated with this task, we can't rely on updateTransforms
        // to work here because the task is no longer in the list
        TaskView tv = getChildViewForTask(removedTask);
        if (tv != null) {
            mViewPool.returnViewToPool(tv);
        }

        // Remove the task from the ignored set
        removeIgnoreTask(removedTask);

        // If requested, relayout with the given animation
        if (animation != null) {
            updateLayoutAlgorithm(true /* boundScroll */);
            relayoutTaskViews(animation);
        }

        // Update the new front most task's action button
        if (mScreenPinningEnabled && newFrontMostTask != null) {
            TaskView frontTv = getChildViewForTask(newFrontMostTask);
            if (frontTv != null) {
                frontTv.showActionButton(true /* fadeIn */, DEFAULT_SYNC_STACK_DURATION);
            }
        }

        // If there are no remaining tasks, then just close recents
        if (mStack.getTaskCount() == 0) {
            EventBus.getDefault().send(new AllTaskViewsDismissedEvent());
        }
    }

    @Override
    public void onHistoryTaskRemoved(TaskStack stack, Task removedTask,
            AnimationProps animation) {
        // To be implemented
    }

    /**** ViewPoolConsumer Implementation ****/

    @Override
    public TaskView createView(Context context) {
        return (TaskView) mInflater.inflate(R.layout.recents_task_view, this, false);
    }

    @Override
    public void prepareViewToEnterPool(TaskView tv) {
        final Task task = tv.getTask();

        // Report that this tasks's data is no longer being used
        Recents.getTaskLoader().unloadTaskData(task);

        // Reset the view properties and view state
        tv.resetViewProperties();
        tv.setFocusedState(false, false /* requestViewFocus */);
        tv.setClipViewInStack(false);
        if (mScreenPinningEnabled) {
            tv.hideActionButton(false /* fadeOut */, 0 /* duration */, false /* scaleDown */, null);
        }

        // Detach the view from the hierarchy
        detachViewFromParent(tv);
        // Update the task views list after removing the task view
        updateTaskViewsList();
    }

    @Override
    public void prepareViewToLeavePool(TaskView tv, Task task, boolean isNewView) {
        // Find the index where this task should be placed in the stack
        int taskIndex = mStack.indexOfStackTask(task);
        int insertIndex = findTaskViewInsertIndex(task, taskIndex);

        // Add/attach the view to the hierarchy
        if (isNewView) {
            if (mInMeasureLayout) {
                // If we are measuring the layout, then just add the view normally as it will be
                // laid out during the layout pass
                addView(tv, insertIndex);
            } else {
                // Otherwise, this is from a bindVisibleTaskViews() call outside the measure/layout
                // pass, and we should layout the new child ourselves
                ViewGroup.LayoutParams params = tv.getLayoutParams();
                if (params == null) {
                    params = generateDefaultLayoutParams();
                }
                addViewInLayout(tv, insertIndex, params, true /* preventRequestLayout */);
                measureTaskView(tv);
                layoutTaskView(tv);
            }
        } else {
            attachViewToParent(tv, insertIndex, tv.getLayoutParams());
        }
        // Update the task views list after adding the new task view
        updateTaskViewsList();

        // Rebind the task and request that this task's data be filled into the TaskView
        tv.onTaskBound(task);

        // Load the task data
        Recents.getTaskLoader().loadTaskData(task, true /* fetchAndInvalidateThumbnails */);

        // If the doze trigger has already fired, then update the state for this task view
        if (mUIDozeTrigger.hasTriggered()) {
            tv.setNoUserInteractionState();
        }

        // Set the new state for this view, including the callbacks and view clipping
        tv.setCallbacks(this);
        tv.setTouchEnabled(true);
        tv.setClipViewInStack(true);
        if (mFocusedTask == task) {
            tv.setFocusedState(true, false /* requestViewFocus */);
            if (mStartTimerIndicatorDuration > 0) {
                // The timer indicator couldn't be started before, so start it now
                tv.getHeaderView().startFocusTimerIndicator(mStartTimerIndicatorDuration);
                mStartTimerIndicatorDuration = 0;
            }
        }

        // Restore the action button visibility if it is the front most task view
        if (mScreenPinningEnabled && tv.getTask() ==
                mStack.getStackFrontMostTask(false /* includeFreeform */)) {
            tv.showActionButton(false /* fadeIn */, 0 /* fadeInDuration */);
        }
    }

    @Override
    public boolean hasPreferredData(TaskView tv, Task preferredData) {
        return (tv.getTask() == preferredData);
    }

    /**** TaskViewCallbacks Implementation ****/

    @Override
    public void onTaskViewClipStateChanged(TaskView tv) {
        if (!mTaskViewsClipDirty) {
            mTaskViewsClipDirty = true;
            invalidate();
        }
    }

    /**** TaskStackLayoutAlgorithm.TaskStackLayoutAlgorithmCallbacks ****/

    @Override
    public void onFocusStateChanged(float prevFocusState, float curFocusState) {
        if (mDeferredTaskViewLayoutAnimation == null) {
            mUIDozeTrigger.poke();
            relayoutTaskViewsOnNextFrame(AnimationProps.IMMEDIATE);
        }
    }

    /**** TaskStackViewScroller.TaskStackViewScrollerCallbacks ****/

    @Override
    public void onScrollChanged(float prevScroll, float curScroll, AnimationProps animation) {
        mUIDozeTrigger.poke();
        if (animation != null) {
            relayoutTaskViewsOnNextFrame(animation);
        }

        if (mEnterAnimationComplete) {
            if (shouldShowHistoryButton() &&
                    prevScroll > SHOW_HISTORY_BUTTON_SCROLL_THRESHOLD &&
                    curScroll <= SHOW_HISTORY_BUTTON_SCROLL_THRESHOLD) {
                EventBus.getDefault().send(new ShowHistoryButtonEvent(true /* translate */));
            } else if (prevScroll < HIDE_HISTORY_BUTTON_SCROLL_THRESHOLD &&
                    curScroll >= HIDE_HISTORY_BUTTON_SCROLL_THRESHOLD) {
                EventBus.getDefault().send(new HideHistoryButtonEvent());
            }
        }
    }

    /**** EventBus Events ****/

    public final void onBusEvent(PackagesChangedEvent event) {
        // Compute which components need to be removed
        ArraySet<ComponentName> removedComponents = mStack.computeComponentsRemoved(
                event.packageName, event.userId);

        // For other tasks, just remove them directly if they no longer exist
        ArrayList<Task> tasks = mStack.getStackTasks();
        for (int i = tasks.size() - 1; i >= 0; i--) {
            final Task t = tasks.get(i);
            if (removedComponents.contains(t.key.getComponent())) {
                final TaskView tv = getChildViewForTask(t);
                if (tv != null) {
                    // For visible children, defer removing the task until after the animation
                    tv.dismissTask();
                } else {
                    // Otherwise, remove the task from the stack immediately
                    mStack.removeTask(t, AnimationProps.IMMEDIATE);
                }
            }
        }
    }

    public final void onBusEvent(LaunchTaskEvent event) {
        // Cancel any doze triggers once a task is launched
        mUIDozeTrigger.stopDozing();
    }

    public final void onBusEvent(LaunchNextTaskRequestEvent event) {
        int launchTaskIndex = mStack.indexOfStackTask(mStack.getLaunchTarget());
        if (launchTaskIndex != -1) {
            launchTaskIndex = Math.max(0, launchTaskIndex - 1);
        } else {
            launchTaskIndex = mStack.getTaskCount() - 1;
        }
        if (launchTaskIndex != -1) {
            // Stop all animations
            mUIDozeTrigger.stopDozing();
            cancelAllTaskViewAnimations();

            Task launchTask = mStack.getStackTasks().get(launchTaskIndex);
            EventBus.getDefault().send(new LaunchTaskEvent(getChildViewForTask(launchTask),
                    launchTask, null, INVALID_STACK_ID, false /* screenPinningRequested */));
        }
    }

    public final void onBusEvent(LaunchTaskStartedEvent event) {
        mAnimationHelper.startLaunchTaskAnimation(event.taskView, event.screenPinningRequested,
                event.getAnimationTrigger());
    }

    public final void onBusEvent(DismissRecentsToHomeAnimationStarted event) {
        // Stop any scrolling
        mStackScroller.stopScroller();
        mStackScroller.stopBoundScrollAnimation();

        // Start the task animations
        mAnimationHelper.startExitToHomeAnimation(event.animated, event.getAnimationTrigger());

        // Dismiss the freeform workspace background
        int taskViewExitToHomeDuration = TaskStackAnimationHelper.EXIT_TO_HOME_TRANSLATION_DURATION;
        animateFreeformWorkspaceBackgroundAlpha(0, new AnimationProps(taskViewExitToHomeDuration,
                Interpolators.FAST_OUT_SLOW_IN));
    }

    public final void onBusEvent(DismissFocusedTaskViewEvent event) {
        if (mFocusedTask != null) {
            TaskView tv = getChildViewForTask(mFocusedTask);
            if (tv != null) {
                tv.dismissTask();
            }
            resetFocusedTask(mFocusedTask);
        }
    }

    public final void onBusEvent(final DismissTaskViewEvent event) {
        // For visible children, defer removing the task until after the animation
        mAnimationHelper.startDeleteTaskAnimation(event.task, event.taskView,
                event.getAnimationTrigger());
    }

    public final void onBusEvent(TaskViewDismissedEvent event) {
        removeTaskViewFromStack(event.taskView, event.task);
        EventBus.getDefault().send(new DeleteTaskDataEvent(event.task));

        MetricsLogger.action(getContext(), MetricsEvent.OVERVIEW_DISMISS,
                event.task.key.getComponent().toString());
    }

    public final void onBusEvent(FocusNextTaskViewEvent event) {
        setRelativeFocusedTask(true, false /* stackTasksOnly */, true /* animated */, false,
                event.timerIndicatorDuration);
    }

    public final void onBusEvent(FocusPreviousTaskViewEvent event) {
        setRelativeFocusedTask(false, false /* stackTasksOnly */, true /* animated */);
    }

    public final void onBusEvent(UserInteractionEvent event) {
        // Poke the doze trigger on user interaction
        mUIDozeTrigger.poke();

        RecentsDebugFlags debugFlags = Recents.getDebugFlags();
        if (debugFlags.isFastToggleRecentsEnabled() && mFocusedTask != null) {
            TaskView tv = getChildViewForTask(mFocusedTask);
            if (tv != null) {
                tv.getHeaderView().cancelFocusTimerIndicator();
            }
        }
    }

    public final void onBusEvent(RecentsVisibilityChangedEvent event) {
        if (!event.visible) {
            reset();
        }
    }

    public final void onBusEvent(DragStartEvent event) {
        // Ensure that the drag task is not animated
        addIgnoreTask(event.task);

        if (event.task.isFreeformTask()) {
            // Animate to the front of the stack
            mStackScroller.animateScroll(mLayoutAlgorithm.mInitialScrollP, null);
        }

        // Enlarge the dragged view slightly
        float finalScale = event.taskView.getScaleX() * DRAG_SCALE_FACTOR;
        mLayoutAlgorithm.getStackTransform(event.task, getScroller().getStackScroll(),
                mTmpTransform, null);
        mTmpTransform.scale = finalScale;
        mTmpTransform.translationZ = mLayoutAlgorithm.mMaxTranslationZ + 1;
        updateTaskViewToTransform(event.taskView, mTmpTransform,
                new AnimationProps(DRAG_SCALE_DURATION, Interpolators.FAST_OUT_SLOW_IN));
    }

    public final void onBusEvent(DragStartInitializeDropTargetsEvent event) {
        SystemServicesProxy ssp = Recents.getSystemServices();
        if (ssp.hasFreeformWorkspaceSupport()) {
            event.handler.registerDropTargetForCurrentDrag(mStackDropTarget);
            event.handler.registerDropTargetForCurrentDrag(mFreeformWorkspaceDropTarget);
        }
    }

    public final void onBusEvent(DragDropTargetChangedEvent event) {
        AnimationProps animation = new AnimationProps(250, Interpolators.FAST_OUT_SLOW_IN);
        if (event.dropTarget instanceof TaskStack.DockState) {
            // Calculate the new task stack bounds that matches the window size that Recents will
            // have after the drop
            final TaskStack.DockState dockState = (TaskStack.DockState) event.dropTarget;
            mStackBounds.set(dockState.getDockedTaskStackBounds(getMeasuredWidth(),
                    getMeasuredHeight(), mDividerSize, mLayoutAlgorithm.mSystemInsets,
                    getResources()));
            mLayoutAlgorithm.initialize(mStackBounds,
                    TaskStackLayoutAlgorithm.StackState.getStackStateForStack(mStack));
            updateLayoutAlgorithm(true /* boundScroll */);
        } else {
            // Restore the pre-drag task stack bounds, but ensure that we don't layout the dragging
            // task view, so add it back to the ignore set after updating the layout
            mStackBounds.set(mStableStackBounds);
            removeIgnoreTask(event.task);
            mLayoutAlgorithm.initialize(mStackBounds,
                    TaskStackLayoutAlgorithm.StackState.getStackStateForStack(mStack));
            updateLayoutAlgorithm(true /* boundScroll */);
            addIgnoreTask(event.task);
        }
        relayoutTaskViews(animation);
    }

    public final void onBusEvent(final DragEndEvent event) {
        // We don't handle drops on the dock regions
        if (event.dropTarget instanceof TaskStack.DockState) {
            return;
        }

        boolean isFreeformTask = event.task.isFreeformTask();
        boolean hasChangedStacks =
                (!isFreeformTask && event.dropTarget == mFreeformWorkspaceDropTarget) ||
                        (isFreeformTask && event.dropTarget == mStackDropTarget);

        if (hasChangedStacks) {
            // Move the task to the right position in the stack (ie. the front of the stack if
            // freeform or the front of the stack if fullscreen). Note, we MUST move the tasks
            // before we update their stack ids, otherwise, the keys will have changed.
            if (event.dropTarget == mFreeformWorkspaceDropTarget) {
                mStack.moveTaskToStack(event.task, FREEFORM_WORKSPACE_STACK_ID);
            } else if (event.dropTarget == mStackDropTarget) {
                mStack.moveTaskToStack(event.task, FULLSCREEN_WORKSPACE_STACK_ID);
            }
            updateLayoutAlgorithm(true /* boundScroll */);

            // Move the task to the new stack in the system after the animation completes
            event.addPostAnimationCallback(new Runnable() {
                @Override
                public void run() {
                    SystemServicesProxy ssp = Recents.getSystemServices();
                    ssp.moveTaskToStack(event.task.key.id, event.task.key.stackId);
                }
            });
        }

        // We translated the view but we need to animate it back from the current layout-space rect
        // to its final layout-space rect
        int x = (int) event.taskView.getTranslationX();
        int y = (int) event.taskView.getTranslationY();
        Rect taskViewRect = new Rect(event.taskView.getLeft(), event.taskView.getTop(),
                event.taskView.getRight(), event.taskView.getBottom());
        taskViewRect.offset(x, y);
        event.taskView.setTranslationX(0);
        event.taskView.setTranslationY(0);
        event.taskView.setLeftTopRightBottom(taskViewRect.left, taskViewRect.top,
                taskViewRect.right, taskViewRect.bottom);

        // Animate all the TaskViews back into position
        mLayoutAlgorithm.getStackTransform(event.task, getScroller().getStackScroll(),
                mTmpTransform, null);
        event.getAnimationTrigger().increment();
        relayoutTaskViews(new AnimationProps(DEFAULT_SYNC_STACK_DURATION,
                Interpolators.FAST_OUT_SLOW_IN));
        updateTaskViewToTransform(event.taskView, mTmpTransform,
                new AnimationProps(DEFAULT_SYNC_STACK_DURATION, Interpolators.FAST_OUT_SLOW_IN,
                        event.getAnimationTrigger().decrementOnAnimationEnd()));
        removeIgnoreTask(event.task);
    }

    public final void onBusEvent(StackViewScrolledEvent event) {
        mLayoutAlgorithm.updateFocusStateOnScroll(event.yMovement.value);
    }

    public final void onBusEvent(IterateRecentsEvent event) {
        if (!mEnterAnimationComplete) {
            // Cancel the previous task's window transition before animating the focused state
            EventBus.getDefault().send(new CancelEnterRecentsWindowAnimationEvent(null));
        }
    }

    public final void onBusEvent(EnterRecentsWindowAnimationCompletedEvent event) {
        mEnterAnimationComplete = true;

        if (mStack.getTaskCount() > 0) {
            // Start the task enter animations
            mAnimationHelper.startEnterAnimation(event.getAnimationTrigger());

            // Add a runnable to the post animation ref counter to clear all the views
            event.addPostAnimationCallback(new Runnable() {
                @Override
                public void run() {
                    // Start the dozer to trigger to trigger any UI that shows after a timeout
                    mUIDozeTrigger.startDozing();

                    // Update the focused state here -- since we only set the focused task without
                    // requesting view focus in onFirstLayout(), actually request view focus and
                    // animate the focused state if we are alt-tabbing now, after the window enter
                    // animation is completed
                    if (mFocusedTask != null) {
                        RecentsConfiguration config = Recents.getConfiguration();
                        RecentsActivityLaunchState launchState = config.getLaunchState();
                        setFocusedTask(mStack.indexOfStackTask(mFocusedTask),
                                false /* scrollToTask */, launchState.launchedWithAltTab);
                    }

                    EventBus.getDefault().send(new EnterRecentsTaskStackAnimationCompletedEvent());
                }
            });
        }
    }

    public final void onBusEvent(UpdateFreeformTaskViewVisibilityEvent event) {
        List<TaskView> taskViews = getTaskViews();
        int taskViewCount = taskViews.size();
        for (int i = 0; i < taskViewCount; i++) {
            TaskView tv = taskViews.get(i);
            Task task = tv.getTask();
            if (task.isFreeformTask()) {
                tv.setVisibility(event.visible ? View.VISIBLE : View.INVISIBLE);
            }
        }
    }

    public final void onBusEvent(ShowHistoryEvent event) {
        ReferenceCountedTrigger postAnimTrigger = new ReferenceCountedTrigger();
        postAnimTrigger.addLastDecrementRunnable(new Runnable() {
            @Override
            public void run() {
                setVisibility(View.INVISIBLE);
            }
        });
        mAnimationHelper.startShowHistoryAnimation(postAnimTrigger);
    }

    public final void onBusEvent(HideHistoryEvent event) {
        setVisibility(View.VISIBLE);
        mAnimationHelper.startHideHistoryAnimation();
    }

    public final void onBusEvent(TaskStackUpdatedEvent event) {
        // Scroll the stack to the front after it has been updated
        event.addPostAnimationCallback(new Runnable() {
            @Override
            public void run() {
                mStackScroller.animateScroll(mLayoutAlgorithm.mMaxScrollP,
                        null /* postScrollRunnable */);
            }
        });
    }

    /**
     * Removes the task from the stack, and updates the focus to the next task in the stack if the
     * removed TaskView was focused.
     */
    private void removeTaskViewFromStack(TaskView tv, Task task) {
        // Announce for accessibility
        tv.announceForAccessibility(getContext().getString(
                R.string.accessibility_recents_item_dismissed, task.title));

        // Remove the task from the stack
        mStack.removeTask(task, new AnimationProps(DEFAULT_SYNC_STACK_DURATION,
                Interpolators.FAST_OUT_SLOW_IN));
    }

    /**
     * Starts an alpha animation on the freeform workspace background.
     */
    private void animateFreeformWorkspaceBackgroundAlpha(int targetAlpha,
            AnimationProps animation) {
        if (mFreeformWorkspaceBackground.getAlpha() == targetAlpha) {
            return;
        }

        Utilities.cancelAnimationWithoutCallbacks(mFreeformWorkspaceBackgroundAnimator);
        mFreeformWorkspaceBackgroundAnimator = ObjectAnimator.ofInt(mFreeformWorkspaceBackground,
                Utilities.DRAWABLE_ALPHA, mFreeformWorkspaceBackground.getAlpha(), targetAlpha);
        mFreeformWorkspaceBackgroundAnimator.setStartDelay(
                animation.getDuration(AnimationProps.ALPHA));
        mFreeformWorkspaceBackgroundAnimator.setDuration(
                animation.getDuration(AnimationProps.ALPHA));
        mFreeformWorkspaceBackgroundAnimator.setInterpolator(
                animation.getInterpolator(AnimationProps.ALPHA));
        mFreeformWorkspaceBackgroundAnimator.start();
    }

    /**
     * Returns the insert index for the task in the current set of task views. If the given task
     * is already in the task view list, then this method returns the insert index assuming it
     * is first removed at the previous index.
     *
     * @param task the task we are finding the index for
     * @param taskIndex the index of the task in the stack
     */
    private int findTaskViewInsertIndex(Task task, int taskIndex) {
        if (taskIndex != -1) {
            List<TaskView> taskViews = getTaskViews();
            boolean foundTaskView = false;
            int taskViewCount = taskViews.size();
            for (int i = 0; i < taskViewCount; i++) {
                Task tvTask = taskViews.get(i).getTask();
                if (tvTask == task) {
                    foundTaskView = true;
                } else if (taskIndex < mStack.indexOfStackTask(tvTask)) {
                    if (foundTaskView) {
                        return i - 1;
                    } else {
                        return i;
                    }
                }
            }
        }
        return -1;
    }

    /**
     * @return whether the history button should be visible
     */
    private boolean shouldShowHistoryButton() {
        return !mStack.getHistoricalTasks().isEmpty();
    }

    /**
     * Reads current system flags related to accessibility and screen pinning.
     */
    private void readSystemFlags() {
        SystemServicesProxy ssp = Recents.getSystemServices();
        mTouchExplorationEnabled = ssp.isTouchExplorationEnabled();
        mScreenPinningEnabled = ssp.getSystemSetting(getContext(),
                Settings.System.LOCK_TO_APP_ENABLED) != 0;
    }
}
