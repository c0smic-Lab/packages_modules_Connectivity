/*
 * Copyright (C) 2012 The Android Open Source Project
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

package com.android.server.connectivity;

import static android.net.NetworkCapabilities.TRANSPORT_CELLULAR;
import static android.net.NetworkCapabilities.TRANSPORT_TEST;

import static com.android.net.module.util.CollectionUtils.contains;
import static com.android.net.module.util.HandlerUtils.ensureRunningOnHandlerThread;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.net.ConnectivityManager;
import android.net.IDnsResolver;
import android.net.INetd;
import android.net.InetAddresses;
import android.net.InterfaceConfigurationParcel;
import android.net.IpPrefix;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.NetworkInfo;
import android.net.RouteInfo;
import android.os.RemoteException;
import android.os.ServiceSpecificException;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.IndentingPrintWriter;
import com.android.modules.utils.build.SdkLevel;
import com.android.net.module.util.NetworkStackConstants;
import com.android.server.ConnectivityService;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.UnknownHostException;
import java.util.Objects;

/**
 * Class to manage a 464xlat CLAT daemon. Nat464Xlat is not thread safe and should be manipulated
 * from a consistent and unique thread context. It is the responsibility of ConnectivityService to
 * call into this class from its own Handler thread.
 *
 * @hide
 */
public class Nat464Xlat {
    private static final String TAG = Nat464Xlat.class.getSimpleName();

    // This must match the interface prefix in clatd.c.
    private static final String CLAT_PREFIX = "v4-";

    // The network types on which we will start clatd,
    // allowing clat only on networks for which we can support IPv6-only.
    private static final int[] NETWORK_TYPES = {
        ConnectivityManager.TYPE_MOBILE,
        ConnectivityManager.TYPE_WIFI,
        ConnectivityManager.TYPE_ETHERNET,
    };

    // The network states in which running clatd is supported.
    private static final NetworkInfo.State[] NETWORK_STATES = {
        NetworkInfo.State.CONNECTED,
        NetworkInfo.State.SUSPENDED,
    };

    private final IDnsResolver mDnsResolver;
    private final INetd mNetd;

    // The network we're running on, and its type.
    private final NetworkAgentInfo mNetwork;

    private enum State {
        IDLE,         // start() not called. Base iface and stacked iface names are null.
        DISCOVERING,  // same as IDLE, except prefix discovery in progress.
        STARTING,     // start() called. Base iface and stacked iface names are known.
        RUNNING,      // start() called, and the stacked iface is known to be up.
    }

    /**
     * NAT64 prefix currently in use. Only valid in STARTING or RUNNING states.
     * Used, among other things, to avoid updates when switching from a prefix learned from one
     * source (e.g., RA) to the same prefix learned from another source (e.g., RA).
     */
    private IpPrefix mNat64PrefixInUse;
    /** NAT64 prefix (if any) discovered from DNS via RFC 7050. */
    private IpPrefix mNat64PrefixFromDns;
    /** NAT64 prefix (if any) learned from the network via RA. */
    private IpPrefix mNat64PrefixFromRa;
    private String mBaseIface;
    private String mIface;
    @VisibleForTesting
    Inet6Address mIPv6Address;
    private State mState = State.IDLE;
    private final ClatCoordinator mClatCoordinator;  // non-null iff T+

    private final boolean mEnableClatOnCellular;
    private boolean mPrefixDiscoveryRunning;

    public Nat464Xlat(NetworkAgentInfo nai, INetd netd, IDnsResolver dnsResolver,
            ConnectivityService.Dependencies deps) {
        mDnsResolver = dnsResolver;
        mNetd = netd;
        mNetwork = nai;
        mEnableClatOnCellular = deps.getCellular464XlatEnabled();
        if (SdkLevel.isAtLeastT()) {
            mClatCoordinator = deps.getClatCoordinator(mNetd);
        } else {
            mClatCoordinator = null;
        }
    }

