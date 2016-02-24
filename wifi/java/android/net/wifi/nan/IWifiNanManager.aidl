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

import android.app.PendingIntent;

import android.net.wifi.nan.ConfigRequest;
import android.net.wifi.nan.IWifiNanEventCallback;
import android.net.wifi.nan.IWifiNanSessionCallback;
import android.net.wifi.nan.PublishConfig;
import android.net.wifi.nan.SubscribeConfig;

/**
 * Interface that WifiNanService implements
 *
 * {@hide}
 */
interface IWifiNanManager
{
    // client API
    int connect(in IBinder binder, in IWifiNanEventCallback callback, int events);
    void disconnect(int clientId, in IBinder binder);
    void requestConfig(int clientId, in ConfigRequest configRequest);

    // session API
    int createSession(int clientId, in IWifiNanSessionCallback callback, int events);
    void publish(int clientId, int sessionId, in PublishConfig publishConfig);
    void subscribe(int clientId, int sessionId, in SubscribeConfig subscribeConfig);
    void sendMessage(int clientId, int sessionId, int peerId, in byte[] message, int messageLength,
            int messageId);
    void stopSession(int clientId, int sessionId);
    void destroySession(int clientId, int sessionId);
}
