/*
 * Copyright (C) 2013 The Android Open Source Project
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

import static com.android.documentsui.Shared.DEBUG;

import android.annotation.LayoutRes;
import android.app.Activity;
import android.app.Fragment;
import android.app.FragmentManager;
import android.app.FragmentTransaction;
import android.app.LoaderManager.LoaderCallbacks;
import android.content.Context;
import android.content.Intent;
import android.content.Loader;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Bundle;
import android.os.Looper;
import android.provider.DocumentsContract.Root;
import android.provider.DocumentsContract;
import android.provider.Settings;
import android.support.annotation.Nullable;
import android.text.TextUtils;
import android.text.format.Formatter;
import android.util.Log;
import android.view.ContextMenu;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.View.OnDragListener;
import android.view.View.OnGenericMotionListener;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.AdapterView.AdapterContextMenuInfo;
import android.widget.AdapterView.OnItemClickListener;
import android.widget.AdapterView.OnItemLongClickListener;
import android.widget.ArrayAdapter;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;

import com.android.documentsui.model.RootInfo;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Display list of known storage backend roots.
 */
public class RootsFragment extends Fragment implements ItemDragListener.DragHost {

    private static final String TAG = "RootsFragment";
    private static final String EXTRA_INCLUDE_APPS = "includeApps";

    private ListView mList;
    private RootsAdapter mAdapter;
    private LoaderCallbacks<Collection<RootInfo>> mCallbacks;

    public static void show(FragmentManager fm, Intent includeApps) {
        final Bundle args = new Bundle();
        args.putParcelable(EXTRA_INCLUDE_APPS, includeApps);

        final RootsFragment fragment = new RootsFragment();
        fragment.setArguments(args);

        final FragmentTransaction ft = fm.beginTransaction();
        ft.replace(R.id.container_roots, fragment);
        ft.commitAllowingStateLoss();
    }

    public static RootsFragment get(FragmentManager fm) {
        return (RootsFragment) fm.findFragmentById(R.id.container_roots);
    }

    @Override
    public View onCreateView(
            LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {

        final View view = inflater.inflate(R.layout.fragment_roots, container, false);
        mList = (ListView) view.findViewById(R.id.roots_list);
        mList.setOnItemClickListener(mItemListener);
        // For right-clicks, we want to trap the click and not pass it to OnClickListener
        // For all other clicks, we will pass the events down
        mList.setOnGenericMotionListener(
                new OnGenericMotionListener() {
            @Override
            public boolean onGenericMotion(View v, MotionEvent event) {
                if (Events.isMouseEvent(event)
                        && event.getButtonState() == MotionEvent.BUTTON_SECONDARY) {
                    registerForContextMenu(v);
                    v.showContextMenu(event.getX(), event.getY());
                    unregisterForContextMenu(v);
                    return true;
                }
                return false;
            }
        });
        mList.setChoiceMode(ListView.CHOICE_MODE_SINGLE);
        return view;
    }

    @Override
    public void onActivityCreated(Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);

        final Context context = getActivity();
        final RootsCache roots = DocumentsApplication.getRootsCache(context);
        final State state = ((BaseActivity) context).getDisplayState();

        mCallbacks = new LoaderCallbacks<Collection<RootInfo>>() {
            @Override
            public Loader<Collection<RootInfo>> onCreateLoader(int id, Bundle args) {
                return new RootsLoader(context, roots, state);
            }

            @Override
            public void onLoadFinished(
                    Loader<Collection<RootInfo>> loader, Collection<RootInfo> result) {
                if (!isAdded()) {
                    return;
                }

                Intent handlerAppIntent = getArguments().getParcelable(EXTRA_INCLUDE_APPS);

                mAdapter = new RootsAdapter(context, result, handlerAppIntent, state,
                        new ItemDragListener<>(RootsFragment.this));
                mList.setAdapter(mAdapter);

                onCurrentRootChanged();
            }

            @Override
            public void onLoaderReset(Loader<Collection<RootInfo>> loader) {
                mAdapter = null;
                mList.setAdapter(null);
            }
        };
    }

    @Override
    public void onResume() {
        super.onResume();
        onDisplayStateChanged();
    }

    public void onDisplayStateChanged() {
        final Context context = getActivity();
        final State state = ((BaseActivity) context).getDisplayState();

        if (state.action == State.ACTION_GET_CONTENT) {
            mList.setOnItemLongClickListener(mItemLongClickListener);
        } else {
            mList.setOnItemLongClickListener(null);
            mList.setLongClickable(false);
        }

        getLoaderManager().restartLoader(2, null, mCallbacks);
    }

