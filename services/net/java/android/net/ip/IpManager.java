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

package android.net.ip;

import com.android.internal.util.MessageUtils;

import android.content.Context;
import android.net.apf.ApfCapabilities;
import android.net.apf.ApfFilter;
import android.net.DhcpResults;
import android.net.InterfaceConfiguration;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.LinkProperties.ProvisioningChange;
import android.net.ProxyInfo;
import android.net.RouteInfo;
import android.net.StaticIpConfiguration;
import android.net.dhcp.DhcpClient;
import android.os.INetworkManagementService;
import android.os.Message;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.text.TextUtils;
import android.util.Log;
import android.util.SparseArray;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.IndentingPrintWriter;
import com.android.internal.util.State;
import com.android.internal.util.StateMachine;
import com.android.server.net.NetlinkTracker;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.Objects;


/**
 * IpManager
 *
 * This class provides the interface to IP-layer provisioning and maintenance
 * functionality that can be used by transport layers like Wi-Fi, Ethernet,
 * et cetera.
 *
 * [ Lifetime ]
 * IpManager is designed to be instantiated as soon as the interface name is
 * known and can be as long-lived as the class containing it (i.e. declaring
 * it "private final" is okay).
 *
 * @hide
 */
public class IpManager extends StateMachine {
    private static final boolean DBG = false;
    private static final boolean VDBG = false;

    // For message logging.
    private static final Class[] sMessageClasses = { IpManager.class, DhcpClient.class };
    private static final SparseArray<String> sWhatToString =
            MessageUtils.findMessageNames(sMessageClasses);

    /**
     * Callbacks for handling IpManager events.
     */
    public static class Callback {
        // In order to receive onPreDhcpAction(), call #withPreDhcpAction()
        // when constructing a ProvisioningConfiguration.
        //
        // Implementations of onPreDhcpAction() must call
        // IpManager#completedPreDhcpAction() to indicate that DHCP is clear
        // to proceed.
        public void onPreDhcpAction() {}
        public void onPostDhcpAction() {}

        // This is purely advisory and not an indication of provisioning
        // success or failure.  This is only here for callers that want to
        // expose DHCPv4 results to other APIs (e.g., WifiInfo#setInetAddress).
        // DHCPv4 or static IPv4 configuration failure or success can be
        // determined by whether or not the passed-in DhcpResults object is
        // null or not.
        public void onNewDhcpResults(DhcpResults dhcpResults) {}

        public void onProvisioningSuccess(LinkProperties newLp) {}
        public void onProvisioningFailure(LinkProperties newLp) {}

        // Invoked on LinkProperties changes.
        public void onLinkPropertiesChange(LinkProperties newLp) {}

        // Called when the internal IpReachabilityMonitor (if enabled) has
        // detected the loss of a critical number of required neighbors.
        public void onReachabilityLost(String logMsg) {}

        // Called when the IpManager state machine terminates.
        public void onQuit() {}

        // Install an APF program to filter incoming packets.
        public void installPacketFilter(byte[] filter) {}

        // If multicast filtering cannot be accomplished with APF, this function will be called to
        // actuate multicast filtering using another means.
        public void setFallbackMulticastFilter(boolean enabled) {}

        // Enabled/disable Neighbor Discover offload functionality. This is
        // called, for example, whenever 464xlat is being started or stopped.
        public void setNeighborDiscoveryOffload(boolean enable) {}
    }

    public static class WaitForProvisioningCallback extends Callback {
        private LinkProperties mCallbackLinkProperties;

        public LinkProperties waitForProvisioning() {
            synchronized (this) {
                try {
                    wait();
                } catch (InterruptedException e) {}
                return mCallbackLinkProperties;
            }
        }

        @Override
        public void onProvisioningSuccess(LinkProperties newLp) {
            synchronized (this) {
                mCallbackLinkProperties = newLp;
                notify();
            }
        }

        @Override
        public void onProvisioningFailure(LinkProperties newLp) {
            synchronized (this) {
                mCallbackLinkProperties = null;
                notify();
            }
        }
    }

