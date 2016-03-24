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
 * limitations under the License
 */

package com.android.systemui.statusbar;

import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.DialogInterface.OnClickListener;
import android.hardware.input.InputManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.SparseArray;
import android.view.ContextThemeWrapper;
import android.view.InputDevice;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.KeyboardShortcutGroup;
import android.view.KeyboardShortcutInfo;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager.KeyboardShortcutsReceiver;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.android.systemui.R;
import com.android.systemui.recents.Recents;

import java.util.ArrayList;
import java.util.List;

import static android.content.Context.LAYOUT_INFLATER_SERVICE;
import static android.view.WindowManager.LayoutParams.TYPE_SYSTEM_DIALOG;

/**
 * Contains functionality for handling keyboard shortcuts.
 */
public class KeyboardShortcuts {
    private static final String TAG = KeyboardShortcuts.class.getSimpleName();

    private static final SparseArray<String> SPECIAL_CHARACTER_NAMES = new SparseArray<>();
    private static final SparseArray<String> MODIFIER_NAMES = new SparseArray<>();

    private static void loadSpecialCharacterNames(Context context) {
        SPECIAL_CHARACTER_NAMES.put(
                KeyEvent.KEYCODE_HOME, context.getString(R.string.keyboard_key_home));
        SPECIAL_CHARACTER_NAMES.put(
                KeyEvent.KEYCODE_BACK, context.getString(R.string.keyboard_key_back));
        SPECIAL_CHARACTER_NAMES.put(
                KeyEvent.KEYCODE_DPAD_UP, context.getString(R.string.keyboard_key_dpad_up));
        SPECIAL_CHARACTER_NAMES.put(
                KeyEvent.KEYCODE_DPAD_DOWN, context.getString(R.string.keyboard_key_dpad_down));
        SPECIAL_CHARACTER_NAMES.put(
                KeyEvent.KEYCODE_DPAD_LEFT, context.getString(R.string.keyboard_key_dpad_left));
        SPECIAL_CHARACTER_NAMES.put(
                KeyEvent.KEYCODE_DPAD_RIGHT, context.getString(R.string.keyboard_key_dpad_right));
        SPECIAL_CHARACTER_NAMES.put(
                KeyEvent.KEYCODE_DPAD_CENTER, context.getString(R.string.keyboard_key_dpad_center));
        SPECIAL_CHARACTER_NAMES.put(KeyEvent.KEYCODE_PERIOD, ".");
        SPECIAL_CHARACTER_NAMES.put(
                KeyEvent.KEYCODE_TAB, context.getString(R.string.keyboard_key_tab));
        SPECIAL_CHARACTER_NAMES.put(
                KeyEvent.KEYCODE_SPACE, context.getString(R.string.keyboard_key_space));
        SPECIAL_CHARACTER_NAMES.put(
                KeyEvent.KEYCODE_ENTER, context.getString(R.string.keyboard_key_enter));
        SPECIAL_CHARACTER_NAMES.put(
                KeyEvent.KEYCODE_DEL, context.getString(R.string.keyboard_key_backspace));
        SPECIAL_CHARACTER_NAMES.put(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                context.getString(R.string.keyboard_key_media_play_pause));
        SPECIAL_CHARACTER_NAMES.put(
                KeyEvent.KEYCODE_MEDIA_STOP, context.getString(R.string.keyboard_key_media_stop));
        SPECIAL_CHARACTER_NAMES.put(
                KeyEvent.KEYCODE_MEDIA_NEXT, context.getString(R.string.keyboard_key_media_next));
        SPECIAL_CHARACTER_NAMES.put(KeyEvent.KEYCODE_MEDIA_PREVIOUS,
                context.getString(R.string.keyboard_key_media_previous));
        SPECIAL_CHARACTER_NAMES.put(KeyEvent.KEYCODE_MEDIA_REWIND,
                context.getString(R.string.keyboard_key_media_rewind));
        SPECIAL_CHARACTER_NAMES.put(KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
                context.getString(R.string.keyboard_key_media_fast_forward));
        SPECIAL_CHARACTER_NAMES.put(
                KeyEvent.KEYCODE_PAGE_UP, context.getString(R.string.keyboard_key_page_up));
        SPECIAL_CHARACTER_NAMES.put(
                KeyEvent.KEYCODE_PAGE_DOWN, context.getString(R.string.keyboard_key_page_down));
        SPECIAL_CHARACTER_NAMES.put(KeyEvent.KEYCODE_BUTTON_A,
                context.getString(R.string.keyboard_key_button_template, "A"));
        SPECIAL_CHARACTER_NAMES.put(KeyEvent.KEYCODE_BUTTON_B,
                context.getString(R.string.keyboard_key_button_template, "B"));
        SPECIAL_CHARACTER_NAMES.put(KeyEvent.KEYCODE_BUTTON_C,
                context.getString(R.string.keyboard_key_button_template, "C"));
        SPECIAL_CHARACTER_NAMES.put(KeyEvent.KEYCODE_BUTTON_X,
                context.getString(R.string.keyboard_key_button_template, "X"));
        SPECIAL_CHARACTER_NAMES.put(KeyEvent.KEYCODE_BUTTON_Y,
                context.getString(R.string.keyboard_key_button_template, "Y"));
        SPECIAL_CHARACTER_NAMES.put(KeyEvent.KEYCODE_BUTTON_Z,
                context.getString(R.string.keyboard_key_button_template, "Z"));
        SPECIAL_CHARACTER_NAMES.put(KeyEvent.KEYCODE_BUTTON_L1,
                context.getString(R.string.keyboard_key_button_template, "L1"));
        SPECIAL_CHARACTER_NAMES.put(KeyEvent.KEYCODE_BUTTON_R1,
                context.getString(R.string.keyboard_key_button_template, "R1"));
        SPECIAL_CHARACTER_NAMES.put(KeyEvent.KEYCODE_BUTTON_L2,
                context.getString(R.string.keyboard_key_button_template, "L2"));
        SPECIAL_CHARACTER_NAMES.put(KeyEvent.KEYCODE_BUTTON_R2,
                context.getString(R.string.keyboard_key_button_template, "R2"));
        SPECIAL_CHARACTER_NAMES.put(KeyEvent.KEYCODE_BUTTON_START,
                context.getString(R.string.keyboard_key_button_template, "Start"));
        SPECIAL_CHARACTER_NAMES.put(KeyEvent.KEYCODE_BUTTON_SELECT,
                context.getString(R.string.keyboard_key_button_template, "Select"));
        SPECIAL_CHARACTER_NAMES.put(KeyEvent.KEYCODE_BUTTON_MODE,
                context.getString(R.string.keyboard_key_button_template, "Mode"));
        SPECIAL_CHARACTER_NAMES.put(
                KeyEvent.KEYCODE_FORWARD_DEL, context.getString(R.string.keyboard_key_forward_del));
        SPECIAL_CHARACTER_NAMES.put(KeyEvent.KEYCODE_ESCAPE, "Esc");
        SPECIAL_CHARACTER_NAMES.put(KeyEvent.KEYCODE_SYSRQ, "SysRq");
        SPECIAL_CHARACTER_NAMES.put(KeyEvent.KEYCODE_BREAK, "Break");
        SPECIAL_CHARACTER_NAMES.put(KeyEvent.KEYCODE_SCROLL_LOCK, "Scroll Lock");
        SPECIAL_CHARACTER_NAMES.put(
                KeyEvent.KEYCODE_MOVE_HOME, context.getString(R.string.keyboard_key_move_home));
        SPECIAL_CHARACTER_NAMES.put(
                KeyEvent.KEYCODE_MOVE_END, context.getString(R.string.keyboard_key_move_end));
        SPECIAL_CHARACTER_NAMES.put(
                KeyEvent.KEYCODE_INSERT, context.getString(R.string.keyboard_key_insert));
        SPECIAL_CHARACTER_NAMES.put(KeyEvent.KEYCODE_F1, "F1");
        SPECIAL_CHARACTER_NAMES.put(KeyEvent.KEYCODE_F2, "F2");
        SPECIAL_CHARACTER_NAMES.put(KeyEvent.KEYCODE_F3, "F3");
        SPECIAL_CHARACTER_NAMES.put(KeyEvent.KEYCODE_F4, "F4");
        SPECIAL_CHARACTER_NAMES.put(KeyEvent.KEYCODE_F5, "F5");
        SPECIAL_CHARACTER_NAMES.put(KeyEvent.KEYCODE_F6, "F6");
        SPECIAL_CHARACTER_NAMES.put(KeyEvent.KEYCODE_F7, "F7");
        SPECIAL_CHARACTER_NAMES.put(KeyEvent.KEYCODE_F8, "F8");
        SPECIAL_CHARACTER_NAMES.put(KeyEvent.KEYCODE_F9, "F9");
        SPECIAL_CHARACTER_NAMES.put(KeyEvent.KEYCODE_F10, "F10");
        SPECIAL_CHARACTER_NAMES.put(KeyEvent.KEYCODE_F11, "F11");
        SPECIAL_CHARACTER_NAMES.put(KeyEvent.KEYCODE_F12, "F12");
        SPECIAL_CHARACTER_NAMES.put(
                KeyEvent.KEYCODE_NUM_LOCK, context.getString(R.string.keyboard_key_num_lock));
        SPECIAL_CHARACTER_NAMES.put(KeyEvent.KEYCODE_NUMPAD_0,
                context.getString(R.string.keyboard_key_numpad_template, "0"));
        SPECIAL_CHARACTER_NAMES.put(KeyEvent.KEYCODE_NUMPAD_1,
                context.getString(R.string.keyboard_key_numpad_template, "1"));
        SPECIAL_CHARACTER_NAMES.put(KeyEvent.KEYCODE_NUMPAD_2,
                context.getString(R.string.keyboard_key_numpad_template, "2"));
        SPECIAL_CHARACTER_NAMES.put(KeyEvent.KEYCODE_NUMPAD_3,
                context.getString(R.string.keyboard_key_numpad_template, "3"));
        SPECIAL_CHARACTER_NAMES.put(KeyEvent.KEYCODE_NUMPAD_4,
                context.getString(R.string.keyboard_key_numpad_template, "4"));
        SPECIAL_CHARACTER_NAMES.put(KeyEvent.KEYCODE_NUMPAD_5,
                context.getString(R.string.keyboard_key_numpad_template, "5"));
        SPECIAL_CHARACTER_NAMES.put(KeyEvent.KEYCODE_NUMPAD_6,
                context.getString(R.string.keyboard_key_numpad_template, "6"));
        SPECIAL_CHARACTER_NAMES.put(KeyEvent.KEYCODE_NUMPAD_7,
                context.getString(R.string.keyboard_key_numpad_template, "7"));
        SPECIAL_CHARACTER_NAMES.put(KeyEvent.KEYCODE_NUMPAD_8,
                context.getString(R.string.keyboard_key_numpad_template, "8"));
        SPECIAL_CHARACTER_NAMES.put(KeyEvent.KEYCODE_NUMPAD_9,
                context.getString(R.string.keyboard_key_numpad_template, "9"));
        SPECIAL_CHARACTER_NAMES.put(KeyEvent.KEYCODE_NUMPAD_DIVIDE,
                context.getString(R.string.keyboard_key_numpad_template, "/"));
        SPECIAL_CHARACTER_NAMES.put(KeyEvent.KEYCODE_NUMPAD_MULTIPLY,
                context.getString(R.string.keyboard_key_numpad_template, "*"));
        SPECIAL_CHARACTER_NAMES.put(KeyEvent.KEYCODE_NUMPAD_SUBTRACT,
                context.getString(R.string.keyboard_key_numpad_template, "-"));
        SPECIAL_CHARACTER_NAMES.put(KeyEvent.KEYCODE_NUMPAD_ADD,
                context.getString(R.string.keyboard_key_numpad_template, "+"));
        SPECIAL_CHARACTER_NAMES.put(KeyEvent.KEYCODE_NUMPAD_DOT,
                context.getString(R.string.keyboard_key_numpad_template, "."));
        SPECIAL_CHARACTER_NAMES.put(KeyEvent.KEYCODE_NUMPAD_COMMA,
                context.getString(R.string.keyboard_key_numpad_template, ","));
        SPECIAL_CHARACTER_NAMES.put(KeyEvent.KEYCODE_NUMPAD_ENTER,
                context.getString(R.string.keyboard_key_numpad_template,
                        context.getString(R.string.keyboard_key_enter)));
        SPECIAL_CHARACTER_NAMES.put(KeyEvent.KEYCODE_NUMPAD_EQUALS,
                context.getString(R.string.keyboard_key_numpad_template, "="));
        SPECIAL_CHARACTER_NAMES.put(KeyEvent.KEYCODE_NUMPAD_LEFT_PAREN,
                context.getString(R.string.keyboard_key_numpad_template, "("));
        SPECIAL_CHARACTER_NAMES.put(KeyEvent.KEYCODE_NUMPAD_RIGHT_PAREN,
                context.getString(R.string.keyboard_key_numpad_template, ")"));
        SPECIAL_CHARACTER_NAMES.put(KeyEvent.KEYCODE_ZENKAKU_HANKAKU, "半角/全角");
        SPECIAL_CHARACTER_NAMES.put(KeyEvent.KEYCODE_EISU, "英数");
        SPECIAL_CHARACTER_NAMES.put(KeyEvent.KEYCODE_MUHENKAN, "無変換");
        SPECIAL_CHARACTER_NAMES.put(KeyEvent.KEYCODE_HENKAN, "変換");
        SPECIAL_CHARACTER_NAMES.put(KeyEvent.KEYCODE_KATAKANA_HIRAGANA, "かな");

        MODIFIER_NAMES.put(KeyEvent.META_META_ON, "Meta");
        MODIFIER_NAMES.put(KeyEvent.META_CTRL_ON, "Ctrl");
        MODIFIER_NAMES.put(KeyEvent.META_ALT_ON, "Alt");
        MODIFIER_NAMES.put(KeyEvent.META_SHIFT_ON, "Shift");
        MODIFIER_NAMES.put(KeyEvent.META_SYM_ON, "Sym");
        MODIFIER_NAMES.put(KeyEvent.META_FUNCTION_ON, "Fn");
    }

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final Context mContext;
    private final OnClickListener dialogCloseListener =  new DialogInterface.OnClickListener() {
        public void onClick(DialogInterface dialog, int id) {
            dismissKeyboardShortcutsDialog();
        }
    };

