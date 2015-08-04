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

package com.android.documentsui;

import static com.android.documentsui.DirectoryFragment.ANIM_DOWN;
import static com.android.documentsui.DirectoryFragment.ANIM_NONE;
import static com.android.documentsui.DirectoryFragment.ANIM_UP;

import android.annotation.Nullable;
import android.app.Activity;
import android.app.FragmentManager;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.provider.DocumentsContract.Root;
import android.util.Log;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.BaseAdapter;
import android.widget.Spinner;
import android.widget.Toast;
import android.widget.Toolbar;

import com.android.documentsui.RecentsProvider.ResumeColumns;
import com.android.documentsui.model.DocumentInfo;
import com.android.documentsui.model.DocumentStack;
import com.android.documentsui.model.DurableUtils;
import com.android.documentsui.model.RootInfo;
import com.android.internal.util.Preconditions;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Standalone file management activity.
 */
public class StandaloneActivity extends BaseActivity {

    public static final String TAG = "StandaloneFileManagement";
    static final boolean DEBUG = false;

    private Toolbar mToolbar;
    private Spinner mToolbarStack;
    private Toolbar mRootsToolbar;
    private DirectoryContainerView mDirectoryContainer;
    private State mState;
    private ItemSelectedListener mStackListener;
    private BaseAdapter mStackAdapter;
    private DocumentClipper mClipper;

    public StandaloneActivity() {
        super(TAG);
    }

    @Override
    public void onCreate(Bundle icicle) {
        super.onCreate(icicle);

        setResult(Activity.RESULT_CANCELED);
        setContentView(R.layout.activity);

        final Context context = this;

        mDirectoryContainer = (DirectoryContainerView) findViewById(R.id.container_directory);

        mState = (icicle != null)
                ? icicle.<State> getParcelable(EXTRA_STATE)
                : buildDefaultState();

        mToolbar = (Toolbar) findViewById(R.id.toolbar);
        mToolbar.setTitleTextAppearance(context,
                android.R.style.TextAppearance_DeviceDefault_Widget_ActionBar_Title);

        mStackAdapter = new StackAdapter();
        mStackListener = new ItemSelectedListener();
        mToolbarStack = (Spinner) findViewById(R.id.stack);
        mToolbarStack.setOnItemSelectedListener(mStackListener);

        mRootsToolbar = (Toolbar) findViewById(R.id.roots_toolbar);
        if (mRootsToolbar != null) {
            mRootsToolbar.setTitleTextAppearance(context,
                    android.R.style.TextAppearance_DeviceDefault_Widget_ActionBar_Title);
        }

        setActionBar(mToolbar);

        mClipper = new DocumentClipper(this);

        RootsFragment.show(getFragmentManager(), null);
        if (!mState.restored) {
            new RestoreStackTask().execute();

            // Show a failure dialog if there was a failed operation.
            final Intent intent = getIntent();
            final DocumentStack dstStack = intent.getParcelableExtra(CopyService.EXTRA_STACK);
            final int failure = intent.getIntExtra(CopyService.EXTRA_FAILURE, 0);
            final int transferMode = intent.getIntExtra(CopyService.EXTRA_TRANSFER_MODE,
                    CopyService.TRANSFER_MODE_NONE);
            if (failure != 0) {
                final ArrayList<DocumentInfo> failedSrcList =
                        intent.getParcelableArrayListExtra(CopyService.EXTRA_SRC_LIST);
                FailureDialogFragment.show(getFragmentManager(), failure, failedSrcList, dstStack,
                        transferMode);
            }
        } else {
            onCurrentDirectoryChanged(ANIM_NONE);
        }
    }

