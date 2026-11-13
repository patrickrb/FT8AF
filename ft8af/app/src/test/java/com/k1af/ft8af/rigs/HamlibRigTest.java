package com.k1af.ft8af.rigs;

import static com.google.common.truth.Truth.assertThat;

import com.k1af.ft8af.connector.BaseRigConnector;
import com.k1af.ft8af.database.ControlMode;
import com.k1af.ft8af.database.RigNameList;
import com.k1af.ft8af.wave.HamlibNative;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.Test;

/**
 * Pure-logic coverage for {@link HamlibRig}. The frequency/mode/PTT methods
 * delegate to native hamlib over a socket bridge and are verified end-to-end on
 * a real radio (see the PR's bench-test notes), not here — {@code libft8af.so}
 * is unavailable in a JVM unit test, so {@link com.k1af.ft8af.wave.HamlibNative}
 * reports unavailable and those calls are inert. This test pins the transport-
 * independent behavior: naming and the "connected == transport connected" rule.
 *
 * Plain JUnit: the tested paths touch no Android types.
 */
public class HamlibRigTest {

    @Test
    public void getName_isHamlib() {
        assertThat(new HamlibRig(1036).getName()).isEqualTo("Hamlib");
    }

    @Test
    public void isConnected_falseWithoutConnector() {
        // No connector attached → not connected, regardless of native state.
        assertThat(new HamlibRig(1036).isConnected()).isFalse();
    }

    @Test
    public void onReceiveData_withoutOpenHandle_isNoOpAndDoesNotThrow() {
        // Before the rig is opened (handle == 0) inbound bytes are simply
        // dropped rather than dereferencing a null bridge.
        HamlibRig rig = new HamlibRig(1036);
        rig.onReceiveData(new byte[]{(byte) 0xFE, (byte) 0xFE, 0x00, (byte) 0xFD});
        // reaching here without an exception is the assertion
        assertThat(rig.getName()).isEqualTo("Hamlib");
    }

    @Test
    public void onDisconnecting_isIdempotent_doesNotThrow() {
        // onDisconnecting() shuts down the internal worker executor. It can be
        // invoked more than once on the same instance (a user disconnect via
        // CableConnector.disconnect() leaves baseRig set, then a reconnect runs
        // connectRig() -> onDisconnecting() again). A second call must not
        // re-submit to the now-terminated executor (RejectedExecutionException).
        HamlibRig rig = new HamlibRig(1036);
        rig.onDisconnecting();
        rig.onDisconnecting(); // second call previously threw
        rig.onDisconnecting(); // still safe on repeat
        assertThat(rig.getName()).isEqualTo("Hamlib");
    }

    @Test
    public void catControlAfterDisconnecting_doesNotThrow() {
        // After the rig is torn down, any residual CAT call (e.g. the CAT
        // liveness probe, a queued band change, or the TX sequencer releasing
        // PTT) must be dropped rather than crashing the caller thread with a
        // RejectedExecutionException from the shut-down worker.
        HamlibRig rig = new HamlibRig(1036);
        rig.onDisconnecting();

        rig.setFreqToRig();
        rig.readFreqFromRig();
        rig.setUsbModeToRig();
        rig.setPTT(true);
        rig.setPTT(false);
        // reaching here without an exception is the assertion
        assertThat(rig.getName()).isEqualTo("Hamlib");
    }

