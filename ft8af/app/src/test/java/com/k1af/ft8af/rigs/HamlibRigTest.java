package com.k1af.ft8af.rigs;

import static com.google.common.truth.Truth.assertThat;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

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
}
