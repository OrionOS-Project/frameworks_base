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

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.util.Log;

/**
 * Base class for NAN session events callbacks. Should be extended by
 * applications wanting notifications. The callbacks are registered when a
 * publish or subscribe session is created using
 * {@link WifiNanManager#publish(PublishConfig, WifiNanSessionCallback, int)} or
 * {@link WifiNanManager#subscribe(SubscribeConfig, WifiNanSessionCallback, int)}
 * . These are callbacks applying to a specific NAN session. Events
 * corresponding to the NAN link are delivered using
 * {@link WifiNanEventCallback}.
 * <p>
 * A single callback is registered at session creation - it cannot be replaced.
 * <p>
 * During registration specify which specific events are desired using a set of
 * {@code WifiNanSessionCallback.LISTEN_*} flags OR'd together. Only those
 * events will be delivered to the registered callback. Override those callbacks
 * {@code WifiNanSessionCallback.on*} for the registered events.
 *
 * @hide PROPOSED_NAN_API
 */
public class WifiNanSessionCallback {
    private static final String TAG = "WifiNanSessionCallback";
    private static final boolean DBG = false;
    private static final boolean VDBG = false; // STOPSHIP if true

    /**
     * Publish fail callback event registration flag. Corresponding callback is
     * {@link WifiNanSessionCallback#onPublishFail(int)}.
     *
     * @hide
     */
    public static final int FLAG_LISTEN_PUBLISH_FAIL = 0x1 << 0;

    /**
     * Publish terminated callback event registration flag. Corresponding
     * callback is {@link WifiNanSessionCallback#onPublishTerminated(int)}.
     */
    public static final int FLAG_LISTEN_PUBLISH_TERMINATED = 0x1 << 1;

    /**
     * Subscribe fail callback event registration flag. Corresponding callback
     * is {@link WifiNanSessionCallback#onSubscribeFail(int)}.
     *
     * @hide
     */
    public static final int FLAG_LISTEN_SUBSCRIBE_FAIL = 0x1 << 2;

    /**
     * Subscribe terminated callback event registration flag. Corresponding
     * callback is {@link WifiNanSessionCallback#onSubscribeTerminated(int)}.
     */
    public static final int FLAG_LISTEN_SUBSCRIBE_TERMINATED = 0x1 << 3;

    /**
     * Match (discovery: publish or subscribe) callback event registration flag.
     * Corresponding callback is
     * {@link WifiNanSessionCallback#onMatch(int, byte[], int, byte[], int)}.
     *
     * @hide
     */
    public static final int FLAG_LISTEN_MATCH = 0x1 << 4;

    /**
     * Message sent successfully callback event registration flag. Corresponding
     * callback is {@link WifiNanSessionCallback#onMessageSendSuccess()}.
     *
     * @hide
     */
    public static final int FLAG_LISTEN_MESSAGE_SEND_SUCCESS = 0x1 << 5;

    /**
     * Message sending failure callback event registration flag. Corresponding
     * callback is {@link WifiNanSessionCallback#onMessageSendFail(int)}.
     *
     * @hide
     */
    public static final int FLAG_LISTEN_MESSAGE_SEND_FAIL = 0x1 << 6;

    /**
     * Message received callback event registration flag. Corresponding callback
     * is {@link WifiNanSessionCallback#onMessageReceived(int, byte[], int)}.
     *
     * @hide
     */
    public static final int FLAG_LISTEN_MESSAGE_RECEIVED = 0x1 << 7;

    /**
     * List of hidden events: which are mandatory - i.e. they will be added to
     * every request.
     *
     * @hide
     */
    public static final int LISTEN_HIDDEN_FLAGS = FLAG_LISTEN_PUBLISH_FAIL
            | FLAG_LISTEN_SUBSCRIBE_FAIL | FLAG_LISTEN_MATCH | FLAG_LISTEN_MESSAGE_SEND_SUCCESS
            | FLAG_LISTEN_MESSAGE_SEND_FAIL | FLAG_LISTEN_MESSAGE_RECEIVED;

    /**
     * Failure reason flag for {@link WifiNanEventCallback} and
     * {@link WifiNanSessionCallback} callbacks. Indicates no resources to
     * execute the requested operation.
     */
    public static final int FAIL_REASON_NO_RESOURCES = 0;

