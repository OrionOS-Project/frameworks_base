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
package com.android.systemui.recents.tv.views;

import android.content.Context;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowInsets;
import android.widget.FrameLayout;

import com.android.systemui.R;
import com.android.systemui.recents.Recents;
import com.android.systemui.recents.RecentsActivity;
import com.android.systemui.recents.RecentsActivityLaunchState;
import com.android.systemui.recents.RecentsConfiguration;
import com.android.systemui.recents.events.EventBus;
import com.android.systemui.recents.events.activity.CancelEnterRecentsWindowAnimationEvent;
import com.android.systemui.recents.events.activity.DismissRecentsToHomeAnimationStarted;
import com.android.systemui.recents.events.activity.TaskStackUpdatedEvent;
import com.android.systemui.recents.events.component.RecentsVisibilityChangedEvent;
import com.android.systemui.recents.misc.SystemServicesProxy;
import com.android.systemui.recents.model.Task;
import com.android.systemui.recents.model.TaskStack;

import java.util.ArrayList;
import java.util.List;

/**
 * Top level layout of recents for TV. This will show the TaskStacks using a HorizontalGridView.
 */
public class RecentsTvView extends FrameLayout {

    private static final String TAG = "RecentsTvView";
    private static final boolean DEBUG = false;

    private TaskStack mStack;
    private TaskStackHorizontalGridView mTaskStackHorizontalView;
    private View mEmptyView;
    private boolean mAwaitingFirstLayout = true;
    private Rect mSystemInsets = new Rect();


    public RecentsTvView(Context context) {
        this(context, null);
    }

    public RecentsTvView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public RecentsTvView(Context context, AttributeSet attrs, int defStyleAttr) {
        this(context, attrs, defStyleAttr, 0);
    }

    public RecentsTvView(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);

        setWillNotDraw(false);

        LayoutInflater inflater = LayoutInflater.from(context);
        mEmptyView = inflater.inflate(R.layout.recents_empty, this, false);
        addView(mEmptyView);
    }

    public void setTaskStack(TaskStack stack) {
        RecentsConfiguration config = Recents.getConfiguration();
        RecentsActivityLaunchState launchState = config.getLaunchState();
        mStack = stack;

        if (mTaskStackHorizontalView != null) {
            mTaskStackHorizontalView.reset();
            mTaskStackHorizontalView.setStack(stack);
        } else {
            mTaskStackHorizontalView = (TaskStackHorizontalGridView) findViewById(R.id.task_list);
            mTaskStackHorizontalView.setStack(stack);
        }


        if (stack.getStackTaskCount() > 0) {
            hideEmptyView();
        } else {
            showEmptyView();
        }

        requestLayout();
    }

    public Task getNextTaskOrTopTask(Task taskToSearch) {
        Task returnTask = null;
        boolean found = false;
        if (mTaskStackHorizontalView != null) {
            TaskStack stack = mTaskStackHorizontalView.getStack();
            ArrayList<Task> taskList = stack.getStackTasks();
            // Iterate the stack views and try and find the focused task
            for (int j = taskList.size() - 1; j >= 0; --j) {
                Task task = taskList.get(j);
                // Return the next task in the line.
                if (found)
                    return task;
                // Remember the first possible task as the top task.
                if (returnTask == null)
                    returnTask = task;
                if (task == taskToSearch)
                    found = true;
            }
        }
        return returnTask;
    }

    public boolean launchFocusedTask() {
        if (mTaskStackHorizontalView != null) {
            Task task = mTaskStackHorizontalView.getFocusedTask();
            if (task != null) {
                SystemServicesProxy ssp = Recents.getSystemServices();
                ssp.startActivityFromRecents(getContext(), task.key.id, task.title, null);
                return true;
            }
        }
        return false;
    }

    /** Launches the task that recents was launched from if possible */
    public boolean launchPreviousTask() {
        if (mTaskStackHorizontalView != null) {
            TaskStack stack = mTaskStackHorizontalView.getStack();
            Task task = stack.getLaunchTarget();
            if (task != null) {
                SystemServicesProxy ssp = Recents.getSystemServices();
                ssp.startActivityFromRecents(getContext(), task.key.id, task.title, null);
                return true;
            }
        }
        return false;
    }

    /** Launches a given task. */
    public boolean launchTask(Task task, Rect taskBounds, int destinationStack) {
        if (mTaskStackHorizontalView != null) {
            // Iterate the stack views and try and find the given task.
            List<TaskCardView> taskViews = mTaskStackHorizontalView.getTaskViews();
            int taskViewCount = taskViews.size();
            for (int j = 0; j < taskViewCount; j++) {
                TaskCardView tv = taskViews.get(j);
                if (tv.getTask() == task) {
                    SystemServicesProxy ssp = Recents.getSystemServices();
                    ssp.startActivityFromRecents(getContext(), task.key.id, task.title, null);
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Hides the task stack and shows the empty view.
     */
    public void showEmptyView() {
        mEmptyView.setVisibility(View.VISIBLE);
        mEmptyView.bringToFront();
    }

    /**
     * Shows the task stack and hides the empty view.
     */
    public void hideEmptyView() {
        mEmptyView.setVisibility(View.INVISIBLE);
    }

    /**
     * Returns the last known system insets.
     */
    public Rect getSystemInsets() {
        return mSystemInsets;
    }

    @Override
    protected void onAttachedToWindow() {
        EventBus.getDefault().register(this, RecentsActivity.EVENT_BUS_PRIORITY + 1);
        super.onAttachedToWindow();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        EventBus.getDefault().unregister(this);
    }

    @Override
    public WindowInsets onApplyWindowInsets(WindowInsets insets) {
        mSystemInsets.set(insets.getSystemWindowInsets());
        requestLayout();
        return insets;
    }

    /**** EventBus Events ****/

    public final void onBusEvent(DismissRecentsToHomeAnimationStarted event) {
        // If we are going home, cancel the previous task's window transition
        EventBus.getDefault().send(new CancelEnterRecentsWindowAnimationEvent(null));
    }

    public final void onBusEvent(RecentsVisibilityChangedEvent event) {
        if (!event.visible) {
            // Reset the view state
            mAwaitingFirstLayout = true;
        }
    }

    public void setTaskStackViewAdapter(TaskStackHorizontalViewAdapter taskStackViewAdapter) {
        if(mTaskStackHorizontalView != null) {
            mTaskStackHorizontalView.setAdapter(taskStackViewAdapter);
        }
    }
}
