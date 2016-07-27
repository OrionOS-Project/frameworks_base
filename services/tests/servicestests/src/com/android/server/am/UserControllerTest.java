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

package com.android.server.am;

import android.app.IUserSwitchObserver;
import android.content.Context;
import android.content.IIntentReceiver;
import android.content.Intent;
import android.content.pm.UserInfo;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IRemoteCallback;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteException;
import android.os.UserManagerInternal;
import android.test.AndroidTestCase;
import android.util.Log;

import com.android.server.pm.UserManagerService;
import com.android.server.wm.WindowManagerService;

import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static android.content.pm.PackageManager.PERMISSION_GRANTED;
import static com.android.server.am.ActivityManagerService.CONTINUE_USER_SWITCH_MSG;
import static com.android.server.am.ActivityManagerService.REPORT_USER_SWITCH_COMPLETE_MSG;
import static com.android.server.am.ActivityManagerService.REPORT_USER_SWITCH_MSG;
import static com.android.server.am.ActivityManagerService.SYSTEM_USER_CURRENT_MSG;
import static com.android.server.am.ActivityManagerService.SYSTEM_USER_START_MSG;
import static com.android.server.am.ActivityManagerService.USER_SWITCH_TIMEOUT_MSG;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyInt;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

public class UserControllerTest extends AndroidTestCase {
    private static final int TEST_USER_ID = 10;
    private static String TAG = UserControllerTest.class.getSimpleName();
    private UserController mUserController;
    private TestInjector mInjector;

    @Override
    public void setUp() throws Exception {
        super.setUp();
        mInjector = new TestInjector(getContext());
        mUserController = new UserController(mInjector);
        setUpUser(TEST_USER_ID, 0);
    }

    @Override
    protected void tearDown() throws Exception {
        super.tearDown();
        mInjector.handlerThread.quit();

    }

    public void testStartUser() throws RemoteException {
        mUserController.startUser(TEST_USER_ID, true);
        Mockito.verify(mInjector.getWindowManager()).startFreezingScreen(anyInt(), anyInt());
        Mockito.verify(mInjector.getWindowManager(), never()).stopFreezingScreen();
        List<String> expectedActions = Arrays.asList(Intent.ACTION_USER_STARTED,
                Intent.ACTION_USER_SWITCHED, Intent.ACTION_USER_STARTING);
        assertEquals(expectedActions, getActions(mInjector.sentIntents));
        Set<Integer> expectedCodes = new HashSet<>(
                Arrays.asList(REPORT_USER_SWITCH_MSG, USER_SWITCH_TIMEOUT_MSG,
                        SYSTEM_USER_START_MSG, SYSTEM_USER_CURRENT_MSG));
        Set<Integer> actualCodes = mInjector.handler.getMessageCodes();
        assertEquals("Unexpected message sent", expectedCodes, actualCodes);
        Message reportMsg = mInjector.handler.getMessageForCode(REPORT_USER_SWITCH_MSG);
        assertNotNull(reportMsg);
        UserState userState = (UserState) reportMsg.obj;
        assertNotNull(userState);
        assertEquals(TEST_USER_ID, userState.mHandle.getIdentifier());
        assertEquals("User must be in STATE_BOOTING", UserState.STATE_BOOTING, userState.state);
        assertEquals("Unexpected old user id", 0, reportMsg.arg1);
        assertEquals("Unexpected new user id", TEST_USER_ID, reportMsg.arg2);
    }