    /**
     * This class encapsulates parameters to be passed to
     * IpManager#startProvisioning(). A defensive copy is made by IpManager
     * and the values specified herein are in force until IpManager#stop()
     * is called.
     *
     * Example use:
     *
     *     final ProvisioningConfiguration config =
     *             mIpManager.buildProvisioningConfiguration()
     *                     .withPreDhcpAction()
     *                     .build();
     *     mIpManager.startProvisioning(config);
     *     ...
     *     mIpManager.stop();
     *
     * The specified provisioning configuration will only be active until
     * IpManager#stop() is called. Future calls to IpManager#startProvisioning()
     * must specify the configuration again.
     */
    public static class ProvisioningConfiguration {

        public static class Builder {
            private ProvisioningConfiguration mConfig = new ProvisioningConfiguration();

            public Builder withoutIpReachabilityMonitor() {
                mConfig.mUsingIpReachabilityMonitor = false;
                return this;
            }

            public Builder withPreDhcpAction() {
                mConfig.mRequestedPreDhcpAction = true;
                return this;
            }

            public Builder withStaticConfiguration(StaticIpConfiguration staticConfig) {
                mConfig.mStaticIpConfig = staticConfig;
                return this;
            }

            public Builder withApfCapabilities(ApfCapabilities apfCapabilities) {
                mConfig.mApfCapabilities = apfCapabilities;
                return this;
            }

            public ProvisioningConfiguration build() {
                return new ProvisioningConfiguration(mConfig);
            }
        }

        /* package */ boolean mUsingIpReachabilityMonitor = true;
        /* package */ boolean mRequestedPreDhcpAction;
        /* package */ StaticIpConfiguration mStaticIpConfig;
        /* package */ ApfCapabilities mApfCapabilities;

        public ProvisioningConfiguration() {}

        public ProvisioningConfiguration(ProvisioningConfiguration other) {
            mUsingIpReachabilityMonitor = other.mUsingIpReachabilityMonitor;
            mRequestedPreDhcpAction = other.mRequestedPreDhcpAction;
            mStaticIpConfig = other.mStaticIpConfig;
            mApfCapabilities = other.mApfCapabilities;
        }
    }

    public static final String DUMP_ARG = "ipmanager";

    private static final int CMD_STOP = 1;
    private static final int CMD_START = 2;
    private static final int CMD_CONFIRM = 3;
    private static final int EVENT_PRE_DHCP_ACTION_COMPLETE = 4;
    // Sent by NetlinkTracker to communicate netlink events.
    private static final int EVENT_NETLINK_LINKPROPERTIES_CHANGED = 5;
    private static final int CMD_UPDATE_TCP_BUFFER_SIZES = 6;
    private static final int CMD_UPDATE_HTTP_PROXY = 7;
    private static final int CMD_SET_MULTICAST_FILTER = 8;

    private static final int MAX_LOG_RECORDS = 1000;

    private static final boolean NO_CALLBACKS = false;
    private static final boolean SEND_CALLBACKS = true;

    // This must match the interface prefix in clatd.c.
    // TODO: Revert this hack once IpManager and Nat464Xlat work in concert.
    private static final String CLAT_PREFIX = "v4-";

    private final Object mLock = new Object();
    private final State mStoppedState = new StoppedState();
    private final State mStoppingState = new StoppingState();
    private final State mStartedState = new StartedState();

    private final String mTag;
    private final Context mContext;
    private final String mInterfaceName;
    private final String mClatInterfaceName;
    @VisibleForTesting
    protected final Callback mCallback;
    private final INetworkManagementService mNwService;
    private final NetlinkTracker mNetlinkTracker;

    private NetworkInterface mNetworkInterface;

    /**
     * Non-final member variables accessed only from within our StateMachine.
     */
    private ProvisioningConfiguration mConfiguration;
    private IpReachabilityMonitor mIpReachabilityMonitor;
    private DhcpClient mDhcpClient;
    private DhcpResults mDhcpResults;
    private String mTcpBufferSizes;
    private ProxyInfo mHttpProxy;
    private ApfFilter mApfFilter;
    private boolean mMulticastFiltering;