    /**
     * Whether to attempt 464xlat on this network. This is true for an IPv6-only network that is
     * currently connected and where the NetworkAgent has not disabled 464xlat. It is the signal to
     * enable NAT64 prefix discovery.
     *
     * @param nai the NetworkAgentInfo corresponding to the network.
     * @return true if the network requires clat, false otherwise.
     */
    @VisibleForTesting
    protected boolean requiresClat(NetworkAgentInfo nai) {
        // TODO: migrate to NetworkCapabilities.TRANSPORT_*.
        final boolean supported = contains(NETWORK_TYPES, nai.networkInfo.getType());
        final boolean connected = contains(NETWORK_STATES, nai.networkInfo.getState());

        // Allow to run clat on test network.
        // TODO: merge to boolean "supported" once boolean "supported" is migrated to
        // NetworkCapabilities.TRANSPORT_*.
        final boolean isTestNetwork = nai.networkCapabilities.hasTransport(TRANSPORT_TEST);

        // Only run clat on networks that have a global IPv6 address and don't have a native IPv4
        // address.
        LinkProperties lp = nai.linkProperties;
        final boolean isIpv6OnlyNetwork = (lp != null) && lp.hasGlobalIpv6Address()
                && !lp.hasIpv4Address();

        // If the network tells us it doesn't use clat, respect that.
        final boolean skip464xlat = (nai.netAgentConfig() != null)
                && nai.netAgentConfig().skip464xlat;

        return (supported || isTestNetwork) && connected && isIpv6OnlyNetwork && !skip464xlat
                && !nai.isDestroyed() && (nai.networkCapabilities.hasTransport(TRANSPORT_CELLULAR)
                ? isCellular464XlatEnabled() : true);
    }

    /**
     * Whether the clat demon should be started on this network now. This is true if requiresClat is
     * true and a NAT64 prefix has been discovered.
     *
     * @param nai the NetworkAgentInfo corresponding to the network.
     * @return true if the network should start clat, false otherwise.
     */
    @VisibleForTesting
    protected boolean shouldStartClat(NetworkAgentInfo nai) {
        LinkProperties lp = nai.linkProperties;
        return requiresClat(nai) && lp != null && lp.getNat64Prefix() != null;
    }

    /**
     * @return true if clatd has been started and has not yet stopped.
     * A true result corresponds to internal states STARTING and RUNNING.
     */
    public boolean isStarted() {
        return (mState == State.STARTING || mState == State.RUNNING);
    }

    /**
     * @return true if clatd has been started but the stacked interface is not yet up.
     */
    public boolean isStarting() {
        return mState == State.STARTING;
    }

    /**
     * @return true if clatd has been started and the stacked interface is up.
     */
    public boolean isRunning() {
        return mState == State.RUNNING;
    }

    /**
     * Start clatd, register this Nat464Xlat as a network observer for the stacked interface,
     * and set internal state.
     */
    private void enterStartingState(String baseIface) {
        mNat64PrefixInUse = selectNat64Prefix();
        String addrStr = null;
        if (SdkLevel.isAtLeastT()) {
            try {
                addrStr = mClatCoordinator.clatStart(baseIface, getNetId(), mNat64PrefixInUse);
            } catch (IOException e) {
                Log.e(TAG, "Error starting clatd on " + baseIface, e);
            }
        } else {
            try {
                addrStr = mNetd.clatdStart(baseIface, mNat64PrefixInUse.toString());
            } catch (RemoteException | ServiceSpecificException e) {
                Log.e(TAG, "Error starting clatd on " + baseIface, e);
            }
        }
        mIface = CLAT_PREFIX + baseIface;
        mBaseIface = baseIface;
        mState = State.STARTING;
        try {
            mIPv6Address = (Inet6Address) InetAddresses.parseNumericAddress(addrStr);
        } catch (ClassCastException | IllegalArgumentException | NullPointerException e) {
            Log.e(TAG, "Invalid IPv6 address " + addrStr , e);
        }
        if (mPrefixDiscoveryRunning && !isPrefixDiscoveryNeeded()) {
            stopPrefixDiscovery();
        }
        if (!mPrefixDiscoveryRunning) {
            setPrefix64(mNat64PrefixInUse);
        }
    }

    /**
     * Enter running state just after getting confirmation that the stacked interface is up, and
     * turn ND offload off if on WiFi.
     */
    private void enterRunningState() {
        mState = State.RUNNING;
    }

    /**
     * Unregister as a base observer for the stacked interface, and clear internal state.
     */
    private void leaveStartedState() {
        mNat64PrefixInUse = null;
        mIface = null;
        mBaseIface = null;
        mIPv6Address = null;

        if (!mPrefixDiscoveryRunning) {
            setPrefix64(null);
        }

        if (isPrefixDiscoveryNeeded()) {
            if (!mPrefixDiscoveryRunning) {
                startPrefixDiscovery();
            }
            mState = State.DISCOVERING;
        } else {
            stopPrefixDiscovery();
            mState = State.IDLE;
        }
    }

