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
 * limitations under the License.
 */

package com.android.systemui.stackdivider;

import android.content.res.Configuration;
import android.os.RemoteException;
import android.view.IDockedStackListener;
import android.view.LayoutInflater;
import android.view.View;

import com.android.systemui.R;
import com.android.systemui.SystemUI;
import com.android.systemui.recents.Recents;
import com.android.systemui.recents.misc.SystemServicesProxy;

import static android.content.res.Configuration.ORIENTATION_LANDSCAPE;
import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;

/**
 * Controls the docked stack divider.
 */
public class Divider extends SystemUI {
    private static final String TAG = "Divider";
    private int mDividerWindowWidth;
    private DividerWindowManager mWindowManager;
    private DividerView mView;
    private DockDividerVisibilityListener mDockDividerVisibilityListener;
    private boolean mVisible = false;
    private boolean mMinimized = false;

    @Override
    public void start() {
        mWindowManager = new DividerWindowManager(mContext);
        mDividerWindowWidth = mContext.getResources().getDimensionPixelSize(
                com.android.internal.R.dimen.docked_stack_divider_thickness);
        update(mContext.getResources().getConfiguration());
        putComponent(Divider.class, this);
        mDockDividerVisibilityListener = new DockDividerVisibilityListener();
        SystemServicesProxy ssp = Recents.getSystemServices();
        ssp.registerDockedStackListener(mDockDividerVisibilityListener);
    }

    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        update(newConfig);
    }

    public DividerView getView() {
        return mView;
    }

    private void addDivider(Configuration configuration) {
        mView = (DividerView)
                LayoutInflater.from(mContext).inflate(R.layout.docked_stack_divider, null);
        mView.setVisibility(mVisible ? View.VISIBLE : View.INVISIBLE);
        final boolean landscape = configuration.orientation == ORIENTATION_LANDSCAPE;
        final int width = landscape ? mDividerWindowWidth : MATCH_PARENT;
        final int height = landscape ? MATCH_PARENT : mDividerWindowWidth;
        mWindowManager.add(mView, width, height);
        mView.setWindowManager(mWindowManager);
    }

    private void removeDivider() {
        mWindowManager.remove();
    }

    private void update(Configuration configuration) {
        removeDivider();
        addDivider(configuration);
        if (mMinimized) {
            mView.setMinimizedDockStack(true);
            mWindowManager.setTouchable(false);
        }
    }

    private void updateVisibility(final boolean visible) {
        mView.post(new Runnable() {
            @Override
            public void run() {
                if (mVisible != visible) {
                    mVisible = visible;
                    mView.setVisibility(visible ? View.VISIBLE : View.INVISIBLE);
                }
            }
        });
    }

    private void updateMinimizedDockedStack(final boolean minimized, final long animDuration) {
        mView.post(new Runnable() {
            @Override
            public void run() {
                if (mMinimized != minimized) {
                    mMinimized = minimized;
                    mWindowManager.setTouchable(!minimized);
                    if (animDuration > 0) {
                        mView.setMinimizedDockStack(minimized, animDuration);
                    } else {
                        mView.setMinimizedDockStack(minimized);
                    }
                }
            }
        });
    }

    class DockDividerVisibilityListener extends IDockedStackListener.Stub {

        @Override
        public void onDividerVisibilityChanged(boolean visible) throws RemoteException {
            updateVisibility(visible);
        }

        @Override
        public void onDockedStackExistsChanged(boolean exists) throws RemoteException {
        }

        @Override
        public void onDockedStackMinimizedChanged(boolean minimized, long animDuration)
                throws RemoteException {
            updateMinimizedDockedStack(minimized, animDuration);
        }
    }
}