    /**
     * Member variables accessed both from within the StateMachine thread
     * and via accessors from other threads.
     */
    @GuardedBy("mLock")
    private LinkProperties mLinkProperties;

    public IpManager(Context context, String ifName, Callback callback)
                throws IllegalArgumentException {
        this(context, ifName, callback, INetworkManagementService.Stub.asInterface(
                ServiceManager.getService(Context.NETWORKMANAGEMENT_SERVICE)));
    }

    /**
     * An expanded constructor, useful for dependency injection.
     */
    public IpManager(Context context, String ifName, Callback callback,
            INetworkManagementService nwService) throws IllegalArgumentException {
        super(IpManager.class.getSimpleName() + "." + ifName);
        mTag = getName();

        mContext = context;
        mInterfaceName = ifName;
        mClatInterfaceName = CLAT_PREFIX + ifName;
        mCallback = callback;
        mNwService = nwService;

        mNetlinkTracker = new NetlinkTracker(
                mInterfaceName,
                new NetlinkTracker.Callback() {
                    @Override
                    public void update() {
                        sendMessage(EVENT_NETLINK_LINKPROPERTIES_CHANGED);
                    }
                }) {
            @Override
            public void interfaceAdded(String iface) {
                super.interfaceAdded(iface);
                if (mClatInterfaceName.equals(iface)) {
                    mCallback.setNeighborDiscoveryOffload(false);
                }
            }

            @Override
            public void interfaceRemoved(String iface) {
                super.interfaceRemoved(iface);
                if (mClatInterfaceName.equals(iface)) {
                    // TODO: consider sending a message to the IpManager main
                    // StateMachine thread, in case "NDO enabled" state becomes
                    // tied to more things that 464xlat operation.
                    mCallback.setNeighborDiscoveryOffload(true);
                }
            }
        };

        try {
            mNwService.registerObserver(mNetlinkTracker);
        } catch (RemoteException e) {
            Log.e(mTag, "Couldn't register NetlinkTracker: " + e.toString());
        }

        resetLinkProperties();

        // Super simple StateMachine.
        addState(mStoppedState);
        addState(mStartedState);
        addState(mStoppingState);

        setInitialState(mStoppedState);
        setLogRecSize(MAX_LOG_RECORDS);
        super.start();
    }

    @Override
    protected void onQuitting() {
        mCallback.onQuit();
    }

    // Shut down this IpManager instance altogether.
    public void shutdown() {
        stop();
        quit();
    }

    public static ProvisioningConfiguration.Builder buildProvisioningConfiguration() {
        return new ProvisioningConfiguration.Builder();
    }

    public void startProvisioning(ProvisioningConfiguration req) {
        getNetworkInterface();

        mCallback.setNeighborDiscoveryOffload(true);
        sendMessage(CMD_START, new ProvisioningConfiguration(req));
    }

    // TODO: Delete this.
    public void startProvisioning(StaticIpConfiguration staticIpConfig) {
        startProvisioning(buildProvisioningConfiguration()
                .withStaticConfiguration(staticIpConfig)
                .build());
    }

    public void startProvisioning() {
        startProvisioning(new ProvisioningConfiguration());
    }

    public void stop() {
        sendMessage(CMD_STOP);
    }

    public void confirmConfiguration() {
        sendMessage(CMD_CONFIRM);
    }

    public void completedPreDhcpAction() {
        sendMessage(EVENT_PRE_DHCP_ACTION_COMPLETE);
    }

    /**
     * Set the TCP buffer sizes to use.
     *
     * This may be called, repeatedly, at any time before or after a call to
     * #startProvisioning(). The setting is cleared upon calling #stop().
     */
    public void setTcpBufferSizes(String tcpBufferSizes) {
        sendMessage(CMD_UPDATE_TCP_BUFFER_SIZES, tcpBufferSizes);
    }

    /**
     * Set the HTTP Proxy configuration to use.
     *
     * This may be called, repeatedly, at any time before or after a call to
     * #startProvisioning(). The setting is cleared upon calling #stop().
     */
    public void setHttpProxy(ProxyInfo proxyInfo) {
        sendMessage(CMD_UPDATE_HTTP_PROXY, proxyInfo);
    }