    private Dialog mKeyboardShortcutsDialog;
    private KeyCharacterMap mKeyCharacterMap;

    public KeyboardShortcuts(Context context) {
        this.mContext = new ContextThemeWrapper(context, android.R.style.Theme_Material_Light);
        if (SPECIAL_CHARACTER_NAMES.size() == 0) {
            loadSpecialCharacterNames(context);
        }
    }

    public void toggleKeyboardShortcuts(int deviceId) {
        InputDevice inputDevice = InputManager.getInstance().getInputDevice(deviceId);
        if (inputDevice != null) {
            mKeyCharacterMap = inputDevice.getKeyCharacterMap();
        }
        if (mKeyboardShortcutsDialog == null) {
            Recents.getSystemServices().requestKeyboardShortcuts(mContext,
                new KeyboardShortcutsReceiver() {
                    @Override
                    public void onKeyboardShortcutsReceived(
                            final List<KeyboardShortcutGroup> result) {
                        KeyboardShortcutGroup systemGroup = new KeyboardShortcutGroup(
                                mContext.getString(R.string.keyboard_shortcut_group_system), true);
                        systemGroup.addItem(new KeyboardShortcutInfo(
                                mContext.getString(R.string.keyboard_shortcut_group_system_home),
                                KeyEvent.KEYCODE_ENTER, KeyEvent.META_META_ON));
                        systemGroup.addItem(new KeyboardShortcutInfo(
                                mContext.getString(R.string.keyboard_shortcut_group_system_back),
                                KeyEvent.KEYCODE_DEL, KeyEvent.META_META_ON));
                        systemGroup.addItem(new KeyboardShortcutInfo(
                                mContext.getString(R.string.keyboard_shortcut_group_system_recents),
                                KeyEvent.KEYCODE_TAB, KeyEvent.META_ALT_ON));
                        result.add(systemGroup);
                        showKeyboardShortcutsDialog(result);
                    }
                });
        } else {
            dismissKeyboardShortcutsDialog();
        }
    }

