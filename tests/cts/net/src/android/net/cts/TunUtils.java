/*
 * Copyright (C) 2018 The Android Open Source Project
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

package android.net.cts;

import static android.net.cts.PacketUtils.IP4_HDRLEN;
import static android.net.cts.PacketUtils.IP6_HDRLEN;
import static android.net.cts.PacketUtils.IPPROTO_ESP;
import static android.net.cts.PacketUtils.UDP_HDRLEN;
import static android.system.OsConstants.IPPROTO_UDP;

import static org.junit.Assert.fail;

import android.os.ParcelFileDescriptor;

import com.android.net.module.util.CollectionUtils;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Predicate;

public class TunUtils {
    private static final String TAG = TunUtils.class.getSimpleName();

    protected static final int IP4_ADDR_OFFSET = 12;
    protected static final int IP4_ADDR_LEN = 4;
    protected static final int IP6_ADDR_OFFSET = 8;
    protected static final int IP6_ADDR_LEN = 16;
    protected static final int IP4_PROTO_OFFSET = 9;
    protected static final int IP6_PROTO_OFFSET = 6;

    private static final int SEQ_NUM_MATCH_NOT_REQUIRED = -1;

    private static final int DATA_BUFFER_LEN = 4096;
    private static final int TIMEOUT = 2000;

    private final List<byte[]> mPackets = new ArrayList<>();
    private final ParcelFileDescriptor mTunFd;
    private final Thread mReaderThread;

    public TunUtils(ParcelFileDescriptor tunFd) {
        mTunFd = tunFd;

        // Start background reader thread
        mReaderThread =
                new Thread(
                        () -> {
                            try {
                                // Loop will exit and thread will quit when tunFd is closed.
                                // Receiving either EOF or an exception will exit this reader loop.
                                // FileInputStream in uninterruptable, so there's no good way to
                                // ensure that this thread shuts down except upon FD closure.
                                while (true) {
                                    byte[] intercepted = receiveFromTun();
                                    if (intercepted == null) {
                                        // Exit once we've hit EOF
                                        return;
                                    } else if (intercepted.length > 0) {
                                        // Only save packet if we've received any bytes.
                                        synchronized (mPackets) {
                                            mPackets.add(intercepted);
                                            mPackets.notifyAll();
                                        }
                                    }
                                }
                            } catch (IOException ignored) {
                                // Simply exit this reader thread
                                return;
                            }
                        });
        mReaderThread.start();
    }

    private byte[] receiveFromTun() throws IOException {
        FileInputStream in = new FileInputStream(mTunFd.getFileDescriptor());
        byte[] inBytes = new byte[DATA_BUFFER_LEN];
        int bytesRead = in.read(inBytes);

        if (bytesRead < 0) {
            return null; // return null for EOF
        } else if (bytesRead >= DATA_BUFFER_LEN) {
            throw new IllegalStateException("Too big packet. Fragmentation unsupported");
        }
        return Arrays.copyOf(inBytes, bytesRead);
    }

    private byte[] getFirstMatchingPacket(Predicate<byte[]> verifier, int startIndex) {
        synchronized (mPackets) {
            for (int i = startIndex; i < mPackets.size(); i++) {
                byte[] pkt = mPackets.get(i);
                if (verifier.test(pkt)) {
                    return pkt;
                }
            }
        }
        return null;
    }

    protected byte[] awaitPacket(Predicate<byte[]> verifier) throws Exception {
        long endTime = System.currentTimeMillis() + TIMEOUT;
        int startIndex = 0;

        synchronized (mPackets) {
            while (System.currentTimeMillis() < endTime) {
                final byte[] pkt = getFirstMatchingPacket(verifier, startIndex);
                if (pkt != null) {
                    return pkt; // We've found the packet we're looking for.
                }

                startIndex = mPackets.size();

                // Try to prevent waiting too long. If waitTimeout <= 0, we've already hit timeout
                long waitTimeout = endTime - System.currentTimeMillis();
                if (waitTimeout > 0) {
                    mPackets.wait(waitTimeout);
                }
            }
        }

        fail("No packet found matching verifier");
        throw new IllegalStateException("Impossible condition; should have thrown in fail()");
    }

    public byte[] awaitEspPacketNoPlaintext(
            int spi, byte[] plaintext, boolean useEncap, int expectedPacketSize) throws Exception {
        final byte[] espPkt = awaitPacket(
            (pkt) -> expectedPacketSize == pkt.length
                    && isEspFailIfSpecifiedPlaintextFound(pkt, spi, useEncap, plaintext));

        return espPkt; // We've found the packet we're looking for.
    }

    /** Await the expected ESP packet */
    public byte[] awaitEspPacket(int spi, boolean useEncap) throws Exception {
        return awaitEspPacket(spi, useEncap, SEQ_NUM_MATCH_NOT_REQUIRED);
    }

    /** Await the expected ESP packet with a matching sequence number */
    public byte[] awaitEspPacket(int spi, boolean useEncap, int seqNum) throws Exception {
        return awaitPacket((pkt) -> isEsp(pkt, spi, seqNum, useEncap));
    }

    private static boolean isMatchingEspPacket(byte[] pkt, int espOffset, int spi, int seqNum) {
        ByteBuffer buffer = ByteBuffer.wrap(pkt);
        buffer.get(new byte[espOffset]); // Skip IP, UDP header
        int actualSpi = buffer.getInt();
        int actualSeqNum = buffer.getInt();

        if (actualSeqNum < 0) {
            throw new UnsupportedOperationException(
                    "actualSeqNum overflowed and needs to be converted to an unsigned integer");
        }

        boolean isSeqNumMatched = (seqNum == SEQ_NUM_MATCH_NOT_REQUIRED || seqNum == actualSeqNum);

        return actualSpi == spi && isSeqNumMatched;
    }

    /**
     * Variant of isEsp that also fails the test if the provided plaintext is found
     *
     * @param pkt the packet bytes to verify
     * @param spi the expected SPI to look for
     * @param encap whether encap was enabled, and the packet has a UDP header
     * @param plaintext the plaintext packet before outbound encryption, which MUST not appear in
     *     the provided packet.
     */
    private static boolean isEspFailIfSpecifiedPlaintextFound(
            byte[] pkt, int spi, boolean encap, byte[] plaintext) {
        if (CollectionUtils.indexOfSubArray(pkt, plaintext) != -1) {
            fail("Banned plaintext packet found");
        }

        return isEsp(pkt, spi, SEQ_NUM_MATCH_NOT_REQUIRED, encap);
    }

    private static boolean isEsp(byte[] pkt, int spi, int seqNum, boolean encap) {
        if (isIpv6(pkt)) {
            if (encap) {
                return pkt[IP6_PROTO_OFFSET] == IPPROTO_UDP
                        && isMatchingEspPacket(pkt, IP6_HDRLEN + UDP_HDRLEN, spi, seqNum);
            } else {
                return pkt[IP6_PROTO_OFFSET] == IPPROTO_ESP
                        && isMatchingEspPacket(pkt, IP6_HDRLEN, spi, seqNum);
            }

        } else {
            // Use default IPv4 header length (assuming no options)
            if (encap) {
                return pkt[IP4_PROTO_OFFSET] == IPPROTO_UDP
                        && isMatchingEspPacket(pkt, IP4_HDRLEN + UDP_HDRLEN, spi, seqNum);
            } else {
                return pkt[IP4_PROTO_OFFSET] == IPPROTO_ESP
                        && isMatchingEspPacket(pkt, IP4_HDRLEN, spi, seqNum);
            }
        }
    }


    public static boolean isIpv6(byte[] pkt) {
        // First nibble shows IP version. 0x60 for IPv6
        return (pkt[0] & (byte) 0xF0) == (byte) 0x60;
    }

    private static byte[] getReflectedPacket(byte[] pkt) {
        byte[] reflected = Arrays.copyOf(pkt, pkt.length);

        if (isIpv6(pkt)) {
            // Set reflected packet's dst to that of the original's src
            System.arraycopy(
                    pkt, // src
                    IP6_ADDR_OFFSET + IP6_ADDR_LEN, // src offset
                    reflected, // dst
                    IP6_ADDR_OFFSET, // dst offset
                    IP6_ADDR_LEN); // len
            // Set reflected packet's src IP to that of the original's dst IP
            System.arraycopy(
                    pkt, // src
                    IP6_ADDR_OFFSET, // src offset
                    reflected, // dst
                    IP6_ADDR_OFFSET + IP6_ADDR_LEN, // dst offset
                    IP6_ADDR_LEN); // len
        } else {
            // Set reflected packet's dst to that of the original's src
            System.arraycopy(
                    pkt, // src
                    IP4_ADDR_OFFSET + IP4_ADDR_LEN, // src offset
                    reflected, // dst
                    IP4_ADDR_OFFSET, // dst offset
                    IP4_ADDR_LEN); // len
            // Set reflected packet's src IP to that of the original's dst IP
            System.arraycopy(
                    pkt, // src
                    IP4_ADDR_OFFSET, // src offset
                    reflected, // dst
                    IP4_ADDR_OFFSET + IP4_ADDR_LEN, // dst offset
                    IP4_ADDR_LEN); // len
        }
        return reflected;
    }

    /** Takes all captured packets, flips the src/dst, and re-injects them. */
    public void reflectPackets() throws IOException {
        synchronized (mPackets) {
            for (byte[] pkt : mPackets) {
                injectPacket(getReflectedPacket(pkt));
            }
        }
    }

    public void injectPacket(byte[] pkt) throws IOException {
        FileOutputStream out = new FileOutputStream(mTunFd.getFileDescriptor());
        out.write(pkt);
        out.flush();
    }

    /** Resets the intercepted packets. */
    public void reset() throws IOException {
        synchronized (mPackets) {
            mPackets.clear();
        }
    }
}