    /**
     * Enable or disable the multicast filter.  Attempts to use APF to accomplish the filtering,
     * if not, Callback.setFallbackMulticastFilter() is called.
     */
    public void setMulticastFilter(boolean enabled) {
        sendMessage(CMD_SET_MULTICAST_FILTER, enabled);
    }

    public LinkProperties getLinkProperties() {
        synchronized (mLock) {
            return new LinkProperties(mLinkProperties);
        }
    }

    public void dump(FileDescriptor fd, PrintWriter writer, String[] args) {
        IndentingPrintWriter pw = new IndentingPrintWriter(writer, "  ");
        pw.println("APF dump:");
        pw.increaseIndent();
        // Thread-unsafe access to mApfFilter but just used for debugging.
        ApfFilter apfFilter = mApfFilter;
        if (apfFilter != null) {
            apfFilter.dump(pw);
        } else {
            pw.println("No apf support");
        }
        pw.decreaseIndent();
    }


    /**
     * Internals.
     */

    @Override
    protected String getWhatToString(int what) {
        return sWhatToString.get(what, "UNKNOWN: " + Integer.toString(what));
    }

    @Override
    protected String getLogRecString(Message msg) {
        final String logLine = String.format(
                "iface{%s/%d} arg1{%d} arg2{%d} obj{%s}",
                mInterfaceName, mNetworkInterface == null ? -1 : mNetworkInterface.getIndex(),
                msg.arg1, msg.arg2, Objects.toString(msg.obj));
        if (VDBG) {
            Log.d(mTag, getWhatToString(msg.what) + " " + logLine);
        }
        return logLine;
    }

    private void getNetworkInterface() {
        try {
            mNetworkInterface = NetworkInterface.getByName(mInterfaceName);
        } catch (SocketException | NullPointerException e) {
            // TODO: throw new IllegalStateException.
            Log.e(mTag, "ALERT: Failed to get interface object: ", e);
        }
    }

    // This needs to be called with care to ensure that our LinkProperties
    // are in sync with the actual LinkProperties of the interface. For example,
    // we should only call this if we know for sure that there are no IP addresses
    // assigned to the interface, etc.
    private void resetLinkProperties() {
        mNetlinkTracker.clearLinkProperties();
        mConfiguration = null;
        mDhcpResults = null;
        mTcpBufferSizes = "";
        mHttpProxy = null;

        synchronized (mLock) {
            mLinkProperties = new LinkProperties();
            mLinkProperties.setInterfaceName(mInterfaceName);
        }
    }

    // For now: use WifiStateMachine's historical notion of provisioned.
    private static boolean isProvisioned(LinkProperties lp) {
        // For historical reasons, we should connect even if all we have is
        // an IPv4 address and nothing else.
        return lp.isProvisioned() || lp.hasIPv4Address();
    }

    // TODO: Investigate folding all this into the existing static function
    // LinkProperties.compareProvisioning() or some other single function that
    // takes two LinkProperties objects and returns a ProvisioningChange
    // object that is a correct and complete assessment of what changed, taking
    // account of the asymmetries described in the comments in this function.
    // Then switch to using it everywhere (IpReachabilityMonitor, etc.).
    private static ProvisioningChange compareProvisioning(
            LinkProperties oldLp, LinkProperties newLp) {
        ProvisioningChange delta;

        final boolean wasProvisioned = isProvisioned(oldLp);
        final boolean isProvisioned = isProvisioned(newLp);

        if (!wasProvisioned && isProvisioned) {
            delta = ProvisioningChange.GAINED_PROVISIONING;
        } else if (wasProvisioned && isProvisioned) {
            delta = ProvisioningChange.STILL_PROVISIONED;
        } else if (!wasProvisioned && !isProvisioned) {
            delta = ProvisioningChange.STILL_NOT_PROVISIONED;
        } else {
            // (wasProvisioned && !isProvisioned)
            //
            // Note that this is true even if we lose a configuration element
            // (e.g., a default gateway) that would not be required to advance
            // into provisioned state. This is intended: if we have a default
            // router and we lose it, that's a sure sign of a problem, but if
            // we connect to a network with no IPv4 DNS servers, we consider
            // that to be a network without DNS servers and connect anyway.
            //
            // See the comment below.
            delta = ProvisioningChange.LOST_PROVISIONING;
        }

        // Additionally:
        //
        // Partial configurations (e.g., only an IPv4 address with no DNS
        // servers and no default route) are accepted as long as DHCPv4
        // succeeds. On such a network, isProvisioned() will always return
        // false, because the configuration is not complete, but we want to
        // connect anyway. It might be a disconnected network such as a
        // Chromecast or a wireless printer, for example.
        //
        // Because on such a network isProvisioned() will always return false,
        // delta will never be LOST_PROVISIONING. So check for loss of
        // provisioning here too.
        if ((oldLp.hasIPv4Address() && !newLp.hasIPv4Address()) ||
                (oldLp.isIPv6Provisioned() && !newLp.isIPv6Provisioned())) {
            delta = ProvisioningChange.LOST_PROVISIONING;
        }

        return delta;
    }