    /**
     * Failure reason flag for {@link WifiNanEventCallback} and
     * {@link WifiNanSessionCallback} callbacks. Indicates invalid argument in
     * the requested operation.
     */
    public static final int FAIL_REASON_INVALID_ARGS = 1;

    /**
     * Failure reason flag for {@link WifiNanEventCallback} and
     * {@link WifiNanSessionCallback} callbacks. Indicates a message is
     * transmitted without a match (i.e. a discovery) occurring first.
     */
    public static final int FAIL_REASON_NO_MATCH_SESSION = 2;

    /**
     * Failure reason flag for {@link WifiNanEventCallback} and
     * {@link WifiNanSessionCallback} callbacks. Indicates an unspecified error
     * occurred during the operation.
     */
    public static final int FAIL_REASON_OTHER = 3;

    /**
     * Failure reason flag for
     * {@link WifiNanSessionCallback#onPublishTerminated(int)} and
     * {@link WifiNanSessionCallback#onSubscribeTerminated(int)} callbacks.
     * Indicates that publish or subscribe session is done - i.e. all the
     * requested operations (per {@link PublishConfig} or
     * {@link SubscribeConfig}) have been executed.
     */
    public static final int TERMINATE_REASON_DONE = 0;

    /**
     * Failure reason flag for
     * {@link WifiNanSessionCallback#onPublishTerminated(int)} and
     * {@link WifiNanSessionCallback#onSubscribeTerminated(int)} callbacks.
     * Indicates that publish or subscribe session is terminated due to a
     * failure.
     */
    public static final int TERMINATE_REASON_FAIL = 1;

    private static final String MESSAGE_BUNDLE_KEY_PEER_ID = "peer_id";
    private static final String MESSAGE_BUNDLE_KEY_MESSAGE = "message";
    private static final String MESSAGE_BUNDLE_KEY_MESSAGE2 = "message2";

    private final Handler mHandler;

    /**
     * Constructs a {@link WifiNanSessionCallback} using the looper of the
     * current thread. I.e. all callbacks will be delivered on the current
     * thread.
     */
    public WifiNanSessionCallback() {
        this(Looper.myLooper());
    }

    /**
     * Constructs a {@link WifiNanSessionCallback} using the specified looper.
     * I.e. all callbacks will delivered on the thread of the specified looper.
     *
     * @param looper The looper on which to execute the callbacks.
     */
    public WifiNanSessionCallback(Looper looper) {
        if (VDBG) Log.v(TAG, "ctor: looper=" + looper);
        mHandler = new Handler(looper) {
            @Override
            public void handleMessage(Message msg) {
                if (DBG) Log.d(TAG, "What=" + msg.what + ", msg=" + msg);
                switch (msg.what) {
                    case FLAG_LISTEN_PUBLISH_FAIL:
                        WifiNanSessionCallback.this.onPublishFail(msg.arg1);
                        break;
                    case FLAG_LISTEN_PUBLISH_TERMINATED:
                        WifiNanSessionCallback.this.onPublishTerminated(msg.arg1);
                        break;
                    case FLAG_LISTEN_SUBSCRIBE_FAIL:
                        WifiNanSessionCallback.this.onSubscribeFail(msg.arg1);
                        break;
                    case FLAG_LISTEN_SUBSCRIBE_TERMINATED:
                        WifiNanSessionCallback.this.onSubscribeTerminated(msg.arg1);
                        break;
                    case FLAG_LISTEN_MATCH:
                        WifiNanSessionCallback.this.onMatch(
                                msg.getData().getInt(MESSAGE_BUNDLE_KEY_PEER_ID),
                                msg.getData().getByteArray(MESSAGE_BUNDLE_KEY_MESSAGE), msg.arg1,
                                msg.getData().getByteArray(MESSAGE_BUNDLE_KEY_MESSAGE2), msg.arg2);
                        break;
                    case FLAG_LISTEN_MESSAGE_SEND_SUCCESS:
                        WifiNanSessionCallback.this.onMessageSendSuccess(msg.arg1);
                        break;
                    case FLAG_LISTEN_MESSAGE_SEND_FAIL:
                        WifiNanSessionCallback.this.onMessageSendFail(msg.arg1, msg.arg2);
                        break;
                    case FLAG_LISTEN_MESSAGE_RECEIVED:
                        WifiNanSessionCallback.this.onMessageReceived(msg.arg2,
                                msg.getData().getByteArray(MESSAGE_BUNDLE_KEY_MESSAGE), msg.arg1);
                        break;
                }
            }
        };
    }

