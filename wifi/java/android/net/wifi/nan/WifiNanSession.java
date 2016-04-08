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

package android.net.wifi.nan;

import android.util.Log;

import java.lang.ref.WeakReference;

/**
 * A representation of a single publish or subscribe NAN session. This object
 * will not be created directly - only its child classes are available:
 * {@link WifiNanPublishSession} and {@link WifiNanSubscribeSession}.
 *
 * @hide PROPOSED_NAN_API
 */
public class WifiNanSession {
    private static final String TAG = "WifiNanSession";
    private static final boolean DBG = false;
    private static final boolean VDBG = false; // STOPSHIP if true

    /**
     * @hide
     */
    protected WeakReference<WifiNanManager> mMgr;

    /**
     * @hide
     */
    protected final int mSessionId;

    /**
     * @hide
     */
    protected boolean mTerminated = false;

    /**
     * {@hide}
     */
    public WifiNanSession(WifiNanManager manager, int sessionId) {
        if (VDBG) Log.v(TAG, "New client created: manager=" + manager + ", sessionId=" + sessionId);

        mMgr = new WeakReference<>(manager);
        mSessionId = sessionId;
    }

    /**
     * Terminate the current publish or subscribe session - i.e. stop
     * transmitting packet on-air (for an active session) or listening for
     * matches (for a passive session). The session may not be used for any
     * additional operations are termination.
     */
    public void terminate() {
        WifiNanManager mgr = mMgr.get();
        if (mgr == null) {
            Log.w(TAG, "terminate: called post GC on WifiNanManager");
            return;
        }
        mgr.terminateSession(mSessionId);
        mTerminated = true;
        mMgr.clear();
    }

    /**
     * Sets the status of the session to terminated - i.e. an indication that
     * already terminated rather than executing a termination.
     *
     * @hide
     */
    public void setTerminated() {
        if (mTerminated) {
            Log.w(TAG, "terminate: already terminated.");
            return;
        }
        mTerminated = true;
        mMgr.clear();
    }

    @Override
    protected void finalize() throws Throwable {
        if (!mTerminated) {
            Log.w(TAG, "WifiNanSession mSessionId=" + mSessionId
                    + " was not explicitly terminated. The session may use resources until "
                    + "terminated so step should be done explicitly");
            terminate();
        }
    }

    /**
     * Sends a message to the specified destination. Message transmission is
     * part of the current discovery session - i.e. executed subsequent to a
     * publish/subscribe
     * {@link WifiNanSessionCallback#onMatch(int, byte[], int, byte[], int)}
     * event.
     *
     * @param peerId The peer's ID for the message. Must be a result of an
     *            {@link WifiNanSessionCallback#onMatch(int, byte[], int, byte[], int)}
     *            event.
     * @param message The message to be transmitted.
     * @param messageLength The number of bytes from the {@code message} to be
     *            transmitted.
     * @param messageId An arbitrary integer used by the caller to identify the
     *            message. The same integer ID will be returned in the callbacks
     *            indicated message send success or failure.
     */
    public void sendMessage(int peerId, byte[] message, int messageLength, int messageId) {
        if (mTerminated) {
            Log.w(TAG, "sendMessage: called on terminated session");
            return;
        } else {
            WifiNanManager mgr = mMgr.get();
            if (mgr == null) {
                Log.w(TAG, "sendMessage: called post GC on WifiNanManager");
                return;
            }

            mgr.sendMessage(mSessionId, peerId, message, messageLength, messageId);
        }
    }
}