    private State buildDefaultState() {
        State state = new State();

        final Intent intent = getIntent();
        state.action = State.ACTION_BROWSE_ALL;
        state.acceptMimes = new String[] { "*/*" };
        state.allowMultiple = true;
        state.acceptMimes = new String[] { intent.getType() };

        // These options are specific to the DocumentsActivity.
        Preconditions.checkArgument(
                !intent.hasExtra(Intent.EXTRA_LOCAL_ONLY));
        Preconditions.checkArgument(
                !intent.hasExtra(DocumentsContract.EXTRA_SHOW_ADVANCED));

        state.showAdvanced = LocalPreferences.getDisplayAdvancedDevices(this);
        state.showSize = LocalPreferences.getDisplayFileSize(this);

        final DocumentStack stack = intent.getParcelableExtra(CopyService.EXTRA_STACK);
        if (stack != null)
            state.stack = stack;

        return state;
    }

    @Override
    protected void onPostCreate(Bundle savedInstanceState) {
        super.onPostCreate(savedInstanceState);
        updateActionBar();
    }

    @Override
    public void updateActionBar() {
        final RootInfo root = getCurrentRoot();
        mToolbar.setNavigationIcon(
                root != null ? root.loadToolbarIcon(mToolbar.getContext()) : null);
        mToolbar.setNavigationContentDescription(R.string.drawer_open);
        mToolbar.setNavigationOnClickListener(null);

        if (mSearchManager.isExpanded()) {
            mToolbar.setTitle(null);
            mToolbarStack.setVisibility(View.GONE);
            mToolbarStack.setAdapter(null);
        } else {
            if (mState.stack.size() <= 1) {
                mToolbar.setTitle(root.title);
                mToolbarStack.setVisibility(View.GONE);
                mToolbarStack.setAdapter(null);
            } else {
                mToolbar.setTitle(null);
                mToolbarStack.setVisibility(View.VISIBLE);
                mToolbarStack.setAdapter(mStackAdapter);

                mStackListener.mIgnoreNextNavigation = true;
                mToolbarStack.setSelection(mStackAdapter.getCount() - 1);
            }
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        boolean showMenu = super.onCreateOptionsMenu(menu);

        expandMenus(menu);
        return showMenu;
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        boolean shown = super.onPrepareOptionsMenu(menu);

        menu.findItem(R.id.menu_file_size).setVisible(true);
        menu.findItem(R.id.menu_advanced).setVisible(true);

        final MenuItem pasteFromCb = menu.findItem(R.id.menu_paste_from_clipboard);
        final MenuItem createDir = menu.findItem(R.id.menu_create_dir);
        final MenuItem settings = menu.findItem(R.id.menu_settings);

        boolean canCreateDir = canCreateDirectory();

        createDir.setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER);
        createDir.setVisible(canCreateDir);

        settings.setVisible((getCurrentRoot().flags & Root.FLAG_HAS_SETTINGS) != 0);

        pasteFromCb.setVisible(true);
        pasteFromCb.setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER);
        pasteFromCb.setEnabled(mClipper.hasItemsToPaste());

        return shown;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        final int id = item.getItemId();
        if (id == R.id.menu_paste_from_clipboard) {
            DirectoryFragment dir = DirectoryFragment.get(getFragmentManager());
            dir = DirectoryFragment.get(getFragmentManager());
            dir.pasteFromClipboard();
            return true;
        }

