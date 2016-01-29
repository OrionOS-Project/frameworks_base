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
package com.android.systemui.qs.customize;

import android.animation.Animator;
import android.content.ClipData;
import android.content.Context;
import android.content.DialogInterface;
import android.content.DialogInterface.OnCancelListener;
import android.content.DialogInterface.OnDismissListener;
import android.util.AttributeSet;
import android.util.Log;
import android.util.TypedValue;
import android.view.ContextThemeWrapper;
import android.view.DragEvent;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.Toolbar;
import android.widget.Toolbar.OnMenuItemClickListener;

import com.android.systemui.R;
import com.android.systemui.qs.QSDetailClipper;
import com.android.systemui.qs.QSTile.Host.Callback;
import com.android.systemui.qs.customize.DropButton.OnDropListener;
import com.android.systemui.qs.customize.TileAdapter.TileSelectedListener;
import com.android.systemui.statusbar.phone.PhoneStatusBar;
import com.android.systemui.statusbar.phone.QSTileHost;
import com.android.systemui.statusbar.phone.SystemUIDialog;

import java.util.ArrayList;

/**
 * Allows full-screen customization of QS, through show() and hide().
 *
 * This adds itself to the status bar window, so it can appear on top of quick settings and
 * *someday* do fancy animations to get into/out of it.
 */
