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

import android.net.ConnectivityMetricsLogger;
import android.os.Parcel;
import android.os.Parcelable;

public class IpConnectivityEvent implements Parcelable {
    // IPRM = IpReachabilityMonitor
    // DHCP = DhcpClient
    // NETMON = NetworkMonitorEvent
    // CONSRV = ConnectivityServiceEvent
    public static final String TAG = "IpConnectivityEvent";
    public static final int IPCE_IPRM_BASE = 0*1024;
    public static final int IPCE_DHCP_BASE = 1*1024;
    public static final int IPCE_NETMON_BASE = 2*1024;
    public static final int IPCE_CONSRV_BASE = 3*1024;

    public static final int IPCE_IPRM_PROBE_RESULT = IPCE_IPRM_BASE + 0;
    public static final int IPCE_IPRM_MESSAGE_RECEIVED = IPCE_IPRM_BASE + 1;
    public static final int IPCE_DHCP_RECV_ERROR = IPCE_DHCP_BASE + 0;
    public static final int IPCE_DHCP_PARSE_ERROR = IPCE_DHCP_BASE + 1;
    public static final int IPCE_DHCP_TIMEOUT = IPCE_DHCP_BASE + 2;
    public static final int IPCE_DHCP_STATE_CHANGE = IPCE_DHCP_BASE + 3;
    public static final int IPCE_NETMON_STATE_CHANGE = IPCE_NETMON_BASE + 0;
    public static final int IPCE_NETMON_CHECK_RESULT = IPCE_NETMON_BASE + 1;
    public static final int IPCE_CONSRV_DEFAULT_NET_CHANGE = IPCE_CONSRV_BASE + 0;

    private static ConnectivityMetricsLogger mMetricsLogger = new ConnectivityMetricsLogger();

    public int describeContents() {
        return 0;
    }

    public void writeToParcel(Parcel out, int flags) {
    }

    public static void logEvent(int tag, IpConnectivityEvent event) {
        long timestamp = System.currentTimeMillis();
        mMetricsLogger.logEvent(timestamp, ConnectivityMetricsLogger.COMPONENT_TAG_CONNECTIVITY,
                tag, event);
    }
};