    @VisibleForTesting
    protected void start() {
        if (isStarted()) {
            Log.e(TAG, "startClat: already started");
            return;
        }

        String baseIface = mNetwork.linkProperties.getInterfaceName();
        if (baseIface == null) {
            Log.e(TAG, "startClat: Can't start clat on null interface");
            return;
        }
        // TODO: should we only do this if mNetd.clatdStart() succeeds?
        Log.i(TAG, "Starting clatd on " + baseIface);
        enterStartingState(baseIface);
    }

    @VisibleForTesting
    protected void stop() {
        if (!isStarted()) {
            Log.e(TAG, "stopClat: already stopped");
            return;
        }

        Log.i(TAG, "Stopping clatd on " + mBaseIface);
        if (SdkLevel.isAtLeastT()) {
            try {
                mClatCoordinator.clatStop();
            } catch (IOException e) {
                Log.e(TAG, "Error stopping clatd on " + mBaseIface + ": " + e);
            }
        } else {
            try {
                mNetd.clatdStop(mBaseIface);
            } catch (RemoteException | ServiceSpecificException e) {
                Log.e(TAG, "Error stopping clatd on " + mBaseIface + ": " + e);
            }
        }

        String iface = mIface;
        boolean wasRunning = isRunning();

        // Change state before updating LinkProperties. handleUpdateLinkProperties ends up calling
        // fixupLinkProperties, and if at that time the state is still RUNNING, fixupLinkProperties
        // would wrongly inform ConnectivityService that there is still a stacked interface.
        leaveStartedState();

        if (wasRunning) {
            LinkProperties lp = new LinkProperties(mNetwork.linkProperties);
            lp.removeStackedLink(iface);
            mNetwork.connService().handleUpdateLinkProperties(mNetwork, lp);
        }
    }

    private void startPrefixDiscovery() {
        try {
            mDnsResolver.startPrefix64Discovery(getNetId());
        } catch (RemoteException | ServiceSpecificException e) {
            Log.e(TAG, "Error starting prefix discovery on netId " + getNetId() + ": " + e);
        }
        mPrefixDiscoveryRunning = true;
    }

    private void stopPrefixDiscovery() {
        try {
            mDnsResolver.stopPrefix64Discovery(getNetId());
        } catch (RemoteException | ServiceSpecificException e) {
            Log.e(TAG, "Error stopping prefix discovery on netId " + getNetId() + ": " + e);
        }
        mPrefixDiscoveryRunning = false;
    }

    private boolean isPrefixDiscoveryNeeded() {
        // If there is no NAT64 prefix in the RA, prefix discovery is always needed. It cannot be
        // stopped after it succeeds, because stopping it will cause netd to report that the prefix
        // has been removed, and that will cause us to stop clatd.
        return requiresClat(mNetwork) && mNat64PrefixFromRa == null;
    }

    private void setPrefix64(IpPrefix prefix) {
        final String prefixString = (prefix != null) ? prefix.toString() : "";
        try {
            mDnsResolver.setPrefix64(getNetId(), prefixString);
        } catch (RemoteException | ServiceSpecificException e) {
            Log.e(TAG, "Error setting NAT64 prefix on netId " + getNetId() + " to "
                    + prefix + ": " + e);
        }
    }

    private void maybeHandleNat64PrefixChange() {
        final IpPrefix newPrefix = selectNat64Prefix();
        if (!Objects.equals(mNat64PrefixInUse, newPrefix)) {
            Log.d(TAG, "NAT64 prefix changed from " + mNat64PrefixInUse + " to "
                    + newPrefix);
            stop();
            // It's safe to call update here, even though this method is called from update, because
            // stop() is guaranteed to have moved out of STARTING and RUNNING, which are the only
            // states in which this method can be called.
            update();
        }
    }

    /**
     * Starts/stops NAT64 prefix discovery and clatd as necessary.
     */
    public void update() {
        // TODO: turn this class into a proper StateMachine. http://b/126113090
        switch (mState) {
            case IDLE:
                if (isPrefixDiscoveryNeeded()) {
                    startPrefixDiscovery();  // Enters DISCOVERING state.
                    mState = State.DISCOVERING;
                } else if (requiresClat(mNetwork)) {
                    start();  // Enters STARTING state.
                }
                break;

            case DISCOVERING:
                if (shouldStartClat(mNetwork)) {
                    // NAT64 prefix detected. Start clatd.
                    start();  // Enters STARTING state.
                    return;
                }
                if (!requiresClat(mNetwork)) {
                    // IPv4 address added. Go back to IDLE state.
                    stopPrefixDiscovery();
                    mState = State.IDLE;
                    return;
                }
                break;

            case STARTING:
            case RUNNING:
                // NAT64 prefix removed, or IPv4 address added.
                // Stop clatd and go back into DISCOVERING or idle.
                if (!shouldStartClat(mNetwork)) {
                    stop();
                    break;
                }
                // Only necessary while clat is actually started.
                maybeHandleNat64PrefixChange();
                break;
        }
    }