    private void dispatchCallback(ProvisioningChange delta, LinkProperties newLp) {
        if (mApfFilter != null) mApfFilter.setLinkProperties(newLp);
        switch (delta) {
            case GAINED_PROVISIONING:
                if (VDBG) { Log.d(mTag, "onProvisioningSuccess()"); }
                mCallback.onProvisioningSuccess(newLp);
                break;

            case LOST_PROVISIONING:
                if (VDBG) { Log.d(mTag, "onProvisioningFailure()"); }
                mCallback.onProvisioningFailure(newLp);
                break;

            default:
                if (VDBG) { Log.d(mTag, "onLinkPropertiesChange()"); }
                mCallback.onLinkPropertiesChange(newLp);
                break;
        }
    }

    private ProvisioningChange setLinkProperties(LinkProperties newLp) {
        if (mIpReachabilityMonitor != null) {
            mIpReachabilityMonitor.updateLinkProperties(newLp);
        }

        ProvisioningChange delta;
        synchronized (mLock) {
            delta = compareProvisioning(mLinkProperties, newLp);
            mLinkProperties = new LinkProperties(newLp);
        }

        if (DBG) {
            switch (delta) {
                case GAINED_PROVISIONING:
                case LOST_PROVISIONING:
                    Log.d(mTag, "provisioning: " + delta);
                    break;
            }
        }

        return delta;
    }

    private boolean linkPropertiesUnchanged(LinkProperties newLp) {
        synchronized (mLock) {
            return Objects.equals(newLp, mLinkProperties);
        }
    }

    private LinkProperties assembleLinkProperties() {
        // [1] Create a new LinkProperties object to populate.
        LinkProperties newLp = new LinkProperties();
        newLp.setInterfaceName(mInterfaceName);

        // [2] Pull in data from netlink:
        //         - IPv4 addresses
        //         - IPv6 addresses
        //         - IPv6 routes
        //         - IPv6 DNS servers
        LinkProperties netlinkLinkProperties = mNetlinkTracker.getLinkProperties();
        newLp.setLinkAddresses(netlinkLinkProperties.getLinkAddresses());
        for (RouteInfo route : netlinkLinkProperties.getRoutes()) {
            newLp.addRoute(route);
        }
        for (InetAddress dns : netlinkLinkProperties.getDnsServers()) {
            // Only add likely reachable DNS servers.
            // TODO: investigate deleting this.
            if (newLp.isReachable(dns)) {
                newLp.addDnsServer(dns);
            }
        }

        // [3] Add in data from DHCPv4, if available.
        //
        // mDhcpResults is never shared with any other owner so we don't have
        // to worry about concurrent modification.
        if (mDhcpResults != null) {
            for (RouteInfo route : mDhcpResults.getRoutes(mInterfaceName)) {
                newLp.addRoute(route);
            }
            for (InetAddress dns : mDhcpResults.dnsServers) {
                // Only add likely reachable DNS servers.
                // TODO: investigate deleting this.
                if (newLp.isReachable(dns)) {
                    newLp.addDnsServer(dns);
                }
            }
            newLp.setDomains(mDhcpResults.domains);

            if (mDhcpResults.mtu != 0) {
                newLp.setMtu(mDhcpResults.mtu);
            }
        }

        // [4] Add in TCP buffer sizes and HTTP Proxy config, if available.
        if (!TextUtils.isEmpty(mTcpBufferSizes)) {
            newLp.setTcpBufferSizes(mTcpBufferSizes);
        }
        if (mHttpProxy != null) {
            newLp.setHttpProxy(mHttpProxy);
        }

        if (VDBG) {
            Log.d(mTag, "newLp{" + newLp + "}");
        }
        return newLp;
    }