public class QSCustomizer extends LinearLayout implements OnMenuItemClickListener, Callback,
        OnDropListener, OnClickListener, Animator.AnimatorListener, TileSelectedListener,
        OnCancelListener, OnDismissListener {

    private static final int MENU_SAVE = Menu.FIRST;
    private static final int MENU_RESET = Menu.FIRST + 1;
    private final QSDetailClipper mClipper;

    private PhoneStatusBar mPhoneStatusBar;

    private Toolbar mToolbar;
    private ViewGroup mDragButtons;
    private CustomQSPanel mQsPanel;

    private boolean isShown;
    private DropButton mInfoButton;
    private DropButton mRemoveButton;
    private FloatingActionButton mFab;
    private SystemUIDialog mDialog;
    private QSTileHost mHost;

    public QSCustomizer(Context context, AttributeSet attrs) {
        super(new ContextThemeWrapper(context, android.R.style.Theme_Material), attrs);
        mClipper = new QSDetailClipper(this);
    }

    public void setHost(QSTileHost host) {
        mHost = host;
        mHost.addCallback(this);
        mPhoneStatusBar = host.getPhoneStatusBar();
        mQsPanel.setTiles(mHost.getTiles());
        mQsPanel.setHost(mHost);
        mQsPanel.setSavedTiles();
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        mToolbar = (Toolbar) findViewById(com.android.internal.R.id.action_bar);
        TypedValue value = new TypedValue();
        mContext.getTheme().resolveAttribute(android.R.attr.homeAsUpIndicator, value, true);
        mToolbar.setNavigationIcon(
                getResources().getDrawable(R.drawable.ic_close_white, mContext.getTheme()));
        mToolbar.setNavigationOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                hide(0, 0);
            }
        });
        mToolbar.setOnMenuItemClickListener(this);
        mToolbar.getMenu().add(Menu.NONE, MENU_SAVE, 0, mContext.getString(R.string.save))
                .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
        mToolbar.getMenu().add(Menu.NONE, MENU_RESET, 0,
                mContext.getString(com.android.internal.R.string.reset));

        mQsPanel = (CustomQSPanel) findViewById(R.id.quick_settings_panel);

        mDragButtons = (ViewGroup) findViewById(R.id.drag_buttons);
        setDragging(false);

        mInfoButton = (DropButton) findViewById(R.id.info_button);
        mInfoButton.setOnDropListener(this);
        mRemoveButton = (DropButton) findViewById(R.id.remove_button);
        mRemoveButton.setOnDropListener(this);

        mFab = (FloatingActionButton) findViewById(R.id.fab);
        mFab.setImageResource(R.drawable.ic_add);
        mFab.setOnClickListener(this);
    }

    public void show(int x, int y) {
        isShown = true;
        mQsPanel.setSavedTiles();
        mPhoneStatusBar.getStatusBarWindow().addView(this);
        mQsPanel.setListening(true);
        mClipper.animateCircularClip(x, y, true, this);
    }

    public void hide(int x, int y) {
        isShown = false;
        mQsPanel.setListening(false);
        mClipper.animateCircularClip(x, y, false, this);
    }

    public boolean isCustomizing() {
        return isShown;
    }

    private void reset() {
        ArrayList<String> tiles = new ArrayList<>();
        String defTiles = mContext.getString(R.string.quick_settings_tiles_default);
        for (String tile : defTiles.split(",")) {
            tiles.add(tile);
        }
        mQsPanel.setTiles(tiles);
    }

    private void setDragging(boolean dragging) {
        mToolbar.setVisibility(!dragging ? View.VISIBLE : View.INVISIBLE);
    }

    private void save() {
        Log.d("CustomQSPanel", "Save!");
        mQsPanel.saveCurrentTiles();
        // TODO: At save button.
        hide(0, 0);
    }

    @Override
    public boolean onMenuItemClick(MenuItem item) {
        switch (item.getItemId()) {
            case MENU_SAVE:
                Log.d("CustomQSPanel", "Save...");
                save();
                break;
            case MENU_RESET:
                reset();
                break;
        }
        return true;
    }

    @Override
    public void onTileSelected(String spec) {
        if (mDialog != null) {
            mQsPanel.addTile(spec);
            mDialog.dismiss();
        }
    }

    @Override
    public void onTilesChanged() {
        mQsPanel.setTiles(mHost.getTiles());
    }

    public boolean onDragEvent(DragEvent event) {
        switch (event.getAction()) {
            case DragEvent.ACTION_DRAG_STARTED:
                setDragging(true);
                break;
            case DragEvent.ACTION_DRAG_ENDED:
                setDragging(false);
                break;
        }
        return true;
    }

    public void onDrop(View v, ClipData data) {
        if (v == mRemoveButton) {
            mQsPanel.remove(mQsPanel.getSpec(data));
        } else if (v == mInfoButton) {
            mQsPanel.unstashTiles();
            SystemUIDialog dialog = new SystemUIDialog(mContext);
            dialog.setTitle(mQsPanel.getSpec(data));
            dialog.setPositiveButton(R.string.ok, null);
            dialog.show();
        }
    }

    @Override
    public void onClick(View v) {
        if (mFab == v) {
            mDialog = new SystemUIDialog(mContext,
                    android.R.style.Theme_Material_Dialog);
            View view = LayoutInflater.from(mContext).inflate(R.layout.qs_add_tiles_list, null);
            ListView listView = (ListView) view.findViewById(android.R.id.list);
            TileAdapter adapter = new TileAdapter(mContext, mQsPanel.getTiles(), mHost);
            adapter.setListener(this);
            listView.setDivider(null);
            listView.setDividerHeight(0);
            listView.setAdapter(adapter);
            listView.setEmptyView(view.findViewById(R.id.empty_text));
            mDialog.setView(view);
            mDialog.setOnDismissListener(this);
            mDialog.setOnCancelListener(this);
            mDialog.show();
            // Too lazy to figure out what this will be now, but it should probably be something
            // besides just a dialog.
            // For now, just make it big.
            WindowManager.LayoutParams params = mDialog.getWindow().getAttributes();
            params.width = WindowManager.LayoutParams.MATCH_PARENT;
            params.height = WindowManager.LayoutParams.WRAP_CONTENT;
            mDialog.getWindow().setAttributes(params);
        }
    }

    @Override
    public void onDismiss(DialogInterface dialog) {
        mDialog = null;
    }

    @Override
    public void onCancel(DialogInterface dialog) {
        mDialog = null;
    }

    @Override
    public void onAnimationEnd(Animator animation) {
        if (!isShown) {
            mPhoneStatusBar.getStatusBarWindow().removeView(this);
        }
    }

    @Override
    public void onAnimationCancel(Animator animation) {
        if (!isShown) {
            mPhoneStatusBar.getStatusBarWindow().removeView(this);
        }
    }

    @Override
    public void onAnimationStart(Animator animation) {
        // Don't care.
    }

    @Override
    public void onAnimationRepeat(Animator animation) {
        // Don't care.
    }
}