    /**
     * Picks a NAT64 prefix to use. Always prefers the prefix from the RA if one is received from
     * both RA and DNS, because the prefix in the RA has better security and updatability, and will
     * almost always be received first anyway.
     *
     * Any network that supports legacy hosts will support discovering the DNS64 prefix via DNS as
     * well. If the prefix from the RA is withdrawn, fall back to that for reliability purposes.
     */
    private IpPrefix selectNat64Prefix() {
        return mNat64PrefixFromRa != null ? mNat64PrefixFromRa : mNat64PrefixFromDns;
    }

    public void setNat64PrefixFromRa(IpPrefix prefix) {
        mNat64PrefixFromRa = prefix;
    }

    public void setNat64PrefixFromDns(IpPrefix prefix) {
        mNat64PrefixFromDns = prefix;
    }

    /**
     * Copies the stacked clat link in oldLp, if any, to the passed LinkProperties.
     * This is necessary because the LinkProperties in mNetwork come from the transport layer, which
     * has no idea that 464xlat is running on top of it.
     */
    public void fixupLinkProperties(@Nullable LinkProperties oldLp, @NonNull LinkProperties lp) {
        // This must be done even if clatd is not running, because otherwise shouldStartClat would
        // never return true.
        lp.setNat64Prefix(selectNat64Prefix());

        if (!isRunning()) {
            return;
        }
        if (lp.getAllInterfaceNames().contains(mIface)) {
            return;
        }

        Log.d(TAG, "clatd running, updating NAI for " + mIface);
        // oldLp can't be null here since shouldStartClat checks null LinkProperties to start clat.
        // Thus, the status won't pass isRunning check if the oldLp is null.
        for (LinkProperties stacked: oldLp.getStackedLinks()) {
            if (Objects.equals(mIface, stacked.getInterfaceName())) {
                lp.addStackedLink(stacked);
                return;
            }
        }
    }

    private LinkProperties makeLinkProperties(LinkAddress clatAddress) {
        LinkProperties stacked = new LinkProperties();
        stacked.setInterfaceName(mIface);

        // Although the clat interface is a point-to-point tunnel, we don't
        // point the route directly at the interface because some apps don't
        // understand routes without gateways (see, e.g., http://b/9597256
        // http://b/9597516). Instead, set the next hop of the route to the
        // clat IPv4 address itself (for those apps, it doesn't matter what
        // the IP of the gateway is, only that there is one).
        RouteInfo ipv4Default = new RouteInfo(
                new LinkAddress(NetworkStackConstants.IPV4_ADDR_ANY, 0),
                clatAddress.getAddress(), mIface);
        stacked.addRoute(ipv4Default);
        stacked.addLinkAddress(clatAddress);
        return stacked;
    }

    private LinkAddress getLinkAddress(String iface) {
        try {
            final InterfaceConfigurationParcel config = mNetd.interfaceGetCfg(iface);
            return new LinkAddress(
                    InetAddresses.parseNumericAddress(config.ipv4Addr), config.prefixLength);
        } catch (IllegalArgumentException | RemoteException | ServiceSpecificException e) {
            Log.e(TAG, "Error getting link properties: " + e);
            return null;
        }
    }

    /**
     * Adds stacked link on base link and transitions to RUNNING state.
     * Must be called on the handler thread.
     */
    public void handleInterfaceLinkStateChanged(String iface, boolean up) {
        // TODO: if we call start(), then stop(), then start() again, and the
        // interfaceLinkStateChanged notification for the first start is delayed past the first
        // stop, then the code becomes out of sync with system state and will behave incorrectly.
        //
        // This is not trivial to fix because:
        // 1. It is not guaranteed that start() will eventually result in the interface coming up,
        //    because there could be an error starting clat (e.g., if the interface goes down before
        //    the packet socket can be bound).
        // 2. If start is called multiple times, there is nothing in the interfaceLinkStateChanged
        //    notification that says which start() call the interface was created by.
        //
        // Once this code is converted to StateMachine, it will be possible to use deferMessage to
        // ensure it stays in STARTING state until the interfaceLinkStateChanged notification fires,
        // and possibly use a timeout (or provide some guarantees at the lower layer) to address #1.
        ensureRunningOnHandlerThread(mNetwork.handler());
        if (!isStarting() || !up || !Objects.equals(mIface, iface)) {
            return;
        }

        LinkAddress clatAddress = getLinkAddress(iface);
        if (clatAddress == null) {
            Log.e(TAG, "clatAddress was null for stacked iface " + iface);
            return;
        }

        Log.i(TAG, String.format("interface %s is up, adding stacked link %s on top of %s",
                mIface, mIface, mBaseIface));
        enterRunningState();
        LinkProperties lp = new LinkProperties(mNetwork.linkProperties);
        lp.addStackedLink(makeLinkProperties(clatAddress));
        mNetwork.connService().handleUpdateLinkProperties(mNetwork, lp);
    }

