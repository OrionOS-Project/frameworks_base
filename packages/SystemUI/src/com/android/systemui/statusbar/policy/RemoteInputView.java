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
 * limitations under the License
 */

package com.android.systemui.statusbar.policy;

import android.app.PendingIntent;
import android.app.RemoteInput;
import android.content.Context;
import android.content.Intent;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.AttributeSet;
import android.util.Log;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.CompletionInfo;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.android.systemui.R;
import com.android.systemui.statusbar.NotificationData;
import com.android.systemui.statusbar.RemoteInputController;

/**
 * Host for the remote input.
 */
public class RemoteInputView extends LinearLayout implements View.OnClickListener, TextWatcher {

    private static final String TAG = "RemoteInput";

    // A marker object that let's us easily find views of this class.
    public static final Object VIEW_TAG = new Object();

    private RemoteEditText mEditText;
    private ImageButton mSendButton;
    private ProgressBar mProgressBar;
    private PendingIntent mPendingIntent;
    private RemoteInput[] mRemoteInputs;
    private RemoteInput mRemoteInput;
    private RemoteInputController mController;

    private NotificationData.Entry mEntry;

    public RemoteInputView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();

        mProgressBar = (ProgressBar) findViewById(R.id.remote_input_progress);

        mSendButton = (ImageButton) findViewById(R.id.remote_input_send);
        mSendButton.setOnClickListener(this);

