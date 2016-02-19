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
import android.net.DhcpResults;
import android.net.InterfaceConfiguration;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.LinkProperties.ProvisioningChange;
import android.net.RouteInfo;
import android.net.StaticIpConfiguration;
import android.net.dhcp.DhcpClient;
import android.os.INetworkManagementService;
import android.os.Message;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.util.Log;
import android.util.SparseArray;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.State;
import com.android.internal.util.StateMachine;
import com.android.server.net.NetlinkTracker;

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
    private static final boolean DBG = true;
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

            public ProvisioningConfiguration build() {
                return new ProvisioningConfiguration(mConfig);
            }
        }

        /* package */ boolean mUsingIpReachabilityMonitor = true;
        /* package */ boolean mRequestedPreDhcpAction;
        /* package */ StaticIpConfiguration mStaticIpConfig;

        public ProvisioningConfiguration() {}

        public ProvisioningConfiguration(ProvisioningConfiguration other) {
            mUsingIpReachabilityMonitor = other.mUsingIpReachabilityMonitor;
            mRequestedPreDhcpAction = other.mRequestedPreDhcpAction;
            mStaticIpConfig = other.mStaticIpConfig;
        }
    }

    private static final int CMD_STOP = 1;
    private static final int CMD_START = 2;
    private static final int CMD_CONFIRM = 3;
    private static final int EVENT_PRE_DHCP_ACTION_COMPLETE = 4;
    // Sent by NetlinkTracker to communicate netlink events.
    private static final int EVENT_NETLINK_LINKPROPERTIES_CHANGED = 5;

    private static final int MAX_LOG_RECORDS = 1000;

    private final Object mLock = new Object();
    private final State mStoppedState = new StoppedState();
    private final State mStoppingState = new StoppingState();
    private final State mStartedState = new StartedState();

    private final String mTag;
    private final Context mContext;
    private final String mInterfaceName;
    @VisibleForTesting
    protected final Callback mCallback;
    private final INetworkManagementService mNwService;
    private final NetlinkTracker mNetlinkTracker;

    private int mInterfaceIndex;

    /**
     * Non-final member variables accessed only from within our StateMachine.
     */
    private IpReachabilityMonitor mIpReachabilityMonitor;
    private DhcpClient mDhcpClient;
    private DhcpResults mDhcpResults;
    private ProvisioningConfiguration mConfiguration;

    /**
     * Member variables accessed both from within the StateMachine thread
     * and via accessors from other threads.
     */
    @GuardedBy("mLock")
    private LinkProperties mLinkProperties;

    public IpManager(Context context, String ifName, Callback callback)
                throws IllegalArgumentException {
        super(IpManager.class.getSimpleName() + "." + ifName);
        mTag = getName();

        mContext = context;
        mInterfaceName = ifName;
        mCallback = callback;

        mNwService = INetworkManagementService.Stub.asInterface(
                ServiceManager.getService(Context.NETWORKMANAGEMENT_SERVICE));

        mNetlinkTracker = new NetlinkTracker(
                mInterfaceName,
                new NetlinkTracker.Callback() {
                    @Override
                    public void update() {
                        sendMessage(EVENT_NETLINK_LINKPROPERTIES_CHANGED);
                    }
                });
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

    /**
     * A special constructor for use in testing that bypasses some of the more
     * complicated setup bits.
     *
     * TODO: Figure out how to delete this yet preserve testability.
     */
    @VisibleForTesting
    protected IpManager(String ifName, Callback callback) {
        super(IpManager.class.getSimpleName() + ".test-" + ifName);
        mTag = getName();

        mInterfaceName = ifName;
        mCallback = callback;

        mContext = null;
        mNwService = null;
        mNetlinkTracker = null;
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
        getInterfaceIndex();
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

    public LinkProperties getLinkProperties() {
        synchronized (mLock) {
            return new LinkProperties(mLinkProperties);
        }
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
                mInterfaceName, mInterfaceIndex,
                msg.arg1, msg.arg2, Objects.toString(msg.obj));
        if (VDBG) {
            Log.d(mTag, getWhatToString(msg.what) + " " + logLine);
        }
        return logLine;
    }

    private void getInterfaceIndex() {
        try {
            mInterfaceIndex = NetworkInterface.getByName(mInterfaceName).getIndex();
        } catch (SocketException | NullPointerException e) {
            // TODO: throw new IllegalStateException.
            Log.e(mTag, "ALERT: Failed to get interface index: ", e);
        }
    }

    // This needs to be called with care to ensure that our LinkProperties
    // are in sync with the actual LinkProperties of the interface. For example,
    // we should only call this if we know for sure that there are no IP addresses
    // assigned to the interface, etc.
    private void resetLinkProperties() {
        mNetlinkTracker.clearLinkProperties();
        mDhcpResults = null;
        mConfiguration = null;

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
        }

        if (VDBG) {
            Log.d(mTag, "newLp{" + newLp + "}");
        }

        return newLp;
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
                    setLinkProperties(assembleLinkProperties());
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

                case EVENT_NETLINK_LINKPROPERTIES_CHANGED: {
                    final LinkProperties newLp = assembleLinkProperties();
                    if (linkPropertiesUnchanged(newLp)) {
                        break;
                    }
                    final ProvisioningChange delta = setLinkProperties(newLp);
                    dispatchCallback(delta, newLp);
                    if (delta == ProvisioningChange.LOST_PROVISIONING) {
                        transitionTo(mStoppedState);
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
