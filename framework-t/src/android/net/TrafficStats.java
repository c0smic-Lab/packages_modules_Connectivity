/*
 * Copyright (C) 2007 The Android Open Source Project
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

package android.net;

import static android.annotation.SystemApi.Client.MODULE_LIBRARIES;

import static com.android.internal.annotations.VisibleForTesting.Visibility.PRIVATE;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.annotation.SuppressLint;
import android.annotation.SystemApi;
import android.annotation.TestApi;
import android.app.DownloadManager;
import android.app.backup.BackupManager;
import android.app.usage.NetworkStatsManager;
import android.compat.annotation.UnsupportedAppUsage;
import android.content.Context;
import android.media.MediaPlayer;
import android.net.netstats.StatsResult;
import android.os.Binder;
import android.os.Build;
import android.os.RemoteException;
import android.os.StrictMode;
import android.util.Log;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;

import java.io.FileDescriptor;
import java.io.IOException;
import java.net.DatagramSocket;
import java.net.Socket;
import java.net.SocketException;


/**
 * Class that provides network traffic statistics. These statistics include
 * bytes transmitted and received and network packets transmitted and received,
 * over all interfaces, over the mobile interface, and on a per-UID basis.
 * <p>
 * These statistics may not be available on all platforms. If the statistics are
 * not supported by this device, {@link #UNSUPPORTED} will be returned.
 * <p>
 * Note that the statistics returned by this class reset and start from zero
 * after every reboot. To access more robust historical network statistics data,
 * use {@link NetworkStatsManager} instead.
 */
public class TrafficStats {
    static {
        System.loadLibrary("framework-connectivity-tiramisu-jni");
    }

    private static final String TAG = TrafficStats.class.getSimpleName();
    /**
     * The return value to indicate that the device does not support the statistic.
     */
    public final static int UNSUPPORTED = -1;

    /** @hide @deprecated use {@code DataUnit} instead to clarify SI-vs-IEC */
    @Deprecated
    public static final long KB_IN_BYTES = 1024;
    /** @hide @deprecated use {@code DataUnit} instead to clarify SI-vs-IEC */
    @Deprecated
    public static final long MB_IN_BYTES = KB_IN_BYTES * 1024;
    /** @hide @deprecated use {@code DataUnit} instead to clarify SI-vs-IEC */
    @Deprecated
    public static final long GB_IN_BYTES = MB_IN_BYTES * 1024;
    /** @hide @deprecated use {@code DataUnit} instead to clarify SI-vs-IEC */
    @Deprecated
    public static final long TB_IN_BYTES = GB_IN_BYTES * 1024;
    /** @hide @deprecated use {@code DataUnit} instead to clarify SI-vs-IEC */
    @Deprecated
    public static final long PB_IN_BYTES = TB_IN_BYTES * 1024;

    /**
     * Special UID value used when collecting {@link NetworkStatsHistory} for
     * removed applications.
     *
     * @hide
     */
    public static final int UID_REMOVED = -4;

    /**
     * Special UID value used when collecting {@link NetworkStatsHistory} for
     * tethering traffic.
     *
     * @hide
     */
    public static final int UID_TETHERING = NetworkStats.UID_TETHERING;

    /**
     * Tag values in this range are reserved for the network stack. The network stack is
     * running as UID {@link android.os.Process.NETWORK_STACK_UID} when in the mainline
     * module separate process, and as the system UID otherwise.
     */
    /** @hide */
    @SystemApi
    public static final int TAG_NETWORK_STACK_RANGE_START = 0xFFFFFD00;
    /** @hide */
    @SystemApi
    public static final int TAG_NETWORK_STACK_RANGE_END = 0xFFFFFEFF;

    /**
     * Tags between 0xFFFFFF00 and 0xFFFFFFFF are reserved and used internally by system services
     * like DownloadManager when performing traffic on behalf of an application.
     */
    // Please note there is no enforcement of these constants, so do not rely on them to
    // determine that the caller is a system caller.
    /** @hide */
    @SystemApi
    public static final int TAG_SYSTEM_IMPERSONATION_RANGE_START = 0xFFFFFF00;
    /** @hide */
    @SystemApi
    public static final int TAG_SYSTEM_IMPERSONATION_RANGE_END = 0xFFFFFF0F;

    /**
     * Tag values between these ranges are reserved for the network stack to do traffic
     * on behalf of applications. It is a subrange of the range above.
     */
    /** @hide */
    @SystemApi
    public static final int TAG_NETWORK_STACK_IMPERSONATION_RANGE_START = 0xFFFFFF80;
    /** @hide */
    @SystemApi
    public static final int TAG_NETWORK_STACK_IMPERSONATION_RANGE_END = 0xFFFFFF8F;

    /**
     * Default tag value for {@link DownloadManager} traffic.
     *
     * @hide
     */
    public static final int TAG_SYSTEM_DOWNLOAD = 0xFFFFFF01;