    public void onCurrentRootChanged() {
        if (mAdapter == null) {
            return;
        }

        final RootInfo root = ((BaseActivity) getActivity()).getCurrentRoot();
        for (int i = 0; i < mAdapter.getCount(); i++) {
            final Object item = mAdapter.getItem(i);
            if (item instanceof RootItem) {
                final RootInfo testRoot = ((RootItem) item).root;
                if (Objects.equals(testRoot, root)) {
                    mList.setItemChecked(i, true);
                    return;
                }
            }
        }
    }

    /**
     * Attempts to shift focus back to the navigation drawer.
     */
    public void requestFocus() {
        mList.requestFocus();
    }

    private void showAppDetails(ResolveInfo ri) {
        final Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
        intent.setData(Uri.fromParts("package", ri.activityInfo.packageName, null));
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_WHEN_TASK_RESET);
        startActivity(intent);
    }

    private BaseActivity getBaseActivity() {
        return (BaseActivity) getActivity();
    }

    @Override
    public void runOnUiThread(Runnable runnable) {
        getActivity().runOnUiThread(runnable);
    }

    /**
     * {@inheritDoc}
     *
     * In RootsFragment we open the hovered root.
     */
    @Override
    public void onViewHovered(View view) {
        // SpacerView doesn't have DragListener so this view is guaranteed to be a RootItemView.
        RootItemView itemView = (RootItemView) view;
        itemView.drawRipple();

        final int position = (Integer) view.getTag(R.id.item_position_tag);
        final Item item = mAdapter.getItem(position);
        item.open(this);
    }

    @Override
    public void setDropTargetHighlight(View v, boolean highlight) {
        // SpacerView doesn't have DragListener so this view is guaranteed to be a RootItemView.
        RootItemView itemView = (RootItemView) v;
        itemView.setHighlight(highlight);
    }

    @Override
    public void onCreateContextMenu(
            ContextMenu menu, View v, ContextMenu.ContextMenuInfo menuInfo) {
        super.onCreateContextMenu(menu, v, menuInfo);
        AdapterContextMenuInfo adapterMenuInfo = (AdapterContextMenuInfo) menuInfo;
        final Item item = mAdapter.getItem(adapterMenuInfo.position);
        if (item instanceof RootItem) {
            RootItem rootItem = (RootItem) item;
            MenuInflater inflater = getActivity().getMenuInflater();
            inflater.inflate(R.menu.root_context_menu, menu);
            (getBaseActivity()).getMenuManager().updateRootContextMenu(menu, rootItem.root);
        }
    }

    @Override
    public boolean onContextItemSelected(MenuItem item) {
        AdapterContextMenuInfo adapterMenuInfo = (AdapterContextMenuInfo) item.getMenuInfo();
        final RootItem rootItem = (RootItem) mAdapter.getItem(adapterMenuInfo.position);
        switch(item.getItemId()) {
            case R.id.menu_eject_root:
                final View unmountIcon = adapterMenuInfo.targetView.findViewById(R.id.unmount_icon);
                ejectClicked(unmountIcon, rootItem.root);
                return true;
            case R.id.menu_settings:
                final RootInfo root = rootItem.root;
                getBaseActivity().openRootSettings(root);
                return true;
            default:
                if (DEBUG) Log.d(TAG, "Unhandled menu item selected: " + item);
                return false;
        }
    }

    private static void ejectClicked(View ejectIcon, RootInfo root) {
        assert(ejectIcon != null);
        assert(ejectIcon.getContext() instanceof BaseActivity);
        ejectIcon.setEnabled(false);
        root.ejecting = true;
        ejectRoot(
                ejectIcon,
                root.authority,
                root.rootId,
                new Consumer<Boolean>() {
                    @Override
                    public void accept(Boolean ejected) {
                        ejectIcon.setEnabled(!ejected);
                        root.ejecting = false;
                    }
                });
    }

    static void ejectRoot(
            View ejectIcon, String authority, String rootId, Consumer<Boolean> listener) {
        BooleanSupplier predicate = () -> {
            return !(ejectIcon.getVisibility() == View.VISIBLE);
        };
        new EjectRootTask(predicate::getAsBoolean,
                authority,
                rootId,
                ejectIcon.getContext(),
                listener).executeOnExecutor(ProviderExecutor.forAuthority(authority));
    }

    private OnItemClickListener mItemListener = new OnItemClickListener() {
        @Override
        public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
            final Item item = mAdapter.getItem(position);
            item.open(RootsFragment.this);

            ((BaseActivity) getActivity()).setRootsDrawerOpen(false);
        }
    };

    private OnItemLongClickListener mItemLongClickListener = new OnItemLongClickListener() {
        @Override
        public boolean onItemLongClick(AdapterView<?> parent, View view, int position, long id) {
            final Item item = mAdapter.getItem(position);
            return item.showAppDetails(RootsFragment.this);
        }
    };

    private static abstract class Item {
        private final @LayoutRes int mLayoutId;
        private final String mStringId;

        public Item(@LayoutRes int layoutId, String stringId) {
            mLayoutId = layoutId;
            mStringId = stringId;
        }

        public View getView(View convertView, ViewGroup parent) {
            if (convertView == null
                    || (Integer) convertView.getTag(R.id.layout_id_tag) != mLayoutId) {
                convertView = LayoutInflater.from(parent.getContext())
                        .inflate(mLayoutId, parent, false);
            }
            convertView.setTag(R.id.layout_id_tag, mLayoutId);
            bindView(convertView);
            return convertView;
        }

        boolean showAppDetails(RootsFragment fragment) {
            return false;
        }

        abstract void bindView(View convertView);

        abstract boolean isDropTarget();

        abstract void open(RootsFragment fragment);
    }

    private static class RootItem extends Item {
        private static final String STRING_ID_FORMAT = "RootItem{%s/%s}";

        public final RootInfo root;

        public RootItem(RootInfo root) {
            super(R.layout.item_root, getStringId(root));
            this.root = root;
        }

        private static String getStringId(RootInfo root) {
            // Empty URI authority is invalid, so we can use empty string if root.authority is null.
            // Directly passing null to String.format() will write "null" which can be a valid URI
            // authority.
            String authority = (root.authority == null ? "" : root.authority);
            return String.format(STRING_ID_FORMAT, authority, root.rootId);
        }

        @Override
        public void bindView(View convertView) {
            final ImageView icon = (ImageView) convertView.findViewById(android.R.id.icon);
            final TextView title = (TextView) convertView.findViewById(android.R.id.title);
            final TextView summary = (TextView) convertView.findViewById(android.R.id.summary);
            final ImageView unmountIcon = (ImageView) convertView.findViewById(R.id.unmount_icon);

            final Context context = convertView.getContext();
            icon.setImageDrawable(root.loadDrawerIcon(context));
            title.setText(root.title);

            if (root.supportsEject()) {
                unmountIcon.setVisibility(View.VISIBLE);
                unmountIcon.setImageDrawable(root.loadEjectIcon(context));
                unmountIcon.setOnClickListener(new OnClickListener() {
                    @Override
                    public void onClick(View unmountIcon) {
                        RootsFragment.ejectClicked(unmountIcon, root);
                    }
                });
            } else {
                unmountIcon.setVisibility(View.GONE);
                unmountIcon.setOnClickListener(null);
            }
            // Show available space if no summary
            String summaryText = root.summary;
            if (TextUtils.isEmpty(summaryText) && root.availableBytes >= 0) {
                summaryText = context.getString(R.string.root_available_bytes,
                        Formatter.formatFileSize(context, root.availableBytes));
            }

            summary.setText(summaryText);
            summary.setVisibility(TextUtils.isEmpty(summaryText) ? View.GONE : View.VISIBLE);
        }

        @Override
        boolean isDropTarget() {
            return root.supportsCreate() && !root.isLibrary();
        }

        @Override
        void open(RootsFragment fragment) {
            BaseActivity activity = BaseActivity.get(fragment);
            Metrics.logRootVisited(fragment.getActivity(), root);
            activity.onRootPicked(root);
        }
    }

    private static class SpacerItem extends Item {
        private static final String STRING_ID = "SpacerItem";

        public SpacerItem() {
            // Multiple spacer items can share the same string id as they're identical.
            super(R.layout.item_root_spacer, STRING_ID);
        }

        @Override
        void bindView(View convertView) {
            // Nothing to bind
        }

        @Override
        boolean isDropTarget() {
            return false;
        }

        @Override
        void open(RootsFragment fragment) {
            if (DEBUG) Log.d(TAG, "Ignoring click/hover on spacer item.");
        }
    }

    private static class AppItem extends Item {
        private static final String STRING_ID_FORMAT = "AppItem{%s/%s}";

        public final ResolveInfo info;

        public AppItem(ResolveInfo info) {
            super(R.layout.item_root, getStringId(info));
            this.info = info;
        }

        private static String getStringId(ResolveInfo info) {
            ActivityInfo activityInfo = info.activityInfo;

            String component = String.format(
                    STRING_ID_FORMAT, activityInfo.applicationInfo.packageName, activityInfo.name);
            return component;
        }

        @Override
        boolean showAppDetails(RootsFragment fragment) {
            fragment.showAppDetails(info);
            return true;
        }

        @Override
        void bindView(View convertView) {
            final ImageView icon = (ImageView) convertView.findViewById(android.R.id.icon);
            final TextView title = (TextView) convertView.findViewById(android.R.id.title);
            final TextView summary = (TextView) convertView.findViewById(android.R.id.summary);

            final PackageManager pm = convertView.getContext().getPackageManager();
            icon.setImageDrawable(info.loadIcon(pm));
            title.setText(info.loadLabel(pm));

            // TODO: match existing summary behavior from disambig dialog
            summary.setVisibility(View.GONE);
        }

        @Override
        boolean isDropTarget() {
            // We won't support drag n' drop in DocumentsActivity, and apps only show up there.
            return false;
        }

        @Override
        void open(RootsFragment fragment) {
            DocumentsActivity activity = DocumentsActivity.get(fragment);
            Metrics.logAppVisited(fragment.getActivity(), info);
            activity.onAppPicked(info);
        }
    }

    private static class RootsAdapter extends ArrayAdapter<Item> {
        private static final Map<String, Long> sIdMap = new HashMap<String, Long>();
        // the next available id to associate with a new string id
        private static long sNextAvailableId;

        private OnDragListener mDragListener;

        /**
         * @param handlerAppIntent When not null, apps capable of handling the original intent will
         *            be included in list of roots (in special section at bottom).
         */
        public RootsAdapter(Context context, Collection<RootInfo> roots,
                @Nullable Intent handlerAppIntent, State state, OnDragListener dragListener) {
            super(context, 0);

            final List<RootItem> libraries = new ArrayList<>();
            final List<RootItem> others = new ArrayList<>();

            for (final RootInfo root : roots) {
                final RootItem item = new RootItem(root);

                if (root.isHome() &&
                        !Shared.shouldShowDocumentsRoot(context,
                                ((Activity) context).getIntent())) {
                    continue;
                } else if (root.isLibrary()) {
                    if (DEBUG) Log.d(TAG, "Adding " + root + " as library.");
                    libraries.add(item);
                } else {
                    if (DEBUG) Log.d(TAG, "Adding " + root + " as non-library.");
                    others.add(item);
                }
            }

            final RootComparator comp = new RootComparator();
            Collections.sort(libraries, comp);
            Collections.sort(others, comp);

            addAll(libraries);
            // Only add the spacer if it is actually separating something.
            if (!libraries.isEmpty() && !others.isEmpty()) {
                add(new SpacerItem());
            }
            addAll(others);

            // Include apps that can handle this intent too.
            if (handlerAppIntent != null) {
                includeHandlerApps(context, handlerAppIntent);
            }

            mDragListener = dragListener;
        }

        /**
         * Adds apps capable of handling the original intent will be included in list of roots (in
         * special section at bottom).
         */
        private void includeHandlerApps(Context context, Intent handlerAppIntent) {
            final PackageManager pm = context.getPackageManager();
            final List<ResolveInfo> infos = pm.queryIntentActivities(
                    handlerAppIntent, PackageManager.MATCH_DEFAULT_ONLY);

            final List<AppItem> apps = new ArrayList<>();

            // Omit ourselves from the list
            for (ResolveInfo info : infos) {
                if (!context.getPackageName().equals(info.activityInfo.packageName)) {
                    apps.add(new AppItem(info));
                }
            }

            if (apps.size() > 0) {
                add(new SpacerItem());
                addAll(apps);
            }
        }

        @Override
        public boolean hasStableIds() {
            return true;
        }

        @Override
        public long getItemId(int position) {
            // Ensure this method is only called in main thread because we don't have any
            // concurrency protection.
            assert(Looper.myLooper() == Looper.getMainLooper());

            String stringId = getItem(position).mStringId;

            long id;
            if (sIdMap.containsKey(stringId)) {
                id = sIdMap.get(stringId);
            } else {
                id = sNextAvailableId++;
                sIdMap.put(stringId, id);
            }

            return id;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            final Item item = getItem(position);
            final View view = item.getView(convertView, parent);

            if (item.isDropTarget()) {
                view.setTag(R.id.item_position_tag, position);
                view.setOnDragListener(mDragListener);
            } else {
                view.setTag(R.id.item_position_tag, null);
                view.setOnDragListener(null);
            }
            return view;
        }

        @Override
        public boolean areAllItemsEnabled() {
            return false;
        }

        @Override
        public boolean isEnabled(int position) {
            return getItemViewType(position) != 1;
        }

        @Override
        public int getItemViewType(int position) {
            final Item item = getItem(position);
            if (item instanceof RootItem || item instanceof AppItem) {
                return 0;
            } else {
                return 1;
            }
        }

        @Override
        public int getViewTypeCount() {
            return 2;
        }
    }

    public static class RootComparator implements Comparator<RootItem> {
        @Override
        public int compare(RootItem lhs, RootItem rhs) {
            return lhs.root.compareTo(rhs.root);
        }
    }
}