        return super.onOptionsItemSelected(item);
    }

    @Override
    public void onBackPressed() {
        if (!mState.stackTouched) {
            super.onBackPressed();
            return;
        }

        final int size = mState.stack.size();
        if (size > 1) {
            mState.stack.pop();
            onCurrentDirectoryChanged(ANIM_UP);
        } else {
            super.onBackPressed();
        }
    }

    @Override
    public State getDisplayState() {
        return mState;
    }

    @Override
    void onDirectoryChanged(int anim) {
        final FragmentManager fm = getFragmentManager();
        final RootInfo root = getCurrentRoot();
        final DocumentInfo cwd = getCurrentDirectory();

        mDirectoryContainer.setDrawDisappearingFirst(anim == ANIM_DOWN);

        if (cwd == null) {
            DirectoryFragment.showRecentsOpen(fm, anim);

            // Start recents in grid when requesting visual things
            final boolean visualMimes = MimePredicate.mimeMatches(
                    MimePredicate.VISUAL_MIMES, mState.acceptMimes);
            mState.userMode = visualMimes ? State.MODE_GRID : State.MODE_LIST;
            mState.derivedMode = mState.userMode;
        } else {
            if (mState.currentSearch != null) {
                // Ongoing search
                DirectoryFragment.showSearch(fm, root, mState.currentSearch, anim);
            } else {
                // Normal boring directory
                DirectoryFragment.showNormal(fm, root, cwd, anim);
            }
        }
    }

    @Override
    public void onDocumentsPicked(List<DocumentInfo> docs) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void onDocumentPicked(DocumentInfo doc, @Nullable DocumentContext siblings) {
        if (doc.isDirectory()) {
            openDirectory(doc);
        } else {
            openDocument(doc, siblings);
        }
    }

    /**
     * Launches an intent to view the specified document.
     */
    private void openDocument(DocumentInfo doc, @Nullable DocumentContext siblings) {
        Intent intent = null;
        if (siblings != null) {
            QuickViewIntentBuilder builder =
                    new QuickViewIntentBuilder(getPackageManager(), doc, siblings);
            intent = builder.build();
        }

        // fallback to traditional VIEW action...
        if (intent == null) {
            intent = new Intent(Intent.ACTION_VIEW);
            intent.setFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            intent.setData(doc.derivedUri);
        }

        if (DEBUG && intent.getClipData() != null) {
            Log.d(TAG, "Starting intent w/ clip data: " + intent.getClipData());
        }

        try {
            startActivity(intent);
        } catch (ActivityNotFoundException ex2) {
            Toast.makeText(this, R.string.toast_no_application, Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public boolean onKeyShortcut(int keyCode, KeyEvent event) {
        DirectoryFragment dir;
        switch (keyCode) {
            case KeyEvent.KEYCODE_A:
                dir = DirectoryFragment.get(getFragmentManager());
                dir.selectAllFiles();
                return true;
            case KeyEvent.KEYCODE_N:
                if (event.isShiftPressed() && canCreateDirectory()) {
                    showCreateDirectoryDialog();
                    return true;
                }
            case KeyEvent.KEYCODE_C:
                // TODO: Should be statically bound using alphabeticShortcut. See b/21330356.
                dir = DirectoryFragment.get(getFragmentManager());
                dir.copySelectedToClipboard();
                // TODO: Cancel action mode in directory fragment.
        }

        return super.onKeyUp(keyCode, event);
    }

    @Override
    void saveStackBlocking() {
        final ContentResolver resolver = getContentResolver();
        final ContentValues values = new ContentValues();

        final byte[] rawStack = DurableUtils.writeToArrayOrNull(
                getDisplayState().stack);

        // Remember location for next app launch
        final String packageName = getCallingPackageMaybeExtra();
        values.clear();
        values.put(ResumeColumns.STACK, rawStack);
        values.put(ResumeColumns.EXTERNAL, 0);
        resolver.insert(RecentsProvider.buildResume(packageName), values);
    }

    @Override
    void onTaskFinished(Uri... uris) {
        Log.d(TAG, "onFinished() " + Arrays.toString(uris));

        final Intent intent = new Intent();
        if (uris.length == 1) {
            intent.setData(uris[0]);
        } else if (uris.length > 1) {
            final ClipData clipData = new ClipData(
                    null, mState.acceptMimes, new ClipData.Item(uris[0]));
            for (int i = 1; i < uris.length; i++) {
                clipData.addItem(new ClipData.Item(uris[i]));
            }
            intent.setClipData(clipData);
        }

        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);

        setResult(Activity.RESULT_OK, intent);
        finish();
    }
}
