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

package com.android.documentsui.bots;

import static android.support.test.espresso.Espresso.onView;
import static android.support.test.espresso.action.ViewActions.click;
import static android.support.test.espresso.action.ViewActions.closeSoftKeyboard;
import static android.support.test.espresso.action.ViewActions.typeText;
import static android.support.test.espresso.assertion.ViewAssertions.matches;
import static android.support.test.espresso.matcher.ViewMatchers.isAssignableFrom;
import static android.support.test.espresso.matcher.ViewMatchers.isDisplayed;
import static android.support.test.espresso.matcher.ViewMatchers.withId;
import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.assertFalse;
import static junit.framework.Assert.assertNotNull;
import static junit.framework.Assert.assertTrue;
import static org.hamcrest.CoreMatchers.is;

import android.content.Context;
import android.content.res.Configuration;
import android.support.test.espresso.matcher.BoundedMatcher;
import android.support.test.uiautomator.By;
import android.support.test.uiautomator.UiDevice;
import android.support.test.uiautomator.UiObject;
import android.support.test.uiautomator.UiObject2;
import android.support.test.uiautomator.UiObjectNotFoundException;
import android.support.test.uiautomator.UiSelector;
import android.widget.Spinner;
import android.widget.Toolbar;

import com.android.documentsui.R;
import com.android.documentsui.model.DocumentInfo;

import org.hamcrest.Description;
import org.hamcrest.Matcher;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;

import junit.framework.Assert;
import junit.framework.AssertionFailedError;

/**
 * A test helper class that provides support for controlling DocumentsUI activities
 * programmatically, and making assertions against the state of the UI.
 * <p>
 * Support for working directly with Roots and Directory view can be found in the respective bots.
 */
public class UiBot extends BaseBot {

    public static final String TARGET_PKG = "com.android.documentsui";

    public UiBot(UiDevice device, Context context, int timeout) {
        super(device, context, timeout);
    }

    public void assertWindowTitle(String expected) {
        onView(Matchers.TOOLBAR)
                .check(matches(withToolbarTitle(is(expected))));
    }

    public void assertBreadcrumbTitle(String expected) {
        if (!isTablet()) {
            onView(Matchers.BREADCRUMB)
                    .check(matches(withBreadcrumbTitle(is(expected))));
        }
    }

    public void assertMenuEnabled(int id, boolean enabled) {
        UiObject2 menu = findMenuWithName(mContext.getString(id));
        assertNotNull(menu);
        assertEquals(enabled, menu.isEnabled());
    }

    public void assertSearchTextField(boolean isFocused, String query)
            throws UiObjectNotFoundException {
        UiObject textField = findSearchViewTextField();
        boolean searchIconVisible = isSearchIconVisible();

        assertFalse(searchIconVisible);
        assertTrue(textField.exists());
        assertEquals(isFocused, textField.isFocused());
        if (query != null) {
            assertEquals(query, textField.getText());
        }
    }

    public void assertSearchTextFiledAndIcon(
            boolean searchTextFieldExists, boolean searchIconExists) {
        assertEquals(searchTextFieldExists, findSearchViewTextField().exists());
        boolean searchIconVisible = isSearchIconVisible();
        assertEquals(searchIconExists, searchIconVisible);
    }

    public void assertInActionMode(boolean inActionMode) {
        UiObject actionModeBar = findActionModeBar();
        assertEquals(inActionMode, actionModeBar.exists());
    }

    public void openSearchView() throws UiObjectNotFoundException {
        UiObject searchView = findSearchView();
        searchView.click();
        assertTrue(searchView.exists());
    }

    public void setSearchQuery(String query) throws UiObjectNotFoundException {
        onView(Matchers.SEARCH_MENU).perform(typeText(query));
    }

    public UiObject openOverflowMenu() throws UiObjectNotFoundException {
        UiObject obj = findMenuMoreOptions();
        obj.click();
        mDevice.waitForIdle(mTimeout);
        return obj;
    }

    public void setDialogText(String text) throws UiObjectNotFoundException {
        findDialogEditText().setText(text);
    }

    public boolean isTablet() {
        return (mContext.getResources().getConfiguration().screenLayout &
                Configuration.SCREENLAYOUT_SIZE_MASK) >= Configuration.SCREENLAYOUT_SIZE_LARGE;
    }

    void switchViewMode() {
        UiObject2 mode = menuGridMode();
        if (mode != null) {
            mode.click();
        } else {
            menuListMode().click();
        }
    }

    boolean isSearchIconVisible() {
        boolean searchIconVisible = true;
        boolean isTablet = isTablet();
        try {
            if (isTablet) {
                // Tablets use ImageView for its search icon, and has search_button as its res name
                onView(Matchers.SEARCH_BUTTON)
                        .check(matches(isDisplayed()));
            } else {
                // Phones use ActionMenuItemView for its search icon, and has menu_search as its res
                // name
                onView(Matchers.MENU_SEARCH)
                        .check(matches(isDisplayed()));
            }
        } catch (Exception | AssertionFailedError e) {
            searchIconVisible = false;
        }
        return searchIconVisible;
    }

    UiObject2 menuGridMode() {
        // Note that we're using By.desc rather than By.res, because of b/25285770
        return find(By.desc("Grid view"));
    }

    UiObject2 menuListMode() {
        // Note that we're using By.desc rather than By.res, because of b/25285770
        return find(By.desc("List view"));
    }