    /**
     * Called when a publish operation fails. It is dummy method (empty
     * implementation printing out a log message). Override to implement your
     * custom response.
     *
     * @param reason The failure reason using
     *            {@code WifiNanSessionCallback.FAIL_*} codes.
     */
    public void onPublishFail(int reason) {
        if (VDBG) Log.v(TAG, "onPublishFail: called in stub - override if interested");
    }

    /**
     * Called when a publish operation terminates. Event will only be delivered
     * if registered using
     * {@link WifiNanSessionCallback#FLAG_LISTEN_PUBLISH_TERMINATED}. A dummy (empty
     * implementation printing out a warning). Make sure to override if
     * registered.
     *
     * @param reason The termination reason using
     *            {@code WifiNanSessionCallback.TERMINATE_*} codes.
     */
    public void onPublishTerminated(int reason) {
        Log.w(TAG, "onPublishTerminated: called in stub - override if interested or disable");
    }

    /**
     * Called when a subscribe operation fails. It is dummy method (empty
     * implementation printing out a log message). Override to implement your
     * custom response.
     *
     * @param reason The failure reason using
     *            {@code WifiNanSessionCallback.FAIL_*} codes.
     */
    public void onSubscribeFail(int reason) {
        if (VDBG) Log.v(TAG, "onSubscribeFail: called in stub - override if interested");
    }

    /**
     * Called when a subscribe operation terminates. Event will only be
     * delivered if registered using
     * {@link WifiNanSessionCallback#FLAG_LISTEN_SUBSCRIBE_TERMINATED}. A dummy
     * (empty implementation printing out a warning). Make sure to override if
     * registered.
     *
     * @param reason The termination reason using
     *            {@code WifiNanSessionCallback.TERMINATE_*} codes.
     */
    public void onSubscribeTerminated(int reason) {
        Log.w(TAG, "onSubscribeTerminated: called in stub - override if interested or disable");
    }

    /**
     * Called when a discovery (publish or subscribe) operation results in a
     * match - i.e. when a peer is discovered. It is dummy method (empty
     * implementation printing out a log message). Override to implement your
     * custom response.
     *
     * @param peerId The ID of the peer matching our discovery operation.
     * @param serviceSpecificInfo The service specific information (arbitrary
     *            byte array) provided by the peer as part of its discovery
     *            packet.
     * @param serviceSpecificInfoLength The length of the service specific
     *            information array.
     * @param matchFilter The filter (Tx on advertiser and Rx on listener) which
     *            resulted in this match.
     * @param matchFilterLength The length of the match filter array.
     */
    public void onMatch(int peerId, byte[] serviceSpecificInfo,
            int serviceSpecificInfoLength, byte[] matchFilter, int matchFilterLength) {
        if (VDBG) Log.v(TAG, "onMatch: called in stub - override if interested");
    }

    /**
     * Called when a message is transmitted successfully - i.e. when we know
     * that it was received successfully (corresponding to an ACK being
     * received). It is dummy method (empty implementation printing out a log
     * message). Override to implement your custom response.
     * <p>
     * Note that either this callback or
     * {@link WifiNanSessionCallback#onMessageSendFail(int, int)} will be
     * received - never both.
     */
    public void onMessageSendSuccess(int messageId) {
        if (VDBG) Log.v(TAG, "onMessageSendSuccess: called in stub - override if interested");
    }

    /**
     * Called when a message transmission fails - i.e. when no ACK is received.
     * The hardware will usually attempt to re-transmit several times - this
     * event is received after all retries are exhausted. There is a possibility
     * that message was received by the destination successfully but the ACK was
     * lost. It is dummy method (empty implementation printing out a log
     * message). Override to implement your custom response.
     * <p>
     * Note that either this callback or
     * {@link WifiNanSessionCallback#onMessageSendSuccess(int)} will be received
     * - never both
     *
     * @param reason The failure reason using
     *            {@code WifiNanSessionCallback.FAIL_*} codes.
     */
    public void onMessageSendFail(int messageId, int reason) {
        if (VDBG) Log.v(TAG, "onMessageSendFail: called in stub - override if interested");
    }