    public void testDispatchUserSwitch() throws RemoteException {
        // Prepare mock observer and register it
        IUserSwitchObserver observer = mock(IUserSwitchObserver.class);
        when(observer.asBinder()).thenReturn(new Binder());
        doAnswer(invocation -> {
            IRemoteCallback callback = (IRemoteCallback) invocation.getArguments()[1];
            callback.sendResult(null);
            return null;
        }).when(observer).onUserSwitching(anyInt(), any());
        mUserController.registerUserSwitchObserver(observer, "mock");
        // Start user -- this will update state of mUserController
        mUserController.startUser(TEST_USER_ID, true);
        Message reportMsg = mInjector.handler.getMessageForCode(REPORT_USER_SWITCH_MSG);
        assertNotNull(reportMsg);
        UserState userState = (UserState) reportMsg.obj;
        int oldUserId = reportMsg.arg1;
        int newUserId = reportMsg.arg2;
        // Call dispatchUserSwitch and verify that observer was called only once
        mInjector.handler.clearAllRecordedMessages();
        mUserController.dispatchUserSwitch(userState, oldUserId, newUserId);
        Mockito.verify(observer, times(1)).onUserSwitching(eq(TEST_USER_ID), any());
        Set<Integer> expectedCodes = Collections.singleton(CONTINUE_USER_SWITCH_MSG);
        Set<Integer> actualCodes = mInjector.handler.getMessageCodes();
        assertEquals("Unexpected message sent", expectedCodes, actualCodes);
        Message conMsg = mInjector.handler.getMessageForCode(CONTINUE_USER_SWITCH_MSG);
        assertNotNull(conMsg);
        userState = (UserState) conMsg.obj;
        assertNotNull(userState);
        assertEquals(TEST_USER_ID, userState.mHandle.getIdentifier());
        assertEquals("User must be in STATE_BOOTING", UserState.STATE_BOOTING, userState.state);
        assertEquals("Unexpected old user id", 0, conMsg.arg1);
        assertEquals("Unexpected new user id", TEST_USER_ID, conMsg.arg2);
    }

    public void testDispatchUserSwitchBadReceiver() throws RemoteException {
        // Prepare mock observer which doesn't notify the callback and register it
        IUserSwitchObserver observer = mock(IUserSwitchObserver.class);
        when(observer.asBinder()).thenReturn(new Binder());
        mUserController.registerUserSwitchObserver(observer, "mock");
        // Start user -- this will update state of mUserController
        mUserController.startUser(TEST_USER_ID, true);
        Message reportMsg = mInjector.handler.getMessageForCode(REPORT_USER_SWITCH_MSG);
        assertNotNull(reportMsg);
        UserState userState = (UserState) reportMsg.obj;
        int oldUserId = reportMsg.arg1;
        int newUserId = reportMsg.arg2;
        // Call dispatchUserSwitch and verify that observer was called only once
        mInjector.handler.clearAllRecordedMessages();
        mUserController.dispatchUserSwitch(userState, oldUserId, newUserId);
        Mockito.verify(observer, times(1)).onUserSwitching(eq(TEST_USER_ID), any());
        // Verify that CONTINUE_USER_SWITCH_MSG is not sent (triggers timeout)
        Set<Integer> actualCodes = mInjector.handler.getMessageCodes();
        assertTrue("No messages should be sent", actualCodes.isEmpty());
    }

    public void testContinueUserSwitch() throws RemoteException {
        // Start user -- this will update state of mUserController
        mUserController.startUser(TEST_USER_ID, true);
        Message reportMsg = mInjector.handler.getMessageForCode(REPORT_USER_SWITCH_MSG);
        assertNotNull(reportMsg);
        UserState userState = (UserState) reportMsg.obj;
        int oldUserId = reportMsg.arg1;
        int newUserId = reportMsg.arg2;
        mInjector.handler.clearAllRecordedMessages();
        // Verify that continueUserSwitch worked as expected
        mUserController.continueUserSwitch(userState, oldUserId, newUserId);
        Mockito.verify(mInjector.getWindowManager(), times(1)).stopFreezingScreen();
        Set<Integer> expectedCodes = Collections.singleton(REPORT_USER_SWITCH_COMPLETE_MSG);
        Set<Integer> actualCodes = mInjector.handler.getMessageCodes();
        assertEquals("Unexpected message sent", expectedCodes, actualCodes);
        Message msg = mInjector.handler.getMessageForCode(REPORT_USER_SWITCH_COMPLETE_MSG);
        assertNotNull(msg);
        assertEquals("Unexpected userId", TEST_USER_ID, msg.arg1);
    }