    // Returns false if we have lost provisioning, true otherwise.
    private boolean handleLinkPropertiesUpdate(boolean sendCallbacks) {
        final LinkProperties newLp = assembleLinkProperties();
        if (linkPropertiesUnchanged(newLp)) {
            return true;
        }
        final ProvisioningChange delta = setLinkProperties(newLp);
        if (sendCallbacks) {
            dispatchCallback(delta, newLp);
        }
        return (delta != ProvisioningChange.LOST_PROVISIONING);
    }

    private void clearIPv4Address() {
        try {
            final InterfaceConfiguration ifcg = new InterfaceConfiguration();
            ifcg.setLinkAddress(new LinkAddress("0.0.0.0/0"));
            mNwService.setInterfaceConfig(mInterfaceName, ifcg);
        } catch (RemoteException e) {
            Log.e(mTag, "ALERT: Failed to clear IPv4 address on interface " + mInterfaceName, e);
        }
    }

    private void handleIPv4Success(DhcpResults dhcpResults) {
        mDhcpResults = new DhcpResults(dhcpResults);
        final LinkProperties newLp = assembleLinkProperties();
        final ProvisioningChange delta = setLinkProperties(newLp);

        if (VDBG) {
            Log.d(mTag, "onNewDhcpResults(" + Objects.toString(dhcpResults) + ")");
        }
        mCallback.onNewDhcpResults(dhcpResults);

        dispatchCallback(delta, newLp);
    }

    private void handleIPv4Failure() {
        // TODO: Figure out to de-dup this and the same code in DhcpClient.
        clearIPv4Address();
        mDhcpResults = null;
        final LinkProperties newLp = assembleLinkProperties();
        ProvisioningChange delta = setLinkProperties(newLp);
        // If we've gotten here and we're still not provisioned treat that as
        // a total loss of provisioning.
        //
        // Either (a) static IP configuration failed or (b) DHCPv4 failed AND
        // there was no usable IPv6 obtained before the DHCPv4 timeout.
        //
        // Regardless: GAME OVER.
        //
        // TODO: Make the DHCP client not time out and just continue in
        // exponential backoff. Callers such as Wi-Fi which need a timeout
        // should implement it themselves.
        if (delta == ProvisioningChange.STILL_NOT_PROVISIONED) {
            delta = ProvisioningChange.LOST_PROVISIONING;
        }

        if (VDBG) { Log.d(mTag, "onNewDhcpResults(null)"); }
        mCallback.onNewDhcpResults(null);

        dispatchCallback(delta, newLp);
        if (delta == ProvisioningChange.LOST_PROVISIONING) {
            transitionTo(mStoppingState);
        }
    }

    class StoppedState extends State {
        @Override
        public void enter() {
            try {
                mNwService.disableIpv6(mInterfaceName);
                mNwService.clearInterfaceAddresses(mInterfaceName);
            } catch (Exception e) {
                Log.e(mTag, "Failed to clear addresses or disable IPv6" + e);
            }

            resetLinkProperties();
        }