    /**
     * Called when a message is received from a discovery session peer. It is
     * dummy method (empty implementation printing out a log message). Override
     * to implement your custom response.
     *
     * @param peerId The ID of the peer sending the message.
     * @param message A byte array containing the message.
     * @param messageLength The length of the byte array containing the relevant
     *            message bytes.
     */
    public void onMessageReceived(int peerId, byte[] message, int messageLength) {
        if (VDBG) Log.v(TAG, "onMessageReceived: called in stub - override if interested");
    }

    /**
     * {@hide}
     */
    public IWifiNanSessionCallback callback = new IWifiNanSessionCallback.Stub() {
        @Override
        public void onPublishFail(int reason) {
            if (VDBG) Log.v(TAG, "onPublishFail: reason=" + reason);

            Message msg = mHandler.obtainMessage(FLAG_LISTEN_PUBLISH_FAIL);
            msg.arg1 = reason;
            mHandler.sendMessage(msg);
        }

        @Override
        public void onPublishTerminated(int reason) {
            if (VDBG) Log.v(TAG, "onPublishResponse: reason=" + reason);

            Message msg = mHandler.obtainMessage(FLAG_LISTEN_PUBLISH_TERMINATED);
            msg.arg1 = reason;
            mHandler.sendMessage(msg);
        }

        @Override
        public void onSubscribeFail(int reason) {
            if (VDBG) Log.v(TAG, "onSubscribeFail: reason=" + reason);

            Message msg = mHandler.obtainMessage(FLAG_LISTEN_SUBSCRIBE_FAIL);
            msg.arg1 = reason;
            mHandler.sendMessage(msg);
        }

        @Override
        public void onSubscribeTerminated(int reason) {
            if (VDBG) Log.v(TAG, "onSubscribeTerminated: reason=" + reason);

            Message msg = mHandler.obtainMessage(FLAG_LISTEN_SUBSCRIBE_TERMINATED);
            msg.arg1 = reason;
            mHandler.sendMessage(msg);
        }

        @Override
        public void onMatch(int peerId, byte[] serviceSpecificInfo,
                int serviceSpecificInfoLength, byte[] matchFilter, int matchFilterLength) {
            if (VDBG) Log.v(TAG, "onMatch: peerId=" + peerId);

            Bundle data = new Bundle();
            data.putInt(MESSAGE_BUNDLE_KEY_PEER_ID, peerId);
            data.putByteArray(MESSAGE_BUNDLE_KEY_MESSAGE, serviceSpecificInfo);
            data.putByteArray(MESSAGE_BUNDLE_KEY_MESSAGE2, matchFilter);

            Message msg = mHandler.obtainMessage(FLAG_LISTEN_MATCH);
            msg.arg1 = serviceSpecificInfoLength;
            msg.arg2 = matchFilterLength;
            msg.setData(data);
            mHandler.sendMessage(msg);
        }

        @Override
        public void onMessageSendSuccess(int messageId) {
            if (VDBG) Log.v(TAG, "onMessageSendSuccess");

            Message msg = mHandler.obtainMessage(FLAG_LISTEN_MESSAGE_SEND_SUCCESS);
            msg.arg1 = messageId;
            mHandler.sendMessage(msg);
        }

        @Override
        public void onMessageSendFail(int messageId, int reason) {
            if (VDBG) Log.v(TAG, "onMessageSendFail: reason=" + reason);

            Message msg = mHandler.obtainMessage(FLAG_LISTEN_MESSAGE_SEND_FAIL);
            msg.arg1 = messageId;
            msg.arg2 = reason;
            mHandler.sendMessage(msg);
        }

        @Override
        public void onMessageReceived(int peerId, byte[] message, int messageLength) {
            if (VDBG) {
                Log.v(TAG, "onMessageReceived: peerId='" + peerId + "', messageLength="
                        + messageLength);
            }

            Bundle data = new Bundle();
            data.putByteArray(MESSAGE_BUNDLE_KEY_MESSAGE, message);

            Message msg = mHandler.obtainMessage(FLAG_LISTEN_MESSAGE_RECEIVED);
            msg.arg1 = messageLength;
            msg.arg2 = peerId;
            msg.setData(data);
            mHandler.sendMessage(msg);
        }
    };
}