    public UiObject2 menuDelete() {
        return find(By.res("com.android.documentsui:id/menu_delete"));
    }

    public UiObject2 menuShare() {
        return find(By.res("com.android.documentsui:id/menu_share"));
    }

    public UiObject2 menuRename() {
        return findMenuWithName(mContext.getString(R.string.menu_rename));
    }

    public UiObject2 menuNewFolder() {
        return findMenuWithName(mContext.getString(R.string.menu_create_dir));
    }

    UiObject findSearchView() {
        return findObject("com.android.documentsui:id/menu_search");
    }

    UiObject findSearchViewTextField() {
        return findObject("com.android.documentsui:id/menu_search", "android:id/search_src_text");
    }

    UiObject findSearchViewIcon() {
        return mContext.getResources().getBoolean(R.bool.full_bar_search_view)
                ? findObject("com.android.documentsui:id/menu_search")
                : findObject("com.android.documentsui:id/menu_search", "android:id/search_button");
    }

    public void clickBreadcrumbItem(String label) throws UiObjectNotFoundException {
        if (isTablet()) {
            findBreadcrumb(label).click();
        } else {
            findMenuWithName(label).click();
        }
    }

    public void clickDropdownBreadcrumb() throws UiObjectNotFoundException {
        assertFalse(isTablet());
        onView(isAssignableFrom(Spinner.class)).perform(click());
    }

    public UiObject findBreadcrumb(String label) throws UiObjectNotFoundException {
        final UiSelector breadcrumbList = new UiSelector().resourceId(
                "com.android.documentsui:id/breadcrumb");

        // Wait for the first list item to appear
        new UiObject(breadcrumbList.childSelector(new UiSelector())).waitForExists(mTimeout);

        return mDevice.findObject(breadcrumbList.childSelector(new UiSelector().text(label)));
    }

    public void assertBreadcrumbItemsPresent(String... labels) throws UiObjectNotFoundException {
        List<String> absent = new ArrayList<>();
        for (String label : labels) {
            // For non-Tablet devices, a dropdown List menu is shown instead
            if (isTablet() ? !findBreadcrumb(label).exists() : findMenuWithName(label) == null) {
                absent.add(label);
            }
        }
        if (!absent.isEmpty()) {
            Assert.fail("Expected documents " + Arrays.asList(labels)
                    + ", but missing " + absent);
        }
    }

    UiObject findActionModeBar() {
        return findObject("android:id/action_mode_bar");
    }

    public UiObject findDialogEditText() {
        return findObject("android:id/content", "android:id/text1");
    }

    public UiObject findDownloadRetryDialog() {
        UiSelector selector = new UiSelector().text("Couldn't download");
        UiObject title = mDevice.findObject(selector);
        title.waitForExists(mTimeout);
        return title;
    }

    public void clickDialogOkButton() {
        // Espresso has flaky results when keyboard shows up, so hiding it for now
        // before trying to click on any dialog button
        onView(withId(android.R.id.button1)).perform(closeSoftKeyboard(), click());
    }

    public void clickDialogCancelButton() throws UiObjectNotFoundException {
        // Espresso has flaky results when keyboard shows up, so hiding it for now
        // before trying to click on any dialog button
        onView(withId(android.R.id.button2)).perform(closeSoftKeyboard(), click());
    }

    UiObject findMenuLabelWithName(String label) {
        UiSelector selector = new UiSelector().text(label);
        return mDevice.findObject(selector);
    }

    UiObject2 findMenuWithName(String label) {
        List<UiObject2> menuItems = mDevice.findObjects(By.clazz("android.widget.LinearLayout"));
        Iterator<UiObject2> it = menuItems.iterator();

        UiObject2 menuItem = null;
        while (it.hasNext()) {
            menuItem = it.next();
            UiObject2 text = menuItem.findObject(By.text(label));
            if (text != null) {
                break;
            }
        }
        return menuItem;
    }

    UiObject findMenuMoreOptions() {
        UiSelector selector = new UiSelector().className("android.widget.ImageButton")
                .descriptionContains("More options");
        // TODO: use the system string ? android.R.string.action_menu_overflow_description
        return mDevice.findObject(selector);
    }

    public void pressKey(int keyCode) {
        mDevice.pressKeyCode(keyCode);
    }

    public void pressKey(int keyCode, int metaState) {
        mDevice.pressKeyCode(keyCode, metaState);
    }

    private static Matcher<Object> withToolbarTitle(
            final Matcher<CharSequence> textMatcher) {
        return new BoundedMatcher<Object, Toolbar>(Toolbar.class) {
            @Override
            public boolean matchesSafely(Toolbar toolbar) {
                return textMatcher.matches(toolbar.getTitle());
            }

            @Override
            public void describeTo(Description description) {
                description.appendText("with toolbar title: ");
                textMatcher.describeTo(description);
            }
        };
    }

    private static Matcher<Object> withBreadcrumbTitle(
            final Matcher<CharSequence> textMatcher) {
        return new BoundedMatcher<Object, Spinner>(Spinner.class) {
            @Override
            public boolean matchesSafely(Spinner breadcrumb) {
                DocumentInfo selectedDoc = (DocumentInfo) breadcrumb.getSelectedItem();
                return textMatcher.matches(selectedDoc.displayName);
            }

            @Override
            public void describeTo(Description description) {
                description.appendText("with breadcrumb title: ");
                textMatcher.describeTo(description);
            }
        };
    }
}