        @Override
        public boolean processMessage(Message msg) {
            switch (msg.what) {
                case CMD_STOP:
                    break;

                case CMD_START:
                    mConfiguration = (ProvisioningConfiguration) msg.obj;
                    transitionTo(mStartedState);
                    break;

                case EVENT_NETLINK_LINKPROPERTIES_CHANGED:
                    handleLinkPropertiesUpdate(NO_CALLBACKS);
                    break;

                case CMD_UPDATE_TCP_BUFFER_SIZES:
                    mTcpBufferSizes = (String) msg.obj;
                    handleLinkPropertiesUpdate(NO_CALLBACKS);
                    break;

                case CMD_UPDATE_HTTP_PROXY:
                    mHttpProxy = (ProxyInfo) msg.obj;
                    handleLinkPropertiesUpdate(NO_CALLBACKS);
                    break;

                case CMD_SET_MULTICAST_FILTER:
                    mMulticastFiltering = (boolean) msg.obj;
                    break;

                case DhcpClient.CMD_ON_QUIT:
                    // Everything is already stopped.
                    Log.e(mTag, "Unexpected CMD_ON_QUIT (already stopped).");
                    break;

                default:
                    return NOT_HANDLED;
            }
            return HANDLED;
        }
    }

    class StoppingState extends State {
        @Override
        public void enter() {
            if (mDhcpClient == null) {
                // There's no DHCPv4 for which to wait; proceed to stopped.
                transitionTo(mStoppedState);
            }
        }

        @Override
        public boolean processMessage(Message msg) {
            switch (msg.what) {
                case DhcpClient.CMD_ON_QUIT:
                    mDhcpClient = null;
                    transitionTo(mStoppedState);
                    break;

                default:
                    deferMessage(msg);
            }
            return HANDLED;
        }
    }

    class StartedState extends State {
        @Override
        public void enter() {
            mApfFilter = ApfFilter.maybeCreate(mConfiguration.mApfCapabilities, mNetworkInterface,
                    mCallback, mMulticastFiltering);
            // TODO: investigate the effects of any multicast filtering racing/interfering with the
            // rest of this IP configuration startup.
            if (mApfFilter == null) mCallback.setFallbackMulticastFilter(mMulticastFiltering);
            // Set privacy extensions.
            try {
                mNwService.setInterfaceIpv6PrivacyExtensions(mInterfaceName, true);
                mNwService.enableIpv6(mInterfaceName);
                // TODO: Perhaps clearIPv4Address() as well.
            } catch (RemoteException re) {
                Log.e(mTag, "Unable to change interface settings: " + re);
            } catch (IllegalStateException ie) {
                Log.e(mTag, "Unable to change interface settings: " + ie);
            }

            if (mConfiguration.mUsingIpReachabilityMonitor) {
                mIpReachabilityMonitor = new IpReachabilityMonitor(
                        mContext,
                        mInterfaceName,
                        new IpReachabilityMonitor.Callback() {
                            @Override
                            public void notifyLost(InetAddress ip, String logMsg) {
                                mCallback.onReachabilityLost(logMsg);
                            }
                        });
            }

            // If we have a StaticIpConfiguration attempt to apply it and
            // handle the result accordingly.
            if (mConfiguration.mStaticIpConfig != null) {
                if (applyStaticIpConfig()) {
                    handleIPv4Success(new DhcpResults(mConfiguration.mStaticIpConfig));
                } else {
                    if (VDBG) { Log.d(mTag, "onProvisioningFailure()"); }
                    mCallback.onProvisioningFailure(getLinkProperties());
                    transitionTo(mStoppingState);
                }
            } else {
                // Start DHCPv4.
                mDhcpClient = DhcpClient.makeDhcpClient(
                        mContext,
                        IpManager.this,
                        mInterfaceName);
                mDhcpClient.registerForPreDhcpNotification();
                mDhcpClient.sendMessage(DhcpClient.CMD_START_DHCP);
            }
        }

        @Override
        public void exit() {
            if (mIpReachabilityMonitor != null) {
                mIpReachabilityMonitor.stop();
                mIpReachabilityMonitor = null;
            }

            if (mDhcpClient != null) {
                mDhcpClient.sendMessage(DhcpClient.CMD_STOP_DHCP);
                mDhcpClient.doQuit();
            }

            if (mApfFilter != null) {
                mApfFilter.shutdown();
                mApfFilter = null;
            }

            resetLinkProperties();
        }

