/*
 * Copyright (C) 2020 The Android Open Source Project
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

import static android.system.OsConstants.IPPROTO_ICMPV6;

import static com.android.net.module.util.IpUtils.icmpv6Checksum;
import static com.android.net.module.util.NetworkStackConstants.ETHER_SRC_ADDR_OFFSET;
import static com.android.networkstack.tethering.util.TetheringUtils.getTetheringJniLibraryName;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import android.app.Instrumentation;
import android.content.Context;
import android.net.INetd;
import android.net.InetAddresses;
import android.net.MacAddress;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.SmallTest;

import com.android.net.module.util.InterfaceParams;
import com.android.networkstack.tethering.util.TetheringUtils;
import com.android.testutils.DevSdkIgnoreRule.IgnoreUpTo;
import com.android.testutils.DevSdkIgnoreRunner;
import com.android.testutils.PollPacketReader;
import com.android.testutils.TapPacketReaderRule;

import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockitoAnnotations;

import java.io.IOException;
import java.nio.ByteBuffer;

@RunWith(DevSdkIgnoreRunner.class)
@IgnoreUpTo(Build.VERSION_CODES.R)
@SmallTest
public class DadProxyTest {
    private static final int DATA_BUFFER_LEN = 4096;
    private static final int PACKET_TIMEOUT_MS = 2_000;  // Long enough for DAD to succeed.

    // Start the readers manually on a common handler shared with DadProxy, for simplicity
    @Rule
    public final TapPacketReaderRule mUpstreamReader = new TapPacketReaderRule(
            DATA_BUFFER_LEN, false /* autoStart */);
    @Rule
    public final TapPacketReaderRule mTetheredReader = new TapPacketReaderRule(
            DATA_BUFFER_LEN, false /* autoStart */);

    private InterfaceParams mUpstreamParams, mTetheredParams;
    private HandlerThread mHandlerThread;
    private Handler mHandler;
    private PollPacketReader mUpstreamPacketReader, mTetheredPacketReader;

    private static INetd sNetd;

    @BeforeClass
    public static void setupOnce() {
        System.loadLibrary(getTetheringJniLibraryName());

        final Instrumentation inst = InstrumentationRegistry.getInstrumentation();
        final IBinder netdIBinder =
                (IBinder) inst.getContext().getSystemService(Context.NETD_SERVICE);
        sNetd = INetd.Stub.asInterface(netdIBinder);
    }

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);

        mHandlerThread = new HandlerThread(getClass().getSimpleName());
        mHandlerThread.start();
        mHandler = new Handler(mHandlerThread.getLooper());

        setupTapInterfaces();

        // Looper must be prepared here since AndroidJUnitRunner runs tests on separate threads.
        if (Looper.myLooper() == null) Looper.prepare();

        DadProxy mProxy = setupProxy();
    }

    @After
    public void tearDown() throws Exception {
        mUpstreamReader.stop();
        mTetheredReader.stop();

        if (mHandlerThread != null) {
            mHandlerThread.quitSafely();
            mHandlerThread.join(PACKET_TIMEOUT_MS);
        }

        if (mTetheredParams != null) {
            sNetd.networkRemoveInterface(INetd.LOCAL_NET_ID, mTetheredParams.name);
        }
        if (mUpstreamParams != null) {
            sNetd.networkRemoveInterface(INetd.LOCAL_NET_ID, mUpstreamParams.name);
        }
    }

    private void setupTapInterfaces() throws Exception {
        // Create upstream test iface.
        mUpstreamReader.start(mHandler);
        final String upstreamIface = mUpstreamReader.iface.getInterfaceName();
        mUpstreamParams = InterfaceParams.getByName(upstreamIface);
        assertNotNull(mUpstreamParams);
        mUpstreamPacketReader = mUpstreamReader.getReader();

        // Create tethered test iface.
        mTetheredReader.start(mHandler);
        final String tetheredIface = mTetheredReader.getIface().getInterfaceName();
        mTetheredParams = InterfaceParams.getByName(tetheredIface);
        assertNotNull(mTetheredParams);
        mTetheredPacketReader = mTetheredReader.getReader();
    }

    private static final int IPV6_HEADER_LEN = 40;
    private static final int ETH_HEADER_LEN = 14;
    private static final int ICMPV6_NA_NS_LEN = 24;
    private static final int LL_TARGET_OPTION_LEN = 8;
    private static final int ICMPV6_CHECKSUM_OFFSET = 2;
    private static final int ETHER_TYPE_IPV6 = 0x86dd;

    private static ByteBuffer createDadPacket(int type) {
        // Refer to buildArpPacket()
        int icmpLen = ICMPV6_NA_NS_LEN
                + (type == NeighborPacketForwarder.ICMPV6_NEIGHBOR_ADVERTISEMENT
                ? LL_TARGET_OPTION_LEN : 0);
        final ByteBuffer buf = ByteBuffer.allocate(icmpLen + IPV6_HEADER_LEN + ETH_HEADER_LEN);

        // Ethernet header.
        final MacAddress srcMac = MacAddress.fromString("33:33:ff:66:77:88");
        buf.put(srcMac.toByteArray());
        final MacAddress dstMac = MacAddress.fromString("01:02:03:04:05:06");
        buf.put(dstMac.toByteArray());
        buf.putShort((short) ETHER_TYPE_IPV6);

        // IPv6 header
        byte[] version = {(byte) 0x60, 0x00, 0x00, 0x00};
        buf.put(version);                                           // Version
        buf.putShort((byte) icmpLen);                               // Length
        buf.put((byte) IPPROTO_ICMPV6);                             // Next header
        buf.put((byte) 0xff);                                       // Hop limit

        final byte[] target =
            InetAddresses.parseNumericAddress("fe80::1122:3344:5566:7788").getAddress();
        final byte[] src;
        final byte[] dst;
        if (type == NeighborPacketForwarder.ICMPV6_NEIGHBOR_SOLICITATION) {
            src = InetAddresses.parseNumericAddress("::").getAddress();
            dst = InetAddresses.parseNumericAddress("ff02::1:ff66:7788").getAddress();
        } else {
            src = target;
            dst = TetheringUtils.ALL_NODES;
        }
        buf.put(src);
        buf.put(dst);

        // ICMPv6 Header
        buf.put((byte) type);                                       // Type
        buf.put((byte) 0x00);                                       // Code
        buf.putShort((short) 0);                                    // Checksum
        buf.putInt(0);                                              // Reserved
        buf.put(target);

        if (type == NeighborPacketForwarder.ICMPV6_NEIGHBOR_ADVERTISEMENT) {
            //NA packet has LL target address
            //ICMPv6 Option
            buf.put((byte) 0x02);                                   // Type
            buf.put((byte) 0x01);                                   // Length
            byte[] ll_target = MacAddress.fromString("01:02:03:04:05:06").toByteArray();
            buf.put(ll_target);
        }

        // Populate checksum field
        final int transportOffset = ETH_HEADER_LEN + IPV6_HEADER_LEN;
        final short checksum = icmpv6Checksum(buf, ETH_HEADER_LEN, transportOffset, icmpLen);
        buf.putShort(transportOffset + ICMPV6_CHECKSUM_OFFSET, checksum);

        buf.flip();
        return buf;
    }

    private DadProxy setupProxy() throws Exception {
        DadProxy proxy = new DadProxy(mHandler, mTetheredParams);
        mHandler.post(() -> proxy.setUpstreamIface(mUpstreamParams));

        // Upstream iface is added to local network to simplify test case.
        // Otherwise the test needs to create and destroy a network for the upstream iface.
        sNetd.networkAddInterface(INetd.LOCAL_NET_ID, mUpstreamParams.name);
        sNetd.networkAddInterface(INetd.LOCAL_NET_ID, mTetheredParams.name);

        return proxy;
    }

    // TODO: change to assert.
    private boolean waitForPacket(ByteBuffer packet, PollPacketReader reader) {
        byte[] p;

        while ((p = reader.popPacket(PACKET_TIMEOUT_MS)) != null) {
            final ByteBuffer buffer = ByteBuffer.wrap(p);

            if (buffer.compareTo(packet) == 0) return true;
        }
        return false;
    }

    private ByteBuffer copy(ByteBuffer buf) {
        // There does not seem to be a way to copy ByteBuffers. ByteBuffer does not implement
        // clone() and duplicate() copies the metadata but shares the contents.
        return ByteBuffer.wrap(buf.array().clone());
    }

    private void updateDstMac(ByteBuffer buf, MacAddress mac) {
        buf.put(mac.toByteArray());
        buf.rewind();
    }
    private void updateSrcMac(ByteBuffer buf, InterfaceParams ifaceParams) {
        buf.position(ETHER_SRC_ADDR_OFFSET);
        buf.put(ifaceParams.macAddr.toByteArray());
        buf.rewind();
    }

    private void receivePacketAndMaybeExpectForwarded(boolean expectForwarded,
            ByteBuffer in, PollPacketReader inReader, ByteBuffer out, PollPacketReader outReader)
            throws IOException {

        inReader.sendResponse(in);
        if (waitForPacket(out, outReader)) return;

        // When the test runs, DAD may be in progress, because the interface has just been created.
        // If so, the DAD proxy will get EADDRNOTAVAIL when trying to send packets. It is not
        // possible to work around this using IPV6_FREEBIND or IPV6_TRANSPARENT options because the
        // kernel rawv6 code doesn't consider those options either when binding or when sending, and
        // doesn't get the source address from the packet even in IPPROTO_RAW/HDRINCL mode (it only
        // gets it from the socket or from cmsg).
        //
        // If DAD was in progress when the above was attempted, try again and expect the packet to
        // be forwarded. Don't disable DAD in the test because if we did, the test would not notice
        // if, for example, the DAD proxy code just crashed if it received EADDRNOTAVAIL.
        final String msg = expectForwarded
                ? "Did not receive expected packet even after waiting for DAD:"
                : "Unexpectedly received packet:";

        inReader.sendResponse(in);
        assertEquals(msg, expectForwarded, waitForPacket(out, outReader));
    }

    private void receivePacketAndExpectForwarded(ByteBuffer in, PollPacketReader inReader,
            ByteBuffer out, PollPacketReader outReader) throws IOException {
        receivePacketAndMaybeExpectForwarded(true, in, inReader, out, outReader);
    }

    private void receivePacketAndExpectNotForwarded(ByteBuffer in, PollPacketReader inReader,
            ByteBuffer out, PollPacketReader outReader) throws IOException {
        receivePacketAndMaybeExpectForwarded(false, in, inReader, out, outReader);
    }

    @Test
    public void testNaForwardingFromUpstreamToTether() throws Exception {
        ByteBuffer na = createDadPacket(NeighborPacketForwarder.ICMPV6_NEIGHBOR_ADVERTISEMENT);

        ByteBuffer out = copy(na);
        updateDstMac(out, MacAddress.fromString("33:33:00:00:00:01"));
        updateSrcMac(out, mTetheredParams);

        receivePacketAndExpectForwarded(na, mUpstreamPacketReader, out, mTetheredPacketReader);
    }

    @Test
    // TODO: remove test once DAD works in both directions.
    public void testNaForwardingFromTetherToUpstream() throws Exception {
        ByteBuffer na = createDadPacket(NeighborPacketForwarder.ICMPV6_NEIGHBOR_ADVERTISEMENT);

        ByteBuffer out = copy(na);
        updateDstMac(out, MacAddress.fromString("33:33:00:00:00:01"));
        updateSrcMac(out, mTetheredParams);

        receivePacketAndExpectNotForwarded(na, mTetheredPacketReader, out, mUpstreamPacketReader);
    }

    @Test
    public void testNsForwardingFromTetherToUpstream() throws Exception {
        ByteBuffer ns = createDadPacket(NeighborPacketForwarder.ICMPV6_NEIGHBOR_SOLICITATION);

        ByteBuffer out = copy(ns);
        updateSrcMac(out, mUpstreamParams);

        receivePacketAndExpectForwarded(ns, mTetheredPacketReader, out, mUpstreamPacketReader);
    }

    @Test
    // TODO: remove test once DAD works in both directions.
    public void testNsForwardingFromUpstreamToTether() throws Exception {
        ByteBuffer ns = createDadPacket(NeighborPacketForwarder.ICMPV6_NEIGHBOR_SOLICITATION);

        ByteBuffer out = copy(ns);
        updateSrcMac(ns, mUpstreamParams);

        receivePacketAndExpectNotForwarded(ns, mUpstreamPacketReader, out, mTetheredPacketReader);
    }
}
