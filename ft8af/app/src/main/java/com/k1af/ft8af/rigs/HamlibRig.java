package com.k1af.ft8af.rigs;

import android.os.SystemClock;
import android.util.Log;

import com.k1af.ft8af.connector.BaseRigConnector;
import com.k1af.ft8af.database.RigNameList;
import com.k1af.ft8af.wave.HamlibNative;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * A {@link BaseRig} backed by hamlib instead of a hand-rolled per-manufacturer
 * byte table. hamlib supplies the CAT protocol for the given model; this class
 * only marshals between the app and hamlib.
 *
 * <p>Transport is unchanged: hamlib's CAT bytes are handed to the existing
 * connector via {@code sink}, and bytes the radio sends back arrive at
 * {@link #onReceiveData(byte[])} and are fed into hamlib. See
 * {@link HamlibNative} and {@code cpp/ft8af_glue/hamlib_jni.cpp}.
 *
 * <p>hamlib's {@code rig_*} calls are synchronous CAT round-trips, so they run
 * on a private single-thread executor — never the UI thread and never the
 * serial read thread (which delivers {@link #onReceiveData}); otherwise a
 * blocking call could never receive its own ack.
 */
public class HamlibRig extends BaseRig {
    private static final String TAG = "HamlibRig";

    /**
     * hamlib mode for FT8/FT4 on most rigs: DATA-USB, so audio routes through
     * the data path rather than a mic/voice path.
     */
    private static final String FT_MODE = "PKTUSB";

    /**
     * QMX override: hamlib's generic Kenwood mode table (rigs/kenwood/kenwood.c's
     * kenwood_mode_table) encodes RIG_MODE_PKTUSB as table index 13, which
     * kenwood_set_mode() turns into the ASCII letter 'D' -- "MDD;". The QMX's own
     * CAT manual (cat_1_04_003.pdf) only accepts single-digit MD values
     * (1,2,3,5,6,7,8,9) and silently rejects 'D', so PKTUSB never actually set the
     * mode on this radio. "RTTY" is table index 6 (RIG_MODE_RTTY, &lt;= 9, encoded
     * as a literal digit "MD6;"), the QMX manual's own "6 (FSK)" -- its real Digi
     * mode. Applying this to every hamlib rig (not just the QMX) would send
     * RIG_MODE_RTTY to rigs that expect PKTUSB, e.g. an FT-891 via newcat would
     * get "MD06" (RTTY-LSB) instead of DATA-USB -- wrong mode and filter.
     */
    private static final String QMX_FT_MODE = "RTTY";

    /** Which {@link #FT_MODE} string to send for a given hamlib model id. */
    static String ftModeFor(int modelId) {
        return modelId == QRPLABS_QMX_MODEL_ID ? QMX_FT_MODE : FT_MODE;
    }

    private final int modelId;
    private final NativeApi nativeApi;
    /** Clock behind the open-retry backoff; see {@link #ensureOpen()}. */
    private final java.util.function.LongSupplier elapsedRealtimeMs;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private volatile long handle = 0;
    /**
     * Guards {@link #handle} against the one genuine cross-thread race: a free
     * ({@link #releaseHandle()}, on the single {@link #worker} thread) running
     * concurrently with a feed ({@link #onReceiveData}, on the serial read
     * thread) on the same native handle. {@code ensureOpen()}/{@code
     * releaseHandle()} never race each other -- both only ever run via {@link
     * #worker}, a single-thread executor -- so publishing a freshly opened
     * handle needs no lock; only freeing it does.
     */
    private final ReentrantReadWriteLock bridgeLock = new ReentrantReadWriteLock();
    /**
     * Set once in {@link #onDisconnecting()}. After that the {@link #worker}
     * executor has been shut down, so any further {@code worker.submit(...)}
     * would throw {@link RejectedExecutionException}. Guarding on this flag (and
     * catching the race with the shutdown) keeps a post-disconnect CAT call — or
     * a second {@code onDisconnecting()} — from crashing the app.
     */
    private volatile boolean closed = false;

    /** Receives CAT bytes hamlib wants sent to the radio; forwards to connector. */
    private final HamlibNative.HamlibSink sink = data -> {
        BaseRigConnector c = getConnector();
        if (c != null) {
            c.sendData(data);
        }
    };

    public HamlibRig(int modelId) {
        this(modelId, REAL_NATIVE_API);
    }

    /** Visible for tests: inject a fake native layer to drive races on the JVM. */
    HamlibRig(int modelId, NativeApi nativeApi) {
        this(modelId, nativeApi, SystemClock::elapsedRealtime);
    }

    /**
     * Visible for tests: also inject the clock behind the open-retry backoff
     * (see {@link #ensureOpen()}), so it's testable deterministically rather
     * than depending on real elapsed time. {@code SystemClock.elapsedRealtime()}
     * is otherwise unmocked and returns 0 under a plain (non-Robolectric) JVM
     * unit test (app/build.gradle's {@code unitTests.returnDefaultValues}) --
     * harmless on its own, but worth not depending on at all here.
     */
    HamlibRig(int modelId, NativeApi nativeApi, java.util.function.LongSupplier elapsedRealtimeMs) {
        this.modelId = modelId;
        this.nativeApi = nativeApi;
        this.elapsedRealtimeMs = elapsedRealtimeMs;
    }

    /**
     * Seam over {@link HamlibNative}'s static natives. {@code HamlibNative}
     * can't be exercised from a JVM unit test (no {@code libft8af.so}, and
     * {@link HamlibNative#isAvailable()} is hard-wired to {@code false} there),
     * so real races like findings 3/4 (use-after-free on a failed open,
     * double-free/leak on a disconnect that overlaps an open) were otherwise
     * untestable outside real hardware. A fake implementation lets tests drive
     * {@link #ensureOpen()}/{@link #releaseHandle()} directly.
     */
    interface NativeApi {
        boolean isAvailable();
        long nativeCreate(int modelId, HamlibNative.HamlibSink sink);
        int nativeRigOpen(long handle);
        void nativeFeedFromRig(long handle, byte[] data);
        int nativeSetFreq(long handle, long hz);
        long nativeGetFreq(long handle);
        int nativeSetMode(long handle, String mode);
        int nativeSetPtt(long handle, boolean on);
        void nativeClose(long handle);
    }

    private static final NativeApi REAL_NATIVE_API = new NativeApi() {
        @Override public boolean isAvailable() { return HamlibNative.isAvailable(); }
        @Override public long nativeCreate(int modelId, HamlibNative.HamlibSink sink) {
            return HamlibNative.nativeCreate(modelId, sink);
        }
        @Override public int nativeRigOpen(long handle) { return HamlibNative.nativeRigOpen(handle); }
        @Override public void nativeFeedFromRig(long handle, byte[] data) {
            HamlibNative.nativeFeedFromRig(handle, data);
        }
        @Override public int nativeSetFreq(long handle, long hz) { return HamlibNative.nativeSetFreq(handle, hz); }
        @Override public long nativeGetFreq(long handle) { return HamlibNative.nativeGetFreq(handle); }
        @Override public int nativeSetMode(long handle, String mode) {
            return HamlibNative.nativeSetMode(handle, mode);
        }
        @Override public int nativeSetPtt(long handle, boolean on) { return HamlibNative.nativeSetPtt(handle, on); }
        @Override public void nativeClose(long handle) { HamlibNative.nativeClose(handle); }
    };

    /**
     * Minimum time between native open attempts after one fails (either
     * {@code nativeCreate} or {@code nativeRigOpen}/the CAT handshake).
     * Without this, a persistently unresponsive radio got a brand-new native
     * bridge (loopback socket + pump thread) on every single CAT call that
     * happens to run while handle==0 -- confirmed on real hardware: a QMX
     * stuck in a non-CAT mode got 354 fresh bridges in about 17 minutes (each
     * one created and torn down within ~300ms, via the ~3s CAT-liveness
     * watchdog tick plus other CAT calls queuing in between). Each cycle was
     * cleaned up correctly (no leak -- see releaseHandle()), but churning
     * native resources this often for an indefinitely unresponsive radio is
     * still wasteful. 15s caps that at roughly a quarter of the previous rate
     * while still recovering promptly once the radio answers again.
     */
    private static final long OPEN_RETRY_BACKOFF_MS = 15_000;
    /**
     * {@link #elapsedRealtimeMs}'s reading at the last open failure, or
     * {@link Long#MIN_VALUE} as the "never failed" sentinel -- NOT 0: a plain
     * JVM unit test (no Robolectric) gets 0 for every unmocked {@code
     * SystemClock.elapsedRealtime()} call, which collided with 0 as a
     * sentinel and made the very first backoff check after a failure see
     * "now == last failure == 0" and wrongly allow an immediate retry.
     */
    private volatile long lastOpenFailureAtMs = Long.MIN_VALUE;

    /**
     * Whether {@link #ensureOpen()} should attempt a fresh native open now,
     * given when the last attempt failed. Pure/testable: {@code
     * ensureOpen()} itself touches native calls and a connector, so the
     * backoff decision is extracted here rather than timed with a real
     * clock + sleep in a test.
     *
     * @param nowMs          current time; callers must use {@link
     *                       SystemClock#elapsedRealtime()}, not wall-clock
     *                       time -- an NTP sync or manual clock change could
     *                       otherwise stall reopening indefinitely (clock
     *                       jumps back) or skip the backoff entirely (clock
     *                       jumps forward)
     * @param lastFailureAtMs the same clock's reading when the last attempt
     *                        failed, or {@link Long#MIN_VALUE} if there
     *                        hasn't been one (NOT 0 -- see {@link
     *                        #lastOpenFailureAtMs}'s javadoc)
     */
    static boolean shouldAttemptOpen(long nowMs, long lastFailureAtMs, long backoffMs) {
        return lastFailureAtMs == Long.MIN_VALUE || nowMs - lastFailureAtMs >= backoffMs;
    }

    /**
     * Open hamlib once the transport is connected. hamlib's {@code rig_open}
     * performs an initial CAT handshake, so the connector must be live and
     * forwarding bytes first. Called at the head of each worker task; safe to
     * call repeatedly.
     */
    private synchronized boolean ensureOpen() {
        if (closed) {
            // Torn down (or being torn down): never re-open. A task that was
            // queued just before onDisconnecting() could otherwise open a fresh
            // handle here that the close task (which captured the old handle)
            // never releases — leaking the rig and leaving it open post-disconnect.
            return false;
        }
        if (handle != 0) {
            return true;
        }
        if (!shouldAttemptOpen(elapsedRealtimeMs.getAsLong(), lastOpenFailureAtMs, OPEN_RETRY_BACKOFF_MS)) {
            return false;
        }
        BaseRigConnector c = getConnector();
        if (c == null || !c.isConnected() || !nativeApi.isAvailable()) {
            return false;
        }
        if (modelId <= 0) {
            // Never hand hamlib a placeholder/unset model ID: rig_init(0) was
            // observed on real hardware to abort the whole process (native
            // SIGABRT, "pthread_mutex_lock called on a destroyed mutex") a
            // fraction of a second later, not just fail gracefully. 0 means
            // "not a hamlib entry" (see HamlibRig.modelIdFor) and must never
            // reach native code.
            Log.w(TAG, "ensureOpen: refusing to open invalid hamlib model " + modelId);
            return false;
        }
        long h = nativeApi.nativeCreate(modelId, sink);
        if (h == 0) {
            Log.w(TAG, "nativeCreate failed for hamlib model " + modelId);
            lastOpenFailureAtMs = elapsedRealtimeMs.getAsLong();
            return false;
        }
        // Publish the handle BEFORE running the handshake: onReceiveData()
        // only forwards radio bytes into the bridge once handle != 0, and
        // rig_open() (called by nativeRigOpen below) does its own synchronous
        // CAT handshake that needs those bytes fed back in real time. With a
        // single combined open call, every byte the radio sent during that
        // handshake was silently dropped (handle was still 0), so hamlib
        // always saw a timeout — confirmed on real hardware: the radio was
        // genuinely replying (serial.recv had real traffic) while hamlib's
        // own trace reported "0 chars" for that exact window, every time.
        handle = h;
        int rc = nativeApi.nativeRigOpen(h);
        if (rc != 0) {
            Log.w(TAG, "nativeRigOpen failed for hamlib model " + modelId + " rc=" + rc);
            releaseHandle();
            lastOpenFailureAtMs = elapsedRealtimeMs.getAsLong();
            return false;
        }
        Log.i(TAG, "hamlib model " + modelId + " opened");
        lastOpenFailureAtMs = Long.MIN_VALUE;
        disableQmxIqModeIfApplicable(c);
        return true;
    }

    /**
     * Unpublish and free the bridge. Runs on {@link #worker} (so it never
     * overlaps an {@link #ensureOpen()} on the same handle — both only ever
     * run there) under {@link #bridgeLock}'s write lock (so it never overlaps
     * a feed in {@link #onReceiveData}, which takes the read lock).
     */
    private void releaseHandle() {
        bridgeLock.writeLock().lock();
        try {
            long h = handle;
            handle = 0;
            if (h != 0) nativeApi.nativeClose(h);
        } finally {
            bridgeLock.writeLock().unlock();
        }
    }

    /**
     * QRP Labs QMX-specific fixup, sent once right after a successful open:
     * force I/Q mode off ({@code "Q90;"}). hamlib has no generic API for this
     * -- it's one of the QMX's vendor "Q" extended commands
     * (qrp-labs.com/images/qmx/manuals/cat_1_04_003.pdf) -- so this bypasses
     * hamlib and writes the connector directly, the same mechanism
     * {@link #sink} uses for hamlib's own bytes. Left on (e.g. from prior SDR
     * use via the radio's own front-panel menu), I/Q mode streams raw ADC
     * samples instead of demodulated audio, which a decoder sees as noise;
     * the setting is session-only, so it silently returns on every power-
     * cycle. Matches danleetw/FT8TW#78, fixed there with the same command.
     *
     * <p>Package-private: visible for {@code disableQmxIqModeIfApplicable_*} tests.
     *
     * @param c the connector to write to; never null when called from
     *          {@link #ensureOpen()} (already checked non-null/connected)
     */
    void disableQmxIqModeIfApplicable(BaseRigConnector c) {
        if (modelId != QRPLABS_QMX_MODEL_ID) return;
        c.sendData("Q90;".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
    }

    /** True for the QRP Labs QMX, which can transmit from CAT tone commands. */
    public boolean isQmx() {
        return modelId == QRPLABS_QMX_MODEL_ID;
    }

    /**
     * hamlib model ID for the QRP Labs QMX (0x809), per rigaddress.txt. Public:
     * also used by {@code MainViewModel} to apply the QMX's LEFT-channel TX
     * audio default on connect (see {@code defaultTxChannelForRig}).
     */
    public static final int QRPLABS_QMX_MODEL_ID = 0x809;

    /**
     * hamlib model ID for the currently-selected rig entry, or 0 if it isn't a
     * hamlib entry at all. Derives it fresh from {@code rigaddress.txt}'s
     * address column rather than from a separately persisted config value: a
     * prior {@code hamlibModel} config key was written for every rig pick
     * (including non-hamlib ones, storing e.g. an ICOM CI-V address like 0x94
     * there) and left stale by one picker path that never wrote it at all, so
     * a leftover or stale value could silently win over the actually-selected
     * entry after a restart and open the wrong hamlib backend.
     *
     * @param selected the picked rig entry (e.g. from
     *                 {@code RigNameList.getRigNameByIndex}), or null
     */
    public static int modelIdFor(RigNameList.RigName selected) {
        if (selected == null || selected.instructionSet != InstructionSet.HAMLIB) return 0;
        return Math.max(selected.address, 0);
    }

    @Override
    public boolean isConnected() {
        BaseRigConnector c = getConnector();
        return c != null && c.isConnected();
    }

    @Override
    public void onReceiveData(byte[] data) {
        // Called on the serial read thread — a quick, non-blocking socket write.
        // Read-locked against releaseHandle()'s write lock: without this, a feed
        // here could run concurrently with the native free, using a handle whose
        // bridge is mid-teardown (or already deleted) on the worker thread.
        bridgeLock.readLock().lock();
        try {
            long h = handle;
            if (h != 0) {
                nativeApi.nativeFeedFromRig(h, data);
            }
        } finally {
            bridgeLock.readLock().unlock();
        }
    }

    /**
     * Submit a CAT task to the worker, tolerating a rig that is (or is being)
     * torn down. Once {@link #onDisconnecting()} has shut the executor down,
     * {@code worker.submit(...)} would throw {@link RejectedExecutionException}
     * on the caller's thread (the UI thread, or the TX sequencer for PTT) and
     * crash the app; here the request is simply dropped instead.
     */
    private void submitToWorker(Runnable task) {
        if (closed) return; // fast path: don't even enqueue after disconnect
        try {
            worker.submit(() -> {
                // Re-check at execution time: a task enqueued a moment before
                // onDisconnecting() set closed can still be sitting in the queue
                // when the rig is torn down. Running its CAT body then would call
                // ensureOpen() and could re-open the handle after disconnect.
                if (closed) return;
                task.run();
            });
        } catch (RejectedExecutionException e) {
            // Raced onDisconnecting()'s shutdown; the rig is gone — drop the task.
            Log.w(TAG, "worker rejected task after disconnect");
        }
    }

    /** Visible for tests: submit a raw task through the same closed-gate. */
    void submitForTest(Runnable task) {
        submitToWorker(task);
    }

    /** Visible for tests: block until the worker has fully drained/terminated. */
    void awaitWorkerForTest() throws InterruptedException {
        worker.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS);
    }

    @Override
    public void setUsbModeToRig() {
        submitToWorker(() -> {
            if (!ensureOpen()) return;
            String mode = ftModeFor(modelId);
            int rc = nativeApi.nativeSetMode(handle, mode);
            if (rc != 0) Log.w(TAG, "set_mode(" + mode + ") rc=" + rc);
        });
    }

    @Override
    public void setFreqToRig() {
        // getFreq() holds the app's target (set via setFreq() just before this).
        // The app already picks an in-band FT8 frequency, so this never QSYs to
        // an untuned band — hamlib just sets exactly what the app asked for.
        final long hz = getFreq();
        submitToWorker(() -> {
            if (!ensureOpen()) return;
            int rc = nativeApi.nativeSetFreq(handle, hz);
            if (rc != 0) Log.w(TAG, "set_freq(" + hz + ") rc=" + rc);
        });
    }

    @Override
    public void readFreqFromRig() {
        submitToWorker(() -> {
            if (!ensureOpen()) return;
            long f = nativeApi.nativeGetFreq(handle);
            if (f > 0) {
                setFreq(f); // updates dial + fires CAT-liveness (BaseRig)
            }
        });
    }

    @Override
    public void setPTT(boolean on) {
        super.setPTT(on); // notify listeners / meter state
        submitToWorker(() -> {
            if (!ensureOpen()) return;
            int rc = nativeApi.nativeSetPtt(handle, on);
            if (rc != 0) Log.w(TAG, "set_ptt(" + on + ") rc=" + rc);
        });
    }

    @Override
    public String getName() {
        return "Hamlib";
    }

    @Override
    public void onDisconnecting() {
        // Idempotent: this can be called more than once on the same instance
        // (e.g. a user disconnect via CableConnector.disconnect() followed by a
        // reconnect through connectRig(), which does not null the old rig).
        // Shutting the worker down twice — or submitting to it after the first
        // shutdown — would throw RejectedExecutionException and crash the app.
        if (closed) return;
        closed = true;
        try {
            // Read/clear/close handle via releaseHandle() on the SAME worker
            // thread ensureOpen() runs on, queued behind it rather than racing
            // it from this (caller) thread. The previous version read/cleared
            // the volatile `handle` field directly here, concurrently with the
            // worker possibly being mid-ensureOpen(): depending on timing that
            // either leaked the bridge ensureOpen was about to publish (this
            // close captures a stale value, e.g. 0, just before ensureOpen
            // overwrites it) or closed a handle a subsequent ensureOpen() had
            // already reused after wrongly seeing a momentarily-zeroed `handle`
            // as "not yet open" and recreating it.
            worker.submit(this::releaseHandle);
        } catch (RejectedExecutionException e) {
            // Worker already terminated, so it can't run the release for us.
            Log.w(TAG, "worker rejected close task; closing handle inline");
            releaseHandle();
        }
        worker.shutdown();
    }
}