        mEditText = (RemoteEditText) getChildAt(0);
        mEditText.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {

                // Check if this was the result of hitting the enter key
                final boolean isSoftImeEvent = event == null
                        && (actionId == EditorInfo.IME_ACTION_DONE
                        || actionId == EditorInfo.IME_ACTION_NEXT
                        || actionId == EditorInfo.IME_ACTION_SEND);
                final boolean isKeyboardEnterKey = event != null
                        && KeyEvent.isConfirmKey(event.getKeyCode())
                        && event.getAction() == KeyEvent.ACTION_DOWN;

                if (isSoftImeEvent || isKeyboardEnterKey) {
                    sendRemoteInput();
                    return true;
                }
                return false;
            }
        });
        mEditText.setOnClickListener(this);
        mEditText.addTextChangedListener(this);
        mEditText.setInnerFocusable(false);
        mEditText.mDefocusListener = this;
    }

    private void sendRemoteInput() {
        Bundle results = new Bundle();
        results.putString(mRemoteInput.getResultKey(), mEditText.getText().toString());
        Intent fillInIntent = new Intent().addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
        RemoteInput.addResultsToIntent(mRemoteInputs, fillInIntent,
                results);

        mEditText.setEnabled(false);
        mSendButton.setVisibility(INVISIBLE);
        mProgressBar.setVisibility(VISIBLE);
        mController.removeRemoteInput(mEntry);
        mEditText.mShowImeOnInputConnection = false;

        try {
            mPendingIntent.send(mContext, 0, fillInIntent);
        } catch (PendingIntent.CanceledException e) {
            Log.i(TAG, "Unable to send remote input result", e);
        }
    }

    public static RemoteInputView inflate(Context context, ViewGroup root,
            NotificationData.Entry entry,
            RemoteInputController controller) {
        RemoteInputView v = (RemoteInputView)
                LayoutInflater.from(context).inflate(R.layout.remote_input, root, false);
        v.mController = controller;
        v.mEntry = entry;
        v.setTag(VIEW_TAG);

        return v;
    }

    @Override
    public void onClick(View v) {
        if (v == mEditText) {
            if (!mEditText.isFocusable()) {
                focus();
            }
        } else if (v == mSendButton) {
            sendRemoteInput();
        }
    }

    public void onDefocus() {
        mController.removeRemoteInput(mEntry);
        mEntry.remoteInputText = mEditText.getText();
        setVisibility(INVISIBLE);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        mController.removeRemoteInput(mEntry);
    }

    public void setPendingIntent(PendingIntent pendingIntent) {
        mPendingIntent = pendingIntent;
    }

    public void setRemoteInput(RemoteInput[] remoteInputs, RemoteInput remoteInput) {
        mRemoteInputs = remoteInputs;
        mRemoteInput = remoteInput;
        mEditText.setHint(mRemoteInput.getLabel());
    }

    public void focus() {
        mController.addRemoteInput(mEntry);
        mEditText.setInnerFocusable(true);
        mEditText.mShowImeOnInputConnection = true;
        mEditText.setText(mEntry.remoteInputText);
        mEditText.setSelection(mEditText.getText().length());
        mEditText.requestFocus();
        updateSendButton();
    }

    public void onNotificationUpdate() {
        boolean sending = mProgressBar.getVisibility() == VISIBLE;

        if (sending) {
            // Update came in after we sent the reply, time to reset.
            reset();
        }
    }

    private void reset() {
        mEditText.getText().clear();
        mEditText.setEnabled(true);
        mSendButton.setVisibility(VISIBLE);
        mProgressBar.setVisibility(INVISIBLE);
        updateSendButton();
        onDefocus();
    }

    private void updateSendButton() {
        mSendButton.setEnabled(mEditText.getText().length() != 0);
    }

    @Override
    public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

    @Override
    public void onTextChanged(CharSequence s, int start, int before, int count) {}

    @Override
    public void afterTextChanged(Editable s) {
        updateSendButton();
    }

    /**
     * An EditText that changes appearance based on whether it's focusable and becomes
     * un-focusable whenever the user navigates away from it or it becomes invisible.
     */
    public static class RemoteEditText extends EditText {

        private final Drawable mBackground;
        private RemoteInputView mDefocusListener;
        boolean mShowImeOnInputConnection;

        public RemoteEditText(Context context, AttributeSet attrs) {
            super(context, attrs);
            mBackground = getBackground();
        }

        private void defocusIfNeeded() {
            if (isFocusable() && isEnabled()) {
                setInnerFocusable(false);
                if (mDefocusListener != null) {
                    mDefocusListener.onDefocus();
                }
                mShowImeOnInputConnection = false;
            }
        }

        @Override
        protected void onVisibilityChanged(View changedView, int visibility) {
            super.onVisibilityChanged(changedView, visibility);

            if (!isShown()) {
                defocusIfNeeded();
            }
        }

        @Override
        protected void onFocusLost() {
            super.onFocusLost();
            defocusIfNeeded();
        }

        @Override
        public boolean requestRectangleOnScreen(Rect r) {
            r.top = mScrollY;
            r.bottom = mScrollY + (mBottom - mTop);
            return super.requestRectangleOnScreen(r);
        }

        @Override
        public void getFocusedRect(Rect r) {
            super.getFocusedRect(r);
            r.top = mScrollY;
            r.bottom = mScrollY + (mBottom - mTop);
        }

        @Override
        public boolean onKeyPreIme(int keyCode, KeyEvent event) {
            if (keyCode == KeyEvent.KEYCODE_BACK && event.getAction() == KeyEvent.ACTION_UP) {
                defocusIfNeeded();
                final InputMethodManager imm = InputMethodManager.getInstance();
                imm.hideSoftInputFromWindow(getWindowToken(), 0);
                return true;
            }
            return super.onKeyPreIme(keyCode, event);
        }

        @Override
        public InputConnection onCreateInputConnection(EditorInfo outAttrs) {
            final InputConnection inputConnection = super.onCreateInputConnection(outAttrs);

            if (mShowImeOnInputConnection && inputConnection != null) {
                final InputMethodManager imm = InputMethodManager.getInstance();
                if (imm != null) {
                    // onCreateInputConnection is called by InputMethodManager in the middle of
                    // setting up the connection to the IME; wait with requesting the IME until that
                    // work has completed.
                    post(new Runnable() {
                        @Override
                        public void run() {
                            imm.viewClicked(RemoteEditText.this);
                            imm.showSoftInput(RemoteEditText.this, 0);
                        }
                    });
                }
            }

            return inputConnection;
        }

        @Override
        public void onCommitCompletion(CompletionInfo text) {
            clearComposingText();
            setText(text.getText());
            setSelection(getText().length());
        }

        void setInnerFocusable(boolean focusable) {
            setFocusableInTouchMode(focusable);
            setFocusable(focusable);
            setCursorVisible(focusable);

            if (focusable) {
                requestFocus();
                setBackground(mBackground);
            } else {
                setBackground(null);
            }

        }
    }
}
