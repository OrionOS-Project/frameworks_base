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

package com.android.internal.app;

import android.app.FragmentManager;
import android.app.FragmentTransaction;
import android.app.ListFragment;
import android.content.Context;
import android.os.Bundle;
import android.util.LocaleList;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.widget.ListView;
import android.widget.SearchView;

import com.android.internal.R;

import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * A two-step locale picker. It shows a language, then a country.
 *
 * <p>It shows suggestions at the top, then the rest of the locales.
 * Allows the user to search for locales using both their native name and their name in the
 * default locale.</p>
 */
public class LocalePickerWithRegion extends ListFragment implements SearchView.OnQueryTextListener {

    private SuggestedLocaleAdapter mAdapter;
    private LocaleSelectedListener mListener;
    private Set<LocaleStore.LocaleInfo> mLocaleList;
    private LocaleStore.LocaleInfo mParentLocale;
    private boolean mTranslatedOnly = false;
    private boolean mCountryMode = false;

    /**
     * Other classes can register to be notified when a locale was selected.
     *
     * <p>This is the mechanism to "return" the result of the selection.</p>
     */
    public interface LocaleSelectedListener {
        /**
         * The classes that want to retrieve the locale picked should implement this method.
         * @param locale    the locale picked.
         */
        void onLocaleSelected(LocaleStore.LocaleInfo locale);
    }

    private static LocalePickerWithRegion createCountryPicker(Context context,
            LocaleSelectedListener listener, LocaleStore.LocaleInfo parent,
            boolean translatedOnly) {
        LocalePickerWithRegion localePicker = new LocalePickerWithRegion();
        boolean shouldShowTheList = localePicker.setListener(context, listener, parent,
                true /* country mode */, translatedOnly);
        return shouldShowTheList ? localePicker : null;
    }

    public static LocalePickerWithRegion createLanguagePicker(Context context,
            LocaleSelectedListener listener, boolean translatedOnly) {
        LocalePickerWithRegion localePicker = new LocalePickerWithRegion();
        localePicker.setListener(context, listener, null,
                false /* language mode */, translatedOnly);
        return localePicker;
    }

    /**
     * Sets the listener and initializes the locale list.
     *
     * <p>Returns true if we need to show the list, false if not.</p>
     *
     * <p>Can return false because of an error, trying to show a list of countries,
     * but no parent locale was provided.</p>
     *
     * <p>It can also return false if the caller tries to show the list in country mode and
     * there is only one country available (i.e. Japanese => Japan).
     * In this case we don't even show the list, we call the listener with that locale,
     * "pretending" it was selected, and return false.</p>
     */
    private boolean setListener(Context context, LocaleSelectedListener listener,
            LocaleStore.LocaleInfo parent, boolean countryMode, boolean translatedOnly) {
        if (countryMode && (parent == null || parent.getLocale() == null)) {
            // The list of countries is determined as all the countries where the parent language
            // is used.
            throw new IllegalArgumentException("The country selection list needs a parent.");
        }

        this.mCountryMode = countryMode;
        this.mParentLocale = parent;
        this.mListener = listener;
        this.mTranslatedOnly = translatedOnly;
        setRetainInstance(true);

        final HashSet<String> langTagsToIgnore = new HashSet<>();
        if (!translatedOnly) {
            final LocaleList userLocales = LocalePicker.getLocales();
            final String[] langTags = userLocales.toLanguageTags().split(",");
            Collections.addAll(langTagsToIgnore, langTags);
        }

        if (countryMode) {
            mLocaleList = LocaleStore.getLevelLocales(context,
                    langTagsToIgnore, parent, translatedOnly);
            if (mLocaleList.size() <= 1) {
                if (listener != null && (mLocaleList.size() == 1)) {
                    listener.onLocaleSelected(mLocaleList.iterator().next());
                }
                return false;
            }
        } else {
            mLocaleList = LocaleStore.getLevelLocales(context, langTagsToIgnore,
                    null /* no parent */, translatedOnly);
        }

        return true;
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setHasOptionsMenu(true);

        Locale sortingLocale;
        if (mCountryMode) {
            if (mParentLocale == null) {
                sortingLocale = Locale.getDefault();
                this.getActivity().setTitle(R.string.country_selection_title);
            } else {
                sortingLocale = mParentLocale.getLocale();
                this.getActivity().setTitle(mParentLocale.getFullNameNative());
            }
        } else {
            sortingLocale = Locale.getDefault();
            this.getActivity().setTitle(R.string.language_selection_title);
        }

        mAdapter = new SuggestedLocaleAdapter(mLocaleList, mCountryMode);
        LocaleHelper.LocaleInfoComparator comp =
                new LocaleHelper.LocaleInfoComparator(sortingLocale);
        mAdapter.sort(comp);
        setListAdapter(mAdapter);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem menuItem) {
        int id = menuItem.getItemId();
        switch (id) {
            case android.R.id.home:
                getFragmentManager().popBackStack();
                return true;
        }
        return super.onOptionsItemSelected(menuItem);
    }

    @Override
    public void onResume() {
        super.onResume();
        getListView().requestFocus();
    }

    @Override
    public void onListItemClick(ListView l, View v, int position, long id) {
        final LocaleStore.LocaleInfo locale =
                (LocaleStore.LocaleInfo) getListAdapter().getItem(position);

        if (mCountryMode || locale.getParent() != null) {
            if (mListener != null) {
                mListener.onLocaleSelected(locale);
            }
            getFragmentManager().popBackStack("localeListEditor",
                    FragmentManager.POP_BACK_STACK_INCLUSIVE);
        } else {
            LocalePickerWithRegion selector = LocalePickerWithRegion.createCountryPicker(
                    getContext(), mListener, locale, mTranslatedOnly /* translate only */);
            if (selector != null) {
                getFragmentManager().beginTransaction()
                        .setTransition(FragmentTransaction.TRANSIT_FRAGMENT_OPEN)
                        .replace(getId(), selector).addToBackStack(null)
                        .commit();
            } else {
                getFragmentManager().popBackStack("localeListEditor",
                        FragmentManager.POP_BACK_STACK_INCLUSIVE);
            }
        }
    }

    @Override
    public void onCreateOptionsMenu(Menu menu, MenuInflater inflater) {
        if (!mCountryMode) {
            inflater.inflate(R.menu.language_selection_list, menu);

            MenuItem mSearchMenuItem = menu.findItem(R.id.locale_search_menu);
            SearchView mSearchView = (SearchView) mSearchMenuItem.getActionView();

            mSearchView.setQueryHint(getText(R.string.search_language_hint));
            mSearchView.setOnQueryTextListener(this);
            mSearchView.setQuery("", false /* submit */);
        }
    }

    @Override
    public boolean onQueryTextSubmit(String query) {
        return false;
    }

    @Override
    public boolean onQueryTextChange(String newText) {
        if (mAdapter != null) {
            mAdapter.getFilter().filter(newText);
        }
        return false;
    }
}