    @Test
    public void taskQueuedBeforeDisconnect_doesNotRunBodyAfterClose() throws Exception {
        // A CAT task can be sitting in the worker queue at the moment
        // onDisconnecting() flips `closed`. Its body must NOT run afterward —
        // otherwise it would call ensureOpen() and re-open a handle that the
        // close task (which captured the pre-disconnect handle) never releases.
        HamlibRig rig = new HamlibRig(1036);
        CountDownLatch aStarted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean bBodyRan = new AtomicBoolean(false);

        // Task A occupies the single worker thread until released.
        rig.submitForTest(() -> {
            aStarted.countDown();
            try {
                release.await();
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        });
        assertThat(aStarted.await(5, TimeUnit.SECONDS)).isTrue();

        // Task B is enqueued behind A while the rig is still live (closed==false),
        // so it passes the submit-time fast path and gets wrapped + queued.
        rig.submitForTest(() -> bBodyRan.set(true));

        // Tear down: sets closed=true, then shutdown() lets A and B drain.
        rig.onDisconnecting();

        release.countDown();
        rig.awaitWorkerForTest();

        // The execution-time re-check dropped B's body after disconnect.
        assertThat(bBodyRan.get()).isFalse();
    }

    /** Captures the last byte[] written, standing in for the real serial link. */
    private static BaseRigConnector recordingConnector(AtomicReference<byte[]> sent) {
        return new BaseRigConnector(ControlMode.CAT) {
            @Override
            public synchronized void sendData(byte[] data) {
                sent.set(data);
            }
        };
    }

    @Test
    public void disableQmxIqModeIfApplicable_sendsQ90_forQmxModelId() {
        // 0x809 = RIG_MAKE_MODEL(RIG_KENWOOD, 57), hamlib's real QMX model ID
        // (see rigaddress.txt's "Qrp Labs QMX (Hamlib),809,...") -- the only
        // model this vendor-specific fixup should ever fire for.
        HamlibRig rig = new HamlibRig(0x809);
        AtomicReference<byte[]> sent = new AtomicReference<>();
        rig.disableQmxIqModeIfApplicable(recordingConnector(sent));
        assertThat(new String(sent.get(), StandardCharsets.US_ASCII)).isEqualTo("Q90;");
    }

    @Test
    public void disableQmxIqModeIfApplicable_isNoOp_forOtherModelIds() {
        // Only the QMX uses this vendor "Q" command; sending it to an unrelated
        // hamlib model (e.g. 1036 = FT-891) would be a meaningless raw write.
        HamlibRig rig = new HamlibRig(1036);
        AtomicReference<byte[]> sent = new AtomicReference<>();
        rig.disableQmxIqModeIfApplicable(recordingConnector(sent));
        assertThat(sent.get()).isNull();
    }

    // ---- finding 2: FT_MODE must be per-model, not global ----------------

    @Test
    public void ftModeFor_qmx_isRTTY() {
        // RTTY -> kenwood_mode_table index 6 -> "MD6;", the QMX's own Digi mode.
        assertThat(HamlibRig.ftModeFor(0x809)).isEqualTo("RTTY");
    }

    @Test
    public void ftModeFor_otherHamlibRig_isPKTUSB() {
        // 1036 = Yaesu FT-891 (Hamlib). PKTUSB (DATA-USB) is correct for it;
        // RTTY would wrongly select RTTY-LSB on newcat.
        assertThat(HamlibRig.ftModeFor(1036)).isEqualTo("PKTUSB");
    }

    // ---- finding 5: hamlib model id derives from the selected rig entry ---

    @Test
    public void modelIdFor_null_isZero() {
        assertThat(HamlibRig.modelIdFor(null)).isEqualTo(0);
    }

    @Test
    public void modelIdFor_nonHamlibEntry_isZero() {
        // An ICOM entry's address (0xA4) is a CI-V address, not a hamlib model id.
        RigNameList.RigName icom = new RigNameList.RigName(
                "ICOM IC-705", 0xA4, 19200, InstructionSet.ICOM);
        assertThat(HamlibRig.modelIdFor(icom)).isEqualTo(0);
    }

    @Test
    public void modelIdFor_qmxEntry_is2057() {
        RigNameList.RigName qmx = new RigNameList.RigName(
                "Qrp Labs QMX (Hamlib)", 0x809, 9600, InstructionSet.HAMLIB);
        assertThat(HamlibRig.modelIdFor(qmx)).isEqualTo(2057);
    }

    @Test
    public void modelIdFor_ft891Entry_is1036() {
        RigNameList.RigName ft891 = new RigNameList.RigName(
                "Yaesu FT-891 (Hamlib)", 0x40C, 38400, InstructionSet.HAMLIB);
        assertThat(HamlibRig.modelIdFor(ft891)).isEqualTo(1036);
    }

    // ---- findings 3/4: open/close races, exercised via a fake NativeApi ---

    /** A connector that reports itself as already connected, with no real transport. */
    private static BaseRigConnector connectedConnector() {
        return new BaseRigConnector(ControlMode.CAT) {
            @Override
            public boolean isConnected() {
                return true;
            }
        };
    }

    /**
     * Deterministic fake for {@link HamlibRig.NativeApi}. {@code HamlibNative}
     * can't drive these races at all on the JVM ({@code isAvailable()} is
     * hard-wired false with no {@code libft8af.so}), so this stands in for the
     * native bridge: handles are plain incrementing longs, and {@code
     * duringRigOpen} can block the worker thread mid-open to let a test fire a
     * concurrent {@code onDisconnecting()} at a precise, repeatable point.
     */
    private static class FakeNativeApi implements HamlibRig.NativeApi {
        private final AtomicLong nextHandle = new AtomicLong(1);
        final List<Integer> createCalls = new CopyOnWriteArrayList<>();
        final List<Long> closeCalls = new CopyOnWriteArrayList<>();
        final List<Long> setFreqCalls = new CopyOnWriteArrayList<>();
        volatile int rigOpenRc = 0;
        volatile Runnable duringRigOpen;

        @Override public boolean isAvailable() { return true; }

        @Override public long nativeCreate(int modelId, HamlibNative.HamlibSink sink) {
            createCalls.add(modelId);
            return nextHandle.getAndIncrement();
        }

        @Override public int nativeRigOpen(long handle) {
            if (duringRigOpen != null) duringRigOpen.run();
            return rigOpenRc;
        }

        @Override public void nativeFeedFromRig(long handle, byte[] data) { }

        @Override public int nativeSetFreq(long handle, long hz) {
            setFreqCalls.add(handle);
            return 0;
        }

        @Override public long nativeGetFreq(long handle) { return -1; }

        @Override public int nativeSetMode(long handle, String mode) { return 0; }

        @Override public int nativeSetPtt(long handle, boolean on) { return 0; }

        @Override public void nativeClose(long handle) { closeCalls.add(handle); }
    }

    @Test
    public void ensureOpen_failedRigOpen_releasesHandleExactlyOnce() throws Exception {
        // Finding 3: nativeRigOpen() no longer frees the bridge itself on failure
        // (that used to race nativeFeedFromRig on the serial thread); HamlibRig
        // must release it exactly once via releaseHandle()/nativeClose instead.
        FakeNativeApi fake = new FakeNativeApi();
        fake.rigOpenRc = -1; // force rig_open() failure
        HamlibRig rig = new HamlibRig(2057, fake);
        rig.setConnector(connectedConnector());

        rig.setFreqToRig();
        CountDownLatch drained = new CountDownLatch(1);
        rig.submitForTest(drained::countDown); // FIFO marker: runs after setFreqToRig's task
        assertThat(drained.await(5, TimeUnit.SECONDS)).isTrue();

        assertThat(fake.createCalls).containsExactly(2057);
        assertThat(fake.closeCalls).containsExactly(1L);
        assertThat(fake.setFreqCalls).isEmpty(); // open failed, so the CAT call itself never ran
    }

    @Test
    public void onDisconnecting_duringInFlightOpen_releasesExactlyOnceWithNoClobber() throws Exception {
        // Finding 4: the old onDisconnecting() read/cleared the `handle` field
        // directly on the caller's thread, racing the worker thread mid-ensureOpen()
        // -- depending on timing that either leaked the bridge ensureOpen was about
        // to publish, or let a later ensureOpen() recreate a handle the queued close
        // could never release. Now onDisconnecting() only ever submits releaseHandle()
        // to the SAME single worker thread ensureOpen() runs on, so the two can never
        // interleave -- the queued close always runs strictly after the open finishes.
        FakeNativeApi fake = new FakeNativeApi();
        CountDownLatch openStarted = new CountDownLatch(1);
        CountDownLatch openMayFinish = new CountDownLatch(1);
        fake.duringRigOpen = () -> {
            openStarted.countDown();
            try {
                openMayFinish.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };
        fake.rigOpenRc = 0; // the open succeeds once released below

        HamlibRig rig = new HamlibRig(2057, fake);
        rig.setConnector(connectedConnector());

        rig.setFreqToRig(); // worker: nativeCreate -> publish handle=1 -> blocks in nativeRigOpen
        assertThat(openStarted.await(5, TimeUnit.SECONDS)).isTrue();

        // "Disconnect" fires here, concurrently with the in-flight open, from this
        // (non-worker) thread -- exactly the scenario finding 4 describes.
        rig.onDisconnecting();

        openMayFinish.countDown();
        rig.awaitWorkerForTest();

        // The open completed using the real handle (1), unclobbered by the
        // concurrent disconnect, and the handle was released exactly once.
        assertThat(fake.setFreqCalls).containsExactly(1L);
        assertThat(fake.closeCalls).containsExactly(1L);
    }

    // ---- open-retry backoff: a persistently unresponsive radio -----------

    @Test
    public void shouldAttemptOpen_noPriorFailure_true() {
        assertThat(HamlibRig.shouldAttemptOpen(1_000L, Long.MIN_VALUE, 15_000L)).isTrue();
    }

    @Test
    public void shouldAttemptOpen_withinBackoffWindow_false() {
        assertThat(HamlibRig.shouldAttemptOpen(10_000L, 1_000L, 15_000L)).isFalse();
    }

    @Test
    public void shouldAttemptOpen_exactlyAtBackoffBoundary_true() {
        assertThat(HamlibRig.shouldAttemptOpen(16_000L, 1_000L, 15_000L)).isTrue();
    }

    @Test
    public void shouldAttemptOpen_pastBackoffWindow_true() {
        assertThat(HamlibRig.shouldAttemptOpen(20_000L, 1_000L, 15_000L)).isTrue();
    }

    @Test
    public void ensureOpen_repeatedCatCallsAfterFailure_doNotRetryWithinBackoff() throws Exception {
        // Real-hardware finding: a persistently unresponsive radio (stuck in a
        // non-CAT mode) got a brand-new native bridge every single CAT call --
        // 354 of them in ~17 minutes via the app's ~3s CAT-liveness watchdog
        // tick. Simulates two such calls landing within the same instant (well
        // inside the 15s backoff): only the first should touch the native layer.
        //
        // Drives a fake clock rather than the real one (the 3-arg constructor):
        // android.os.SystemClock.elapsedRealtime() is unmocked and returns 0 in
        // this plain (non-Robolectric) JVM test, same as it would for every
        // reading in a real run here -- harmless by itself once the "never
        // failed" sentinel is Long.MIN_VALUE rather than 0, but a fake clock
        // also makes the backoff window itself deterministic instead of
        // depending on how fast the test happens to run.
        FakeNativeApi fake = new FakeNativeApi();
        fake.rigOpenRc = -1; // every open attempt fails, like an unresponsive radio
        AtomicLong clockMs = new AtomicLong(1_000L);
        HamlibRig rig = new HamlibRig(2057, fake, clockMs::get);
        rig.setConnector(connectedConnector());

        rig.setFreqToRig(); // first CAT call at t=1000ms: attempts to open, fails
        // Drain before moving the clock -- see the sibling retry test's comment;
        // this test's own assertion happens to hold either way the race falls,
        // but draining first keeps it deterministic rather than accidentally so.
        CountDownLatch firstDrained = new CountDownLatch(1);
        rig.submitForTest(firstDrained::countDown);
        assertThat(firstDrained.await(5, TimeUnit.SECONDS)).isTrue();

        clockMs.set(1_500L); // 500ms later, well inside the 15s backoff
        rig.readFreqFromRig(); // a second CAT call landing moments later (same tick cycle)
        CountDownLatch drained = new CountDownLatch(1);
        rig.submitForTest(drained::countDown);
        assertThat(drained.await(5, TimeUnit.SECONDS)).isTrue();

        // Only the first call actually touched the native layer; the second was
        // skipped by the backoff, not a second failed create/open cycle.
        assertThat(fake.createCalls).containsExactly(2057);
        assertThat(fake.closeCalls).containsExactly(1L);
    }

    @Test
    public void ensureOpen_catCallAfterBackoffElapses_retries() throws Exception {
        // The other half of the backoff: once the window has genuinely passed,
        // the next CAT call must try again, not stay stuck forever.
        FakeNativeApi fake = new FakeNativeApi();
        fake.rigOpenRc = -1;
        AtomicLong clockMs = new AtomicLong(1_000L);
        HamlibRig rig = new HamlibRig(2057, fake, clockMs::get);
        rig.setConnector(connectedConnector());

        rig.setFreqToRig(); // fails at t=1000ms
        // Drain the worker before moving the clock: setFreqToRig() only queues the
        // task, so without this the clock could jump to 16000 on the test thread
        // before the worker has even run the first (t=1000) open attempt, making
        // the second call also see "now=16000, lastFailure=16000" (within backoff)
        // instead of the intended "lastFailure=1000, now=16000" (past it).
        CountDownLatch firstDrained = new CountDownLatch(1);
        rig.submitForTest(firstDrained::countDown);
        assertThat(firstDrained.await(5, TimeUnit.SECONDS)).isTrue();

        clockMs.set(1_000L + 15_000L); // exactly at the backoff boundary
        rig.readFreqFromRig(); // must retry, not skip

        CountDownLatch drained = new CountDownLatch(1);
        rig.submitForTest(drained::countDown);
        assertThat(drained.await(5, TimeUnit.SECONDS)).isTrue();

        assertThat(fake.createCalls).containsExactly(2057, 2057);
        // Handles are 1 then 2 -- FakeNativeApi.nextHandle increments per create.
        assertThat(fake.closeCalls).containsExactly(1L, 2L);
    }
}
