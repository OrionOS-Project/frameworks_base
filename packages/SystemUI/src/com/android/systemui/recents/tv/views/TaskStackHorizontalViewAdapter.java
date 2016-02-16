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

import android.app.Activity;
import android.app.ActivityManagerNative;
import android.support.v7.widget.RecyclerView;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import com.android.systemui.R;
import com.android.systemui.recents.model.Task;

import java.util.ArrayList;
import java.util.List;

public class TaskStackHorizontalViewAdapter extends
        RecyclerView.Adapter<TaskStackHorizontalViewAdapter.ViewHolder> {

    private static final String TAG = "TaskStackHorizontalViewAdapter";
    private List<Task> mTaskList;

    static class ViewHolder extends RecyclerView.ViewHolder implements View.OnClickListener{
        private TaskCardView mTaskCardView;
        private Task mTask;
        public ViewHolder(View v) {
            super(v);
            if(v instanceof TaskCardView) {
                mTaskCardView = (TaskCardView) v;
            }
        }

        public void init(Task task) {
            mTaskCardView.init(task);
            mTask = task;
            mTaskCardView.setOnClickListener(this);
        }

        @Override
        public void onClick(View v) {
            try {
                ActivityManagerNative.getDefault().startActivityFromRecents(mTask.key.id, null);
                ((Activity)(v.getContext())).finish();
            } catch (Exception e) {
                Log.e(TAG, v.getContext()
                        .getString(R.string.recents_launch_error_message, mTask.title), e);
            }

        }
    }

    public TaskStackHorizontalViewAdapter(List tasks) {
        mTaskList = new ArrayList<Task>(tasks);
    }

    public void setNewStackTasks(List tasks) {
        mTaskList.clear();
        mTaskList.addAll(tasks);
        notifyDataSetChanged();
    }
    @Override
    public TaskStackHorizontalViewAdapter.ViewHolder onCreateViewHolder(ViewGroup parent,
            int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.recents_task_card_view, parent, false);
        ViewHolder viewHolder = new ViewHolder(view);
        return viewHolder;
    }

    @Override
    public void onBindViewHolder(ViewHolder holder, int position) {
        holder.init(mTaskList.get(position));
    }

    @Override
    public int getItemCount() {
        return mTaskList.size();
    }
}
