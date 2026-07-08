package com.k1af.ft8af.wave;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Tests the two predicates behind the MicRecorder capture-thread handoff that
 * fixes the field crashes from concurrent {@code reinitialize()} calls (USB
 * onCaptureStopped + one usbDetach per interface of a composite device fire
 * three reinits within ~150ms of a cable unplug):
 *
 * <ul>
 *   <li>{@link MicRecorder#captureLoopShouldContinue} — a reader thread whose
 *       generation was superseded by a reinit must exit even if it never saw
 *       {@code isRunning == false}, otherwise two threads end up read()ing the
 *       same AudioRecord and libaudioclient aborts the process
 *       ('releaseBuffer: mUnreleased out of range').</li>
 *   <li>{@link MicRecorder#shouldJoinCaptureThread} — reinitialize() waits for
 *       the reader thread before release() (releasing mid-read is that same
 *       native abort), but must never join the calling thread itself: the
 *       reader's DEAD_OBJECT path re-enters reinitialize() from inside the
 *       loop, and a self-join never returns.</li>
 * </ul>
 */
public class MicRecorderCaptureHandoffTest {

    // ---- captureLoopShouldContinue -----------------------------------------

    @Test
    public void loopContinues_whenRunningAndGenerationCurrent() {
        assertThat(MicRecorder.captureLoopShouldContinue(true, 3, 3)).isTrue();
    }

    @Test
    public void loopExits_whenStopped() {
        assertThat(MicRecorder.captureLoopShouldContinue(false, 3, 3)).isFalse();
    }

    @Test
    public void loopExits_whenGenerationSuperseded() {
        // The stale-thread case: reinitialize() bumped the generation while this
        // thread was blocked in read(); isRunning may already be true again for
        // the replacement thread, so the flag alone cannot stop the stale one.
        assertThat(MicRecorder.captureLoopShouldContinue(true, 3, 4)).isFalse();
    }

    @Test
    public void loopExits_whenStoppedAndSuperseded() {
        assertThat(MicRecorder.captureLoopShouldContinue(false, 3, 4)).isFalse();
    }

    // ---- shouldJoinCaptureThread -------------------------------------------

    @Test
    public void noJoin_whenNoCaptureThread() {
        assertThat(MicRecorder.shouldJoinCaptureThread(null, Thread.currentThread()))
                .isFalse();
    }

    @Test
    public void noJoin_whenCallerIsTheCaptureThread() {
        // DEAD_OBJECT path: the reader thread itself calls reinitialize().
        Thread self = Thread.currentThread();
        assertThat(MicRecorder.shouldJoinCaptureThread(self, self)).isFalse();
    }

    @Test
    public void noJoin_whenCaptureThreadAlreadyExited() {
        Thread neverStarted = new Thread(() -> { });
        assertThat(MicRecorder.shouldJoinCaptureThread(neverStarted, Thread.currentThread()))
                .isFalse();
    }

    @Test
    public void joins_whenLiveCaptureThreadBelongsToAnotherCaller() throws Exception {
        CountDownLatch running = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread reader = new Thread(() -> {
            running.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        reader.start();
        try {
            assertThat(running.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(MicRecorder.shouldJoinCaptureThread(reader, Thread.currentThread()))
                    .isTrue();
        } finally {
            release.countDown();
            reader.join(5000);
        }
        // Once the thread has exited there is nothing left to wait for.
        assertThat(MicRecorder.shouldJoinCaptureThread(reader, Thread.currentThread()))
                .isFalse();
    }
}