    /**
     * Default tag value for {@link MediaPlayer} traffic.
     *
     * @hide
     */
    public static final int TAG_SYSTEM_MEDIA = 0xFFFFFF02;

    /**
     * Default tag value for {@link BackupManager} backup traffic; that is,
     * traffic from the device to the storage backend.
     *
     * @hide
     */
    public static final int TAG_SYSTEM_BACKUP = 0xFFFFFF03;

    /**
     * Default tag value for {@link BackupManager} restore traffic; that is,
     * app data retrieved from the storage backend at install time.
     *
     * @hide
     */
    public static final int TAG_SYSTEM_RESTORE = 0xFFFFFF04;

    /**
     * Default tag value for code (typically APKs) downloaded by an app store on
     * behalf of the app, such as updates.
     *
     * @hide
     */
    public static final int TAG_SYSTEM_APP = 0xFFFFFF05;

    // TODO : remove this constant when Wifi code is updated
    /** @hide */
    public static final int TAG_SYSTEM_PROBE = 0xFFFFFF42;

    @GuardedBy("TrafficStats.class")
    private static INetworkStatsService sStatsService;

    // The variable will only be accessed in the test, which is effectively
    // single-threaded.
    private static INetworkStatsService sStatsServiceForTest = null;

    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 130143562)
    private synchronized static INetworkStatsService getStatsService() {
        if (sStatsServiceForTest != null) return sStatsServiceForTest;
        if (sStatsService == null) {
            throw new IllegalStateException("TrafficStats not initialized, uid="
                    + Binder.getCallingUid());
        }
        return sStatsService;
    }

    /** @hide */
    private static int getMyUid() {
        return android.os.Process.myUid();
    }

    /**
     * Set the network stats service for testing, or null to reset.
     *
     * @hide
     */
    @VisibleForTesting(visibility = PRIVATE)
    public static void setServiceForTest(INetworkStatsService statsService) {
        sStatsServiceForTest = statsService;
    }

    /**
     * Snapshot of {@link NetworkStats} when the currently active profiling
     * session started, or {@code null} if no session active.
     *
     * @see #startDataProfiling(Context)
     * @see #stopDataProfiling(Context)
     */
    private static NetworkStats sActiveProfilingStart;

    private static Object sProfilingLock = new Object();

    private static final String LOOPBACK_IFACE = "lo";

    /**
     * Initialization {@link TrafficStats} with the context, to
     * allow {@link TrafficStats} to fetch the needed binder.
     *
     * @param context a long-lived context, such as the application context or system
     *                server context.
     * @hide
     */
    @SystemApi(client = MODULE_LIBRARIES)
    @SuppressLint("VisiblySynchronized")
    public static synchronized void init(@NonNull final Context context) {
        if (sStatsService != null) {
            throw new IllegalStateException("TrafficStats is already initialized, uid="
                    + Binder.getCallingUid());
        }
        final NetworkStatsManager statsManager =
                context.getSystemService(NetworkStatsManager.class);
        if (statsManager == null) {
            // TODO: Currently Process.isSupplemental is not working yet, because it depends on
            //  process to run in a certain UID range, which is not true for now. Change this
            //  to Log.wtf once Process.isSupplemental is ready.
            Log.e(TAG, "TrafficStats not initialized, uid=" + Binder.getCallingUid());
            return;
        }
        sStatsService = statsManager.getBinder();
    }

    /**
     * Attach the socket tagger implementation to the current process, to
     * get notified when a socket's {@link FileDescriptor} is assigned to
     * a thread. See {@link SocketTagger#set(SocketTagger)}.
     *
     * @hide
     */
    @SystemApi(client = MODULE_LIBRARIES)
    public static void attachSocketTagger() {
        dalvik.system.SocketTagger.set(new SocketTagger());
    }

    private static class SocketTagger extends dalvik.system.SocketTagger {

        // Enable log with `setprop log.tag.TrafficStats DEBUG` and restart the module.
        private static final boolean LOGD = Log.isLoggable(TAG, Log.DEBUG);

        SocketTagger() {
        }

        @Override
        public void tag(FileDescriptor fd) throws SocketException {
            final UidTag tagInfo = sThreadUidTag.get();
            if (LOGD) {
                Log.d(TAG, "tagSocket(" + fd.getInt$() + ") with statsTag=0x"
                        + Integer.toHexString(tagInfo.tag) + ", statsUid=" + tagInfo.uid);
            }
            if (tagInfo.tag == -1) {
                StrictMode.noteUntaggedSocket();
            }

            if (tagInfo.tag == -1 && tagInfo.uid == -1) return;
            final int errno = native_tagSocketFd(fd, tagInfo.tag, tagInfo.uid);
            if (errno < 0) {
                Log.i(TAG, "tagSocketFd(" + fd.getInt$() + ", "
                        + tagInfo.tag + ", "
                        + tagInfo.uid + ") failed with errno" + errno);
            }
        }

        @Override
        public void untag(FileDescriptor fd) throws SocketException {
            if (LOGD) {
                Log.i(TAG, "untagSocket(" + fd.getInt$() + ")");
            }

            final UidTag tagInfo = sThreadUidTag.get();
            if (tagInfo.tag == -1 && tagInfo.uid == -1) return;

            final int errno = native_untagSocketFd(fd);
            if (errno < 0) {
                Log.w(TAG, "untagSocket(" + fd.getInt$() + ") failed with errno " + errno);
            }
        }
    }

    private static native int native_tagSocketFd(FileDescriptor fd, int tag, int uid);
    private static native int native_untagSocketFd(FileDescriptor fd);

    private static class UidTag {
        public int tag = -1;
        public int uid = -1;
    }

    private static ThreadLocal<UidTag> sThreadUidTag = new ThreadLocal<UidTag>() {
        @Override
        protected UidTag initialValue() {
            return new UidTag();
        }
    };

    /**
     * Set active tag to use when accounting {@link Socket} traffic originating
     * from the current thread. Only one active tag per thread is supported.
     * <p>
     * Changes only take effect during subsequent calls to
     * {@link #tagSocket(Socket)}.
     * <p>
     * Tags between {@code 0xFFFFFF00} and {@code 0xFFFFFFFF} are reserved and
     * used internally by system services like {@link DownloadManager} when
     * performing traffic on behalf of an application.
     *
     * @see #clearThreadStatsTag()
     */
    public static void setThreadStatsTag(int tag) {
        getAndSetThreadStatsTag(tag);
    }

    /**
     * Set active tag to use when accounting {@link Socket} traffic originating
     * from the current thread. Only one active tag per thread is supported.
     * <p>
     * Changes only take effect during subsequent calls to
     * {@link #tagSocket(Socket)}.
     * <p>
     * Tags between {@code 0xFFFFFF00} and {@code 0xFFFFFFFF} are reserved and
     * used internally by system services like {@link DownloadManager} when
     * performing traffic on behalf of an application.
     *
     * @return the current tag for the calling thread, which can be used to
     *         restore any existing values after a nested operation is finished
     */
    public static int getAndSetThreadStatsTag(int tag) {
        final int old = sThreadUidTag.get().tag;
        sThreadUidTag.get().tag = tag;
        return old;
    }

    /**
     * Set active tag to use when accounting {@link Socket} traffic originating
     * from the current thread. The tag used internally is well-defined to
     * distinguish all backup-related traffic.
     *
     * @hide
     */
    @SystemApi
    public static void setThreadStatsTagBackup() {
        setThreadStatsTag(TAG_SYSTEM_BACKUP);
    }

    /**
     * Set active tag to use when accounting {@link Socket} traffic originating
     * from the current thread. The tag used internally is well-defined to
     * distinguish all restore-related traffic.
     *
     * @hide
     */
    @SystemApi
    public static void setThreadStatsTagRestore() {
        setThreadStatsTag(TAG_SYSTEM_RESTORE);
    }

    /**
     * Set active tag to use when accounting {@link Socket} traffic originating
     * from the current thread. The tag used internally is well-defined to
     * distinguish all code (typically APKs) downloaded by an app store on
     * behalf of the app, such as updates.
     *
     * @hide
     */
    @SystemApi
    public static void setThreadStatsTagApp() {
        setThreadStatsTag(TAG_SYSTEM_APP);
    }

    /**
     * Set active tag to use when accounting {@link Socket} traffic originating
     * from the current thread. The tag used internally is well-defined to
     * distinguish all download provider traffic.
     *
     * @hide
     */
    @SystemApi(client = MODULE_LIBRARIES)
    public static void setThreadStatsTagDownload() {
        setThreadStatsTag(TAG_SYSTEM_DOWNLOAD);
    }

    /**
     * Get the active tag used when accounting {@link Socket} traffic originating
     * from the current thread. Only one active tag per thread is supported.
     * {@link #tagSocket(Socket)}.
     *
     * @see #setThreadStatsTag(int)
     */
    public static int getThreadStatsTag() {
        return sThreadUidTag.get().tag;
    }

    /**
     * Clear any active tag set to account {@link Socket} traffic originating
     * from the current thread.
     *
     * @see #setThreadStatsTag(int)
     */
    public static void clearThreadStatsTag() {
        sThreadUidTag.get().tag = -1;
    }

    /**
     * Set specific UID to use when accounting {@link Socket} traffic
     * originating from the current thread. Designed for use when performing an
     * operation on behalf of another application, or when another application
     * is performing operations on your behalf.
     * <p>
     * Any app can <em>accept</em> blame for traffic performed on a socket
     * originally created by another app by calling this method with the
     * {@link android.system.Os#getuid()} value. However, only apps holding the
     * {@code android.Manifest.permission#UPDATE_DEVICE_STATS} permission may
     * <em>assign</em> blame to another UIDs.
     * <p>
     * Changes only take effect during subsequent calls to
     * {@link #tagSocket(Socket)}.
     */
    @SuppressLint("RequiresPermission")
    public static void setThreadStatsUid(int uid) {
        sThreadUidTag.get().uid = uid;
    }

    /**
     * Get the active UID used when accounting {@link Socket} traffic originating
     * from the current thread. Only one active tag per thread is supported.
     * {@link #tagSocket(Socket)}.
     *
     * @see #setThreadStatsUid(int)
     */
    public static int getThreadStatsUid() {
        return sThreadUidTag.get().uid;
    }

    /**
     * Set specific UID to use when accounting {@link Socket} traffic
     * originating from the current thread as the calling UID. Designed for use
     * when another application is performing operations on your behalf.
     * <p>
     * Changes only take effect during subsequent calls to
     * {@link #tagSocket(Socket)}.
     *
     * @removed
     * @deprecated use {@link #setThreadStatsUid(int)} instead.
     */
    @Deprecated
    public static void setThreadStatsUidSelf() {
        setThreadStatsUid(getMyUid());
    }

    /**
     * Clear any active UID set to account {@link Socket} traffic originating
     * from the current thread.
     *
     * @see #setThreadStatsUid(int)
     */
    @SuppressLint("RequiresPermission")
    public static void clearThreadStatsUid() {
        setThreadStatsUid(-1);
    }

    /**
     * Tag the given {@link Socket} with any statistics parameters active for
     * the current thread. Subsequent calls always replace any existing
     * parameters. When finished, call {@link #untagSocket(Socket)} to remove
     * statistics parameters.
     *
     * @see #setThreadStatsTag(int)
     */
    public static void tagSocket(@NonNull Socket socket) throws SocketException {
        SocketTagger.get().tag(socket);
    }

    /**
     * Remove any statistics parameters from the given {@link Socket}.
     * <p>
     * In Android 8.1 (API level 27) and lower, a socket is automatically
     * untagged when it's sent to another process using binder IPC with a
     * {@code ParcelFileDescriptor} container. In Android 9.0 (API level 28)
     * and higher, the socket tag is kept when the socket is sent to another
     * process using binder IPC. You can mimic the previous behavior by
     * calling {@code untagSocket()} before sending the socket to another
     * process.
     */
    public static void untagSocket(@NonNull Socket socket) throws SocketException {
        SocketTagger.get().untag(socket);
    }

    /**
     * Tag the given {@link DatagramSocket} with any statistics parameters
     * active for the current thread. Subsequent calls always replace any
     * existing parameters. When finished, call
     * {@link #untagDatagramSocket(DatagramSocket)} to remove statistics
     * parameters.
     *
     * @see #setThreadStatsTag(int)
     */
    public static void tagDatagramSocket(@NonNull DatagramSocket socket) throws SocketException {
        SocketTagger.get().tag(socket);
    }

    /**
     * Remove any statistics parameters from the given {@link DatagramSocket}.
     */
    public static void untagDatagramSocket(@NonNull DatagramSocket socket) throws SocketException {
        SocketTagger.get().untag(socket);
    }

    /**
     * Tag the given {@link FileDescriptor} socket with any statistics
     * parameters active for the current thread. Subsequent calls always replace
     * any existing parameters. When finished, call
     * {@link #untagFileDescriptor(FileDescriptor)} to remove statistics
     * parameters.
     *
     * @see #setThreadStatsTag(int)
     */
    public static void tagFileDescriptor(@NonNull FileDescriptor fd) throws IOException {
        SocketTagger.get().tag(fd);
    }

    /**
     * Remove any statistics parameters from the given {@link FileDescriptor}
     * socket.
     */
    public static void untagFileDescriptor(@NonNull FileDescriptor fd) throws IOException {
        SocketTagger.get().untag(fd);
    }

    /**
     * Start profiling data usage for current UID. Only one profiling session
     * can be active at a time.
     *
     * @hide
     */
    public static void startDataProfiling(Context context) {
        synchronized (sProfilingLock) {
            if (sActiveProfilingStart != null) {
                throw new IllegalStateException("already profiling data");
            }

            // take snapshot in time; we calculate delta later
            sActiveProfilingStart = getDataLayerSnapshotForUid(context);
        }
    }

    /**
     * Stop profiling data usage for current UID.
     *
     * @return Detailed {@link NetworkStats} of data that occurred since last
     *         {@link #startDataProfiling(Context)} call.
     * @hide
     */
    public static NetworkStats stopDataProfiling(Context context) {
        synchronized (sProfilingLock) {
            if (sActiveProfilingStart == null) {
                throw new IllegalStateException("not profiling data");
            }

            // subtract starting values and return delta
            final NetworkStats profilingStop = getDataLayerSnapshotForUid(context);
            final NetworkStats profilingDelta = NetworkStats.subtract(
                    profilingStop, sActiveProfilingStart, null, null);
            sActiveProfilingStart = null;
            return profilingDelta;
        }
    }

    /**
     * Increment count of network operations performed under the accounting tag
     * currently active on the calling thread. This can be used to derive
     * bytes-per-operation.
     *
     * @param operationCount Number of operations to increment count by.
     */
    public static void incrementOperationCount(int operationCount) {
        final int tag = getThreadStatsTag();
        incrementOperationCount(tag, operationCount);
    }

    /**
     * Increment count of network operations performed under the given
     * accounting tag. This can be used to derive bytes-per-operation.
     *
     * @param tag Accounting tag used in {@link #setThreadStatsTag(int)}.
     * @param operationCount Number of operations to increment count by.
     */
    public static void incrementOperationCount(int tag, int operationCount) {
        final int uid = getMyUid();
        try {
            getStatsService().incrementOperationCount(uid, tag, operationCount);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /** {@hide} */
    public static void closeQuietly(INetworkStatsSession session) {
        // TODO: move to NetworkStatsService once it exists
        if (session != null) {
            try {
                session.close();
            } catch (RuntimeException rethrown) {
                throw rethrown;
            } catch (Exception ignored) {
            }
        }
    }

    private static long addIfSupported(long stat) {
        return (stat == UNSUPPORTED) ? 0 : stat;
    }

    /**
     * Return number of packets transmitted across mobile networks since device
     * boot. Counts packets across all mobile network interfaces, and always
     * increases monotonically since device boot. Statistics are measured at the
     * network layer, so they include both TCP and UDP usage.
     * <p>
     * Before {@link android.os.Build.VERSION_CODES#JELLY_BEAN_MR2}, this may
     * return {@link #UNSUPPORTED} on devices where statistics aren't available.
     */
    public static long getMobileTxPackets() {
        long total = 0;
        for (String iface : getMobileIfaces()) {
            total += addIfSupported(getTxPackets(iface));
        }
        return total;
    }

    /**
     * Return number of packets received across mobile networks since device
     * boot. Counts packets across all mobile network interfaces, and always
     * increases monotonically since device boot. Statistics are measured at the
     * network layer, so they include both TCP and UDP usage.
     * <p>
     * Before {@link android.os.Build.VERSION_CODES#JELLY_BEAN_MR2}, this may
     * return {@link #UNSUPPORTED} on devices where statistics aren't available.
     */
    public static long getMobileRxPackets() {
        long total = 0;
        for (String iface : getMobileIfaces()) {
            total += addIfSupported(getRxPackets(iface));
        }
        return total;
    }

    /**
     * Return number of bytes transmitted across mobile networks since device
     * boot. Counts packets across all mobile network interfaces, and always
     * increases monotonically since device boot. Statistics are measured at the
     * network layer, so they include both TCP and UDP usage.
     * <p>
     * Before {@link android.os.Build.VERSION_CODES#JELLY_BEAN_MR2}, this may
     * return {@link #UNSUPPORTED} on devices where statistics aren't available.
     */
    public static long getMobileTxBytes() {
        long total = 0;
        for (String iface : getMobileIfaces()) {
            total += addIfSupported(getTxBytes(iface));
        }
        return total;
    }

    /**
     * Return number of bytes received across mobile networks since device boot.
     * Counts packets across all mobile network interfaces, and always increases
     * monotonically since device boot. Statistics are measured at the network
     * layer, so they include both TCP and UDP usage.
     * <p>
     * Before {@link android.os.Build.VERSION_CODES#JELLY_BEAN_MR2}, this may
     * return {@link #UNSUPPORTED} on devices where statistics aren't available.
     */
    public static long getMobileRxBytes() {
        long total = 0;
        for (String iface : getMobileIfaces()) {
            total += addIfSupported(getRxBytes(iface));
        }
        return total;
    }

    /** {@hide} */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public static long getMobileTcpRxPackets() {
        return UNSUPPORTED;
    }

    /** {@hide} */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public static long getMobileTcpTxPackets() {
        return UNSUPPORTED;
    }

    /** Clear TrafficStats rate-limit caches.
     *
     * This is mainly for {@link com.android.server.net.NetworkStatsService} to
     * clear rate-limit cache to avoid caching for TrafficStats API results.
     * Tests might get stale values after generating network traffic, which
     * generally need to wait for cache expiry to get updated values.
     *
     * @hide
     */
    @RequiresPermission(anyOf = {
            NetworkStack.PERMISSION_MAINLINE_NETWORK_STACK,
            android.Manifest.permission.NETWORK_STACK,
            android.Manifest.permission.NETWORK_SETTINGS})
    public static void clearRateLimitCaches() {
        try {
            getStatsService().clearTrafficStatsRateLimitCaches();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Return the number of packets transmitted on the specified interface since the interface
     * was created. Statistics are measured at the network layer, so both TCP and
     * UDP usage are included.
     *
     * Note that the returned values are partial statistics that do not count data from several
     * sources and do not apply several adjustments that are necessary for correctness, such
     * as adjusting for VPN apps, IPv6-in-IPv4 translation, etc. These values can be used to
     * determine whether traffic is being transferred on the specific interface but are not a
     * substitute for the more accurate statistics provided by the {@link NetworkStatsManager}
     * APIs.
     *
     * @param iface The name of the interface.
     * @return The number of transmitted packets.
     */
    public static long getTxPackets(@NonNull String iface) {
        return getIfaceStats(iface, TYPE_TX_PACKETS);
    }

    /**
     * Return the number of packets received on the specified interface since the interface was
     * created. Statistics are measured at the network layer, so both TCP
     * and UDP usage are included.
     *
     * Note that the returned values are partial statistics that do not count data from several
     * sources and do not apply several adjustments that are necessary for correctness, such
     * as adjusting for VPN apps, IPv6-in-IPv4 translation, etc. These values can be used to
     * determine whether traffic is being transferred on the specific interface but are not a
     * substitute for the more accurate statistics provided by the {@link NetworkStatsManager}
     * APIs.
     *
     * @param iface The name of the interface.
     * @return The number of received packets.
     */
    public static long getRxPackets(@NonNull String iface) {
        return getIfaceStats(iface, TYPE_RX_PACKETS);
    }

    /**
     * Return the number of bytes transmitted on the specified interface since the interface
     * was created. Statistics are measured at the network layer, so both TCP and
     * UDP usage are included.
     *
     * Note that the returned values are partial statistics that do not count data from several
     * sources and do not apply several adjustments that are necessary for correctness, such
     * as adjusting for VPN apps, IPv6-in-IPv4 translation, etc. These values can be used to
     * determine whether traffic is being transferred on the specific interface but are not a
     * substitute for the more accurate statistics provided by the {@link NetworkStatsManager}
     * APIs.
     *
     * @param iface The name of the interface.
     * @return The number of transmitted bytes.
     */
    public static long getTxBytes(@NonNull String iface) {
        return getIfaceStats(iface, TYPE_TX_BYTES);
    }

    /**
     * Return the number of bytes received on the specified interface since the interface
     * was created. Statistics are measured at the network layer, so both TCP
     * and UDP usage are included.
     *
     * Note that the returned values are partial statistics that do not count data from several
     * sources and do not apply several adjustments that are necessary for correctness, such
     * as adjusting for VPN apps, IPv6-in-IPv4 translation, etc. These values can be used to
     * determine whether traffic is being transferred on the specific interface but are not a
     * substitute for the more accurate statistics provided by the {@link NetworkStatsManager}
     * APIs.
     *
     * @param iface The name of the interface.
     * @return The number of received bytes.
     */
    public static long getRxBytes(@NonNull String iface) {
        return getIfaceStats(iface, TYPE_RX_BYTES);
    }

    /** {@hide} */
    @TestApi
    public static long getLoopbackTxPackets() {
        return getIfaceStats(LOOPBACK_IFACE, TYPE_TX_PACKETS);
    }

    /** {@hide} */
    @TestApi
    public static long getLoopbackRxPackets() {
        return getIfaceStats(LOOPBACK_IFACE, TYPE_RX_PACKETS);
    }

    /** {@hide} */
    @TestApi
    public static long getLoopbackTxBytes() {
        return getIfaceStats(LOOPBACK_IFACE, TYPE_TX_BYTES);
    }

    /** {@hide} */
    @TestApi
    public static long getLoopbackRxBytes() {
        return getIfaceStats(LOOPBACK_IFACE, TYPE_RX_BYTES);
    }

    /**
     * Return number of packets transmitted since device boot. Counts packets
     * across all network interfaces, and always increases monotonically since
     * device boot. Statistics are measured at the network layer, so they
     * include both TCP and UDP usage.
     * <p>
     * Before {@link android.os.Build.VERSION_CODES#JELLY_BEAN_MR2}, this may
     * return {@link #UNSUPPORTED} on devices where statistics aren't available.
     */
    public static long getTotalTxPackets() {
        return getTotalStats(TYPE_TX_PACKETS);
    }

    /**
     * Return number of packets received since device boot. Counts packets
     * across all network interfaces, and always increases monotonically since
     * device boot. Statistics are measured at the network layer, so they
     * include both TCP and UDP usage.
     * <p>
     * Before {@link android.os.Build.VERSION_CODES#JELLY_BEAN_MR2}, this may
     * return {@link #UNSUPPORTED} on devices where statistics aren't available.
     */
    public static long getTotalRxPackets() {
        return getTotalStats(TYPE_RX_PACKETS);
    }

    /**
     * Return number of bytes transmitted since device boot. Counts packets
     * across all network interfaces, and always increases monotonically since
     * device boot. Statistics are measured at the network layer, so they
     * include both TCP and UDP usage.
     * <p>
     * Before {@link android.os.Build.VERSION_CODES#JELLY_BEAN_MR2}, this may
     * return {@link #UNSUPPORTED} on devices where statistics aren't available.
     */
    public static long getTotalTxBytes() {
        return getTotalStats(TYPE_TX_BYTES);
    }

    /**
     * Return number of bytes received since device boot. Counts packets across
     * all network interfaces, and always increases monotonically since device
     * boot. Statistics are measured at the network layer, so they include both
     * TCP and UDP usage.
     * <p>
     * Before {@link android.os.Build.VERSION_CODES#JELLY_BEAN_MR2}, this may
     * return {@link #UNSUPPORTED} on devices where statistics aren't available.
     */
    public static long getTotalRxBytes() {
        return getTotalStats(TYPE_RX_BYTES);
    }

    /**
     * Return number of bytes transmitted by the given UID since device boot.
     * Counts packets across all network interfaces, and always increases
     * monotonically since device boot. Statistics are measured at the network
     * layer, so they include both TCP and UDP usage.
     * <p>
     * Before {@link android.os.Build.VERSION_CODES#JELLY_BEAN_MR2}, this may
     * return {@link #UNSUPPORTED} on devices where statistics aren't available.
     * <p>
     * Starting in {@link android.os.Build.VERSION_CODES#N} this will only
     * report traffic statistics for the calling UID. It will return
     * {@link #UNSUPPORTED} for all other UIDs for privacy reasons. To access
     * historical network statistics belonging to other UIDs, use
     * {@link NetworkStatsManager}.
     *
     * @see android.os.Process#myUid()
     * @see android.content.pm.ApplicationInfo#uid
     */
    public static long getUidTxBytes(int uid) {
        return getUidStats(uid, TYPE_TX_BYTES);
    }

    /**
     * Return number of bytes received by the given UID since device boot.
     * Counts packets across all network interfaces, and always increases
     * monotonically since device boot. Statistics are measured at the network
     * layer, so they include both TCP and UDP usage.
     * <p>
     * Before {@link android.os.Build.VERSION_CODES#JELLY_BEAN_MR2}, this may return
     * {@link #UNSUPPORTED} on devices where statistics aren't available.
     * <p>
     * Starting in {@link android.os.Build.VERSION_CODES#N} this will only
     * report traffic statistics for the calling UID. It will return
     * {@link #UNSUPPORTED} for all other UIDs for privacy reasons. To access
     * historical network statistics belonging to other UIDs, use
     * {@link NetworkStatsManager}.
     *
     * @see android.os.Process#myUid()
     * @see android.content.pm.ApplicationInfo#uid
     */
    public static long getUidRxBytes(int uid) {
        return getUidStats(uid, TYPE_RX_BYTES);
    }

    /**
     * Return number of packets transmitted by the given UID since device boot.
     * Counts packets across all network interfaces, and always increases
     * monotonically since device boot. Statistics are measured at the network
     * layer, so they include both TCP and UDP usage.
     * <p>
     * Before {@link android.os.Build.VERSION_CODES#JELLY_BEAN_MR2}, this may return
     * {@link #UNSUPPORTED} on devices where statistics aren't available.
     * <p>
     * Starting in {@link android.os.Build.VERSION_CODES#N} this will only
     * report traffic statistics for the calling UID. It will return
     * {@link #UNSUPPORTED} for all other UIDs for privacy reasons. To access
     * historical network statistics belonging to other UIDs, use
     * {@link NetworkStatsManager}.
     *
     * @see android.os.Process#myUid()
     * @see android.content.pm.ApplicationInfo#uid
     */
    public static long getUidTxPackets(int uid) {
        return getUidStats(uid, TYPE_TX_PACKETS);
    }

    /**
     * Return number of packets received by the given UID since device boot.
     * Counts packets across all network interfaces, and always increases
     * monotonically since device boot. Statistics are measured at the network
     * layer, so they include both TCP and UDP usage.
     * <p>
     * Before {@link android.os.Build.VERSION_CODES#JELLY_BEAN_MR2}, this may return
     * {@link #UNSUPPORTED} on devices where statistics aren't available.
     * <p>
     * Starting in {@link android.os.Build.VERSION_CODES#N} this will only
     * report traffic statistics for the calling UID. It will return
     * {@link #UNSUPPORTED} for all other UIDs for privacy reasons. To access
     * historical network statistics belonging to other UIDs, use
     * {@link NetworkStatsManager}.
     *
     * @see android.os.Process#myUid()
     * @see android.content.pm.ApplicationInfo#uid
     */
    public static long getUidRxPackets(int uid) {
        return getUidStats(uid, TYPE_RX_PACKETS);
    }

    /** @hide */
    public static long getUidStats(int uid, int type) {
        final StatsResult stats;
        try {
            stats = getStatsService().getUidStats(uid);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
        return getEntryValueForType(stats, type);
    }

    /** @hide */
    public static long getTotalStats(int type) {
        final StatsResult stats;
        try {
            stats = getStatsService().getTotalStats();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
        return getEntryValueForType(stats, type);
    }

    /** @hide */
    public static long getIfaceStats(String iface, int type) {
        final StatsResult stats;
        try {
            stats = getStatsService().getIfaceStats(iface);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
        return getEntryValueForType(stats, type);
    }

    /**
     * @deprecated Starting in {@link android.os.Build.VERSION_CODES#JELLY_BEAN_MR2},
     *             transport layer statistics are no longer available, and will
     *             always return {@link #UNSUPPORTED}.
     * @see #getUidTxBytes(int)
     */
    @Deprecated
    public static long getUidTcpTxBytes(int uid) {
        return UNSUPPORTED;
    }

    /**
     * @deprecated Starting in {@link android.os.Build.VERSION_CODES#JELLY_BEAN_MR2},
     *             transport layer statistics are no longer available, and will
     *             always return {@link #UNSUPPORTED}.
     * @see #getUidRxBytes(int)
     */
    @Deprecated
    public static long getUidTcpRxBytes(int uid) {
        return UNSUPPORTED;
    }

    /**
     * @deprecated Starting in {@link android.os.Build.VERSION_CODES#JELLY_BEAN_MR2},
     *             transport layer statistics are no longer available, and will
     *             always return {@link #UNSUPPORTED}.
     * @see #getUidTxBytes(int)
     */
    @Deprecated
    public static long getUidUdpTxBytes(int uid) {
        return UNSUPPORTED;
    }

    /**
     * @deprecated Starting in {@link android.os.Build.VERSION_CODES#JELLY_BEAN_MR2},
     *             transport layer statistics are no longer available, and will
     *             always return {@link #UNSUPPORTED}.
     * @see #getUidRxBytes(int)
     */
    @Deprecated
    public static long getUidUdpRxBytes(int uid) {
        return UNSUPPORTED;
    }

    /**
     * @deprecated Starting in {@link android.os.Build.VERSION_CODES#JELLY_BEAN_MR2},
     *             transport layer statistics are no longer available, and will
     *             always return {@link #UNSUPPORTED}.
     * @see #getUidTxPackets(int)
     */
    @Deprecated
    public static long getUidTcpTxSegments(int uid) {
        return UNSUPPORTED;
    }

    /**
     * @deprecated Starting in {@link android.os.Build.VERSION_CODES#JELLY_BEAN_MR2},
     *             transport layer statistics are no longer available, and will
     *             always return {@link #UNSUPPORTED}.
     * @see #getUidRxPackets(int)
     */
    @Deprecated
    public static long getUidTcpRxSegments(int uid) {
        return UNSUPPORTED;
    }

    /**
     * @deprecated Starting in {@link android.os.Build.VERSION_CODES#JELLY_BEAN_MR2},
     *             transport layer statistics are no longer available, and will
     *             always return {@link #UNSUPPORTED}.
     * @see #getUidTxPackets(int)
     */
    @Deprecated
    public static long getUidUdpTxPackets(int uid) {
        return UNSUPPORTED;
    }

    /**
     * @deprecated Starting in {@link android.os.Build.VERSION_CODES#JELLY_BEAN_MR2},
     *             transport layer statistics are no longer available, and will
     *             always return {@link #UNSUPPORTED}.
     * @see #getUidRxPackets(int)
     */
    @Deprecated
    public static long getUidUdpRxPackets(int uid) {
        return UNSUPPORTED;
    }

    /**
     * Return detailed {@link NetworkStats} for the current UID. Requires no
     * special permission.
     */
    private static NetworkStats getDataLayerSnapshotForUid(Context context) {
        // TODO: take snapshot locally, since proc file is now visible
        final int uid = getMyUid();
        try {
            return getStatsService().getDataLayerSnapshotForUid(uid);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Return set of any ifaces associated with mobile networks since boot.
     * Interfaces are never removed from this list, so counters should always be
     * monotonic.
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 130143562)
    private static String[] getMobileIfaces() {
        try {
            return getStatsService().getMobileIfaces();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    // NOTE: keep these in sync with {@code com_android_server_net_NetworkStatsService.cpp}.
    /** {@hide} */
    public static final int TYPE_RX_BYTES = 0;
    /** {@hide} */
    public static final int TYPE_RX_PACKETS = 1;
    /** {@hide} */
    public static final int TYPE_TX_BYTES = 2;
    /** {@hide} */
    public static final int TYPE_TX_PACKETS = 3;

    /** @hide */
    private static long getEntryValueForType(@Nullable StatsResult stats, int type) {
        if (stats == null) return UNSUPPORTED;
        if (!isEntryValueTypeValid(type)) return UNSUPPORTED;
        switch (type) {
            case TYPE_RX_BYTES:
                return stats.rxBytes;
            case TYPE_RX_PACKETS:
                return stats.rxPackets;
            case TYPE_TX_BYTES:
                return stats.txBytes;
            case TYPE_TX_PACKETS:
                return stats.txPackets;
            default:
                throw new IllegalStateException("Bug: Invalid type: "
                        + type + " should not reach here.");
        }
    }

    /** @hide */
    private static boolean isEntryValueTypeValid(int type) {
        switch (type) {
            case TYPE_RX_BYTES:
            case TYPE_RX_PACKETS:
            case TYPE_TX_BYTES:
            case TYPE_TX_PACKETS:
                return true;
            default :
                return false;
        }
    }
}

