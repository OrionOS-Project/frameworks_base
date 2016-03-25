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

package android.net.metrics;

import android.os.Parcel;
import android.os.Parcelable;

public class CaptivePortalStateChangeEvent extends IpConnectivityEvent implements Parcelable {
    public static final String TAG = "CaptivePortalStateChangeEvent";

    public static final int NETWORK_MONITOR_CONNECTED = 0;
    public static final int NETWORK_MONITOR_DISCONNECTED = 1;
    public static final int NETWORK_MONITOR_VALIDATED = 2;
    private int mState;

    public CaptivePortalStateChangeEvent(int state) {
        mState = state;
    }

    public CaptivePortalStateChangeEvent(Parcel in) {
        mState = in.readInt();
    }

    public void writeToParcel(Parcel out, int flags) {
        out.writeInt(mState);
    }

    public static final Parcelable.Creator<CaptivePortalStateChangeEvent> CREATOR
        = new Parcelable.Creator<CaptivePortalStateChangeEvent>() {
        public CaptivePortalStateChangeEvent createFromParcel(Parcel in) {
            return new CaptivePortalStateChangeEvent(in);
        }

        public CaptivePortalStateChangeEvent[] newArray(int size) {
            return new CaptivePortalStateChangeEvent[size];
        }
    };

    public static void logEvent(int state) {
        IpConnectivityEvent.logEvent(IpConnectivityEvent.IPCE_NETMON_STATE_CHANGE,
                new CaptivePortalStateChangeEvent(state));
    }
};