    private void setUpUser(int userId, int flags) {
        UserInfo userInfo = new UserInfo(userId, "User" + userId, flags);
        when(mInjector.userManagerMock.getUserInfo(eq(userId))).thenReturn(userInfo);
    }

    private static List<String> getActions(List<Intent> intents) {
        List<String> result = new ArrayList<>();
        for (Intent intent : intents) {
            result.add(intent.getAction());
        }
        return result;
    }

    private static class TestInjector extends UserController.Injector {
        final Object lock = new Object();
        TestHandler handler;
        HandlerThread handlerThread;
        UserManagerService userManagerMock;
        UserManagerInternal userManagerInternalMock;
        WindowManagerService windowManagerMock;
        private Context mCtx;
        List<Intent> sentIntents = new ArrayList<>();

        TestInjector(Context ctx) {
            super(null);
            mCtx = ctx;
            handlerThread = new HandlerThread(TAG);
            handlerThread.start();
            handler = new TestHandler(handlerThread.getLooper());
            userManagerMock = mock(UserManagerService.class);
            userManagerInternalMock = mock(UserManagerInternal.class);
            windowManagerMock = mock(WindowManagerService.class);
        }

        @Override
        protected Object getLock() {
            return lock;
        }

        @Override
        protected Handler getHandler() {
            return handler;
        }

        @Override
        protected UserManagerService getUserManager() {
            return userManagerMock;
        }

        @Override
        UserManagerInternal getUserManagerInternal() {
            return userManagerInternalMock;
        }

        @Override
        protected Context getContext() {
            return mCtx;
        }

        @Override
        int checkCallingPermission(String permission) {
            Log.i(TAG, "checkCallingPermission " + permission);
            return PERMISSION_GRANTED;
        }

        @Override
        void stackSupervisorSetLockTaskModeLocked(TaskRecord task, int lockTaskModeState,
                String reason, boolean andResume) {
            Log.i(TAG, "stackSupervisorSetLockTaskModeLocked");
        }

        @Override
        WindowManagerService getWindowManager() {
            return windowManagerMock;
        }

        @Override
        void updateUserConfigurationLocked() {
            Log.i(TAG, "updateUserConfigurationLocked");
        }

        @Override
        protected int broadcastIntentLocked(Intent intent, String resolvedType,
                IIntentReceiver resultTo, int resultCode, String resultData, Bundle resultExtras,
                String[] requiredPermissions, int appOp, Bundle bOptions, boolean ordered,
                boolean sticky, int callingPid, int callingUid, int userId) {
            Log.i(TAG, "broadcastIntentLocked " + intent);
            sentIntents.add(intent);
            return 0;
        }

        @Override
        boolean stackSupervisorSwitchUserLocked(int userId, UserState uss) {
            Log.i(TAG, "stackSupervisorSwitchUserLocked " + userId);
            return true;
        }

        @Override
        void startHomeActivityLocked(int userId, String reason) {
            Log.i(TAG, "startHomeActivityLocked " + userId);
        }
   }

    private static class TestHandler extends Handler {
        private final List<Message> mMessages = new ArrayList<>();

        TestHandler(Looper looper) {
            super(looper);
        }

        Set<Integer> getMessageCodes() {
            Set<Integer> result = new LinkedHashSet<>();
            for (Message msg : mMessages) {
                result.add(msg.what);
            }
            return result;
        }

        Message getMessageForCode(int what) {
            for (Message msg : mMessages) {
                if (msg.what == what) {
                    return msg;
                }
            }
            return null;
        }

        void clearAllRecordedMessages() {
            mMessages.clear();
        }

        @Override
        public boolean sendMessageAtTime(Message msg, long uptimeMillis) {
            Message copy = new Message();
            copy.copyFrom(msg);
            mMessages.add(copy);
            return super.sendMessageAtTime(msg, uptimeMillis);
        }
    }
}