    /**
     * Removes stacked link on base link and transitions to IDLE state.
     * Must be called on the handler thread.
     */
    public void handleInterfaceRemoved(String iface) {
        ensureRunningOnHandlerThread(mNetwork.handler());
        if (!Objects.equals(mIface, iface)) {
            return;
        }
        if (!isRunning()) {
            return;
        }

        Log.i(TAG, "interface " + iface + " removed");
        // If we're running, and the interface was removed, then we didn't call stop(), and it's
        // likely that clatd crashed. Ensure we call stop() so we can start clatd again. Calling
        // stop() will also update LinkProperties, and if clatd crashed, the LinkProperties update
        // will cause ConnectivityService to call start() again.
        stop();
    }

    /**
     * Translate the input v4 address to v6 clat address.
     */
    @Nullable
    public Inet6Address translateV4toV6(@NonNull Inet4Address addr) {
        // Variables in Nat464Xlat should only be accessed from handler thread.
        ensureRunningOnHandlerThread(mNetwork.handler());
        if (!isStarted()) return null;

        return convertv4ToClatv6(mNat64PrefixInUse, addr);
    }

    @Nullable
    private static Inet6Address convertv4ToClatv6(
            @NonNull IpPrefix prefix, @NonNull Inet4Address addr) {
        final byte[] v6Addr = new byte[16];
        // Generate a v6 address from Nat64 prefix. Prefix should be 12 bytes long.
        System.arraycopy(prefix.getAddress().getAddress(), 0, v6Addr, 0, 12);
        System.arraycopy(addr.getAddress(), 0, v6Addr, 12, 4);

        try {
            return (Inet6Address) Inet6Address.getByAddress(v6Addr);
        } catch (UnknownHostException e) {
            Log.wtf(TAG, "getByAddress should never throw for a numeric address", e);
            return null;
        }
    }

    /**
     * Get the generated v6 address of clat.
     */
    @Nullable
    public Inet6Address getClatv6SrcAddress() {
        // Variables in Nat464Xlat should only be accessed from handler thread.
        ensureRunningOnHandlerThread(mNetwork.handler());

        return mIPv6Address;
    }

    /**
     * Get the generated v4 address of clat.
     */
    @Nullable
    public Inet4Address getClatv4SrcAddress() {
        // Variables in Nat464Xlat should only be accessed from handler thread.
        ensureRunningOnHandlerThread(mNetwork.handler());
        if (!isStarted()) return null;

        final LinkAddress v4Addr = getLinkAddress(mIface);
        if (v4Addr == null) return null;

        return (Inet4Address) v4Addr.getAddress();
    }

    /**
     * Dump the NAT64 xlat information.
     *
     * @param pw print writer.
     */
    public void dump(IndentingPrintWriter pw) {
        if (SdkLevel.isAtLeastT()) {
            // Dump ClatCoordinator information while clatd has been started but not running. The
            // reason is that it helps to have more information if clatd is started but the
            // v4-* interface doesn't bring up. See #isStarted, #isRunning.
            if (isStarted()) {
                pw.println("ClatCoordinator:");
                pw.increaseIndent();
                mClatCoordinator.dump(pw);
                pw.decreaseIndent();
            } else {
                pw.println("<not started>");
            }
        }
    }

    /**
     * Dump the raw BPF maps in 464XLAT
     *
     * @param pw print writer.
     * @param isEgress4Map whether to dump the egress4 map (true) or the ingress6 map (false).
     */
    public void dumpRawBpfMap(IndentingPrintWriter pw, boolean isEgress4Map) {
        if (SdkLevel.isAtLeastT()) {
            mClatCoordinator.dumpRawMap(pw, isEgress4Map);
        }
    }

    @Override
    public String toString() {
        return "mBaseIface: " + mBaseIface + ", mIface: " + mIface + ", mState: " + mState;
    }

    @VisibleForTesting
    protected int getNetId() {
        return mNetwork.network.getNetId();
    }

    @VisibleForTesting
    protected boolean isCellular464XlatEnabled() {
        return mEnableClatOnCellular;
    }
}