    public void dismissKeyboardShortcutsDialog() {
        if (mKeyboardShortcutsDialog != null) {
            mKeyboardShortcutsDialog.dismiss();
            mKeyboardShortcutsDialog = null;
        }
    }

    private void showKeyboardShortcutsDialog(
            final List<KeyboardShortcutGroup> keyboardShortcutGroups) {
        // Need to post on the main thread.
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                handleShowKeyboardShortcuts(keyboardShortcutGroups);
            }
        });
    }

    private void handleShowKeyboardShortcuts(List<KeyboardShortcutGroup> keyboardShortcutGroups) {
        AlertDialog.Builder dialogBuilder = new AlertDialog.Builder(mContext);
        LayoutInflater inflater = (LayoutInflater) mContext.getSystemService(
                LAYOUT_INFLATER_SERVICE);
        final View keyboardShortcutsView = inflater.inflate(
                R.layout.keyboard_shortcuts_view, null);
        populateKeyboardShortcuts((LinearLayout) keyboardShortcutsView.findViewById(
                R.id.keyboard_shortcuts_container), keyboardShortcutGroups);
        dialogBuilder.setView(keyboardShortcutsView);
        dialogBuilder.setPositiveButton(R.string.quick_settings_done, dialogCloseListener);
        mKeyboardShortcutsDialog = dialogBuilder.create();
        mKeyboardShortcutsDialog.setCanceledOnTouchOutside(true);
        Window keyboardShortcutsWindow = mKeyboardShortcutsDialog.getWindow();
        keyboardShortcutsWindow.setType(TYPE_SYSTEM_DIALOG);
        mKeyboardShortcutsDialog.show();
    }

    private void populateKeyboardShortcuts(LinearLayout keyboardShortcutsLayout,
            List<KeyboardShortcutGroup> keyboardShortcutGroups) {
        LayoutInflater inflater = LayoutInflater.from(mContext);
        final int keyboardShortcutGroupsSize = keyboardShortcutGroups.size();
        for (int i = 0; i < keyboardShortcutGroupsSize; i++) {
            KeyboardShortcutGroup group = keyboardShortcutGroups.get(i);
            TextView categoryTitle = (TextView) inflater.inflate(
                    R.layout.keyboard_shortcuts_category_title, keyboardShortcutsLayout, false);
            categoryTitle.setText(group.getLabel());
            categoryTitle.setTextColor(group.isSystemGroup()
                    ? mContext.getColor(R.color.ksh_system_group_color)
                    : mContext.getColor(R.color.ksh_application_group_color));
            keyboardShortcutsLayout.addView(categoryTitle);

            LinearLayout shortcutContainer = (LinearLayout) inflater.inflate(
                    R.layout.keyboard_shortcuts_container, keyboardShortcutsLayout, false);
            final int itemsSize = group.getItems().size();
            for (int j = 0; j < itemsSize; j++) {
                KeyboardShortcutInfo info = group.getItems().get(j);
                if (info.getKeycode() != KeyEvent.KEYCODE_UNKNOWN
                        && !KeyCharacterMap.deviceHasKey(info.getKeycode())) {
                    // The user can't achieve this shortcut, so skipping.
                    Log.w(TAG, "Keyboard Shortcut contains key not on device, skipping.");
                    continue;
                }
                List<String> shortcutKeys = getHumanReadableShortcutKeys(info);
                if (shortcutKeys == null) {
                    // Ignore shortcuts we can't display keys for.
                    Log.w(TAG, "Keyboard Shortcut contains unsupported keys, skipping.");
                    continue;
                }
                View shortcutView = inflater.inflate(R.layout.keyboard_shortcut_app_item,
                        shortcutContainer, false);
                TextView textView = (TextView) shortcutView
                        .findViewById(R.id.keyboard_shortcuts_keyword);
                textView.setText(info.getLabel());

                ViewGroup shortcutItemsContainer = (ViewGroup) shortcutView
                        .findViewById(R.id.keyboard_shortcuts_item_container);
                final int shortcutKeysSize = shortcutKeys.size();
                for (int k = 0; k < shortcutKeysSize; k++) {
                    String shortcutKey = shortcutKeys.get(k);
                    TextView shortcutKeyView = (TextView) inflater.inflate(
                            R.layout.keyboard_shortcuts_key_view, shortcutItemsContainer, false);
                    shortcutKeyView.setText(shortcutKey);
                    shortcutItemsContainer.addView(shortcutKeyView);
                }
                shortcutContainer.addView(shortcutView);
            }
            keyboardShortcutsLayout.addView(shortcutContainer);
            if (i < keyboardShortcutGroupsSize - 1) {
                View separator = inflater.inflate(
                        R.layout.keyboard_shortcuts_category_separator, keyboardShortcutsLayout,
                        false);
                keyboardShortcutsLayout.addView(separator);
            }
        }
    }

    private List<String> getHumanReadableShortcutKeys(KeyboardShortcutInfo info) {
        List<String> shortcutKeys = getHumanReadableModifiers(info);
        if (shortcutKeys == null) {
            return null;
        }
        String displayLabelString;
        if (info.getKeycode() == KeyEvent.KEYCODE_UNKNOWN) {
            displayLabelString = String.valueOf(info.getBaseCharacter());
        } else if (SPECIAL_CHARACTER_NAMES.get(info.getKeycode()) != null) {
            displayLabelString = SPECIAL_CHARACTER_NAMES.get(info.getKeycode());
        } else {
            // TODO: Have a generic map for when we don't have the device's.
            char displayLabel = mKeyCharacterMap == null
                    ? 0 : mKeyCharacterMap.getDisplayLabel(info.getKeycode());
            if (displayLabel != 0) {
                displayLabelString = String.valueOf(displayLabel);
            } else {
                return null;
            }
        }
        shortcutKeys.add(displayLabelString.toUpperCase());
        return shortcutKeys;
    }

    private List<String> getHumanReadableModifiers(KeyboardShortcutInfo info) {
        final List<String> shortcutKeys = new ArrayList<>();
        int modifiers = info.getModifiers();
        if (modifiers == 0) {
            return shortcutKeys;
        }
        for(int i = 0; i < MODIFIER_NAMES.size(); ++i) {
            final int supportedModifier = MODIFIER_NAMES.keyAt(i);
            if ((modifiers & supportedModifier) != 0) {
                shortcutKeys.add(MODIFIER_NAMES.get(supportedModifier).toUpperCase());
                modifiers &= ~supportedModifier;
            }
        }
        if (modifiers != 0) {
            // Remaining unsupported modifiers, don't show anything.
            return null;
        }
        return shortcutKeys;
    }
}