        @Override
        public boolean processMessage(Message msg) {
            switch (msg.what) {
                case CMD_STOP:
                    transitionTo(mStoppedState);
                    break;

                case CMD_START:
                    Log.e(mTag, "ALERT: START received in StartedState. Please fix caller.");
                    break;

                case CMD_CONFIRM:
                    // TODO: Possibly introduce a second type of confirmation
                    // that both probes (a) on-link neighbors and (b) does
                    // a DHCPv4 RENEW.  We used to do this on Wi-Fi framework
                    // roams.
                    if (mIpReachabilityMonitor != null) {
                        mIpReachabilityMonitor.probeAll();
                    }
                    break;

                case EVENT_PRE_DHCP_ACTION_COMPLETE:
                    // It's possible to reach here if, for example, someone
                    // calls completedPreDhcpAction() after provisioning with
                    // a static IP configuration.
                    if (mDhcpClient != null) {
                        mDhcpClient.sendMessage(DhcpClient.CMD_PRE_DHCP_ACTION_COMPLETE);
                    }
                    break;

                case EVENT_NETLINK_LINKPROPERTIES_CHANGED:
                    if (!handleLinkPropertiesUpdate(SEND_CALLBACKS)) {
                        transitionTo(mStoppedState);
                    }
                    break;

                case CMD_UPDATE_TCP_BUFFER_SIZES:
                    mTcpBufferSizes = (String) msg.obj;
                    // This cannot possibly change provisioning state.
                    handleLinkPropertiesUpdate(SEND_CALLBACKS);
                    break;

                case CMD_UPDATE_HTTP_PROXY:
                    mHttpProxy = (ProxyInfo) msg.obj;
                    // This cannot possibly change provisioning state.
                    handleLinkPropertiesUpdate(SEND_CALLBACKS);
                    break;

                case CMD_SET_MULTICAST_FILTER: {
                    mMulticastFiltering = (boolean) msg.obj;
                    if (mApfFilter != null) {
                        mApfFilter.setMulticastFilter(mMulticastFiltering);
                    } else {
                        mCallback.setFallbackMulticastFilter(mMulticastFiltering);
                    }
                    break;
                }

                case DhcpClient.CMD_PRE_DHCP_ACTION:
                    if (VDBG) { Log.d(mTag, "onPreDhcpAction()"); }
                    if (mConfiguration.mRequestedPreDhcpAction) {
                        mCallback.onPreDhcpAction();
                    } else {
                        sendMessage(EVENT_PRE_DHCP_ACTION_COMPLETE);
                    }
                    break;

                case DhcpClient.CMD_POST_DHCP_ACTION: {
                    // Note that onPostDhcpAction() is likely to be
                    // asynchronous, and thus there is no guarantee that we
                    // will be able to observe any of its effects here.
                    if (VDBG) { Log.d(mTag, "onPostDhcpAction()"); }
                    mCallback.onPostDhcpAction();

                    final DhcpResults dhcpResults = (DhcpResults) msg.obj;
                    switch (msg.arg1) {
                        case DhcpClient.DHCP_SUCCESS:
                            handleIPv4Success(dhcpResults);
                            break;
                        case DhcpClient.DHCP_FAILURE:
                            handleIPv4Failure();
                            break;
                        default:
                            Log.e(mTag, "Unknown CMD_POST_DHCP_ACTION status:" + msg.arg1);
                    }
                    break;
                }

                case DhcpClient.CMD_ON_QUIT:
                    // DHCPv4 quit early for some reason.
                    Log.e(mTag, "Unexpected CMD_ON_QUIT.");
                    mDhcpClient = null;
                    break;

                default:
                    return NOT_HANDLED;
            }
            return HANDLED;
        }

        private boolean applyStaticIpConfig() {
            final InterfaceConfiguration ifcg = new InterfaceConfiguration();
            ifcg.setLinkAddress(mConfiguration.mStaticIpConfig.ipAddress);
            ifcg.setInterfaceUp();
            try {
                mNwService.setInterfaceConfig(mInterfaceName, ifcg);
                if (DBG) Log.d(mTag, "Static IP configuration succeeded");
            } catch (IllegalStateException | RemoteException e) {
                Log.e(mTag, "Static IP configuration failed: ", e);
                return false;
            }

            return true;
        }
    }
}
