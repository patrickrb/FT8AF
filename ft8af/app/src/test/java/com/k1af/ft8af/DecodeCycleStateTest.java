package com.k1af.ft8af;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Unit tests for {@link DecodeCycleState} — the synchronized holder for the
 * per-cycle waterfall label overlay and decode count (issue #398). The late
 * full-slot pass (#363) makes slot N's deep delivery overlap slot N+1's early
 * pass, so the read-modify-writes these fields need must be atomic and the
 * posted count must come from the caller's own update, not a re-read.
 */
@RunWith(RobolectricTestRunner.class)
public class DecodeCycleStateTest {

    private ArrayList<Ft8Message> listOf(int n) {
        ArrayList<Ft8Message> list = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            list.add(new Ft8Message(FT8Common.FT8_MODE));
        }
        return list;
    }

    @Test
    public void normalPass_replacesLabelsAndCount() {
        DecodeCycleState state = new DecodeCycleState();
        state.labelsAfterPass(listOf(3), false);
        assertThat(state.countAfterPass(3, false)).isEqualTo(3);

        state.labelsAfterPass(listOf(2), false);
        assertThat(state.countAfterPass(2, false)).isEqualTo(2);
        assertThat(state.getMessages()).hasSize(2);
        assertThat(state.getDecodeCount()).isEqualTo(2);
    }

    @Test
    public void deepPass_augmentsLabelsAndAccumulatesCount() {
        DecodeCycleState state = new DecodeCycleState();
        state.labelsAfterPass(listOf(3), false);
        state.countAfterPass(3, false);

        state.labelsAfterPass(listOf(2), true);
        assertThat(state.countAfterPass(2, true)).isEqualTo(5);
        assertThat(state.getMessages()).hasSize(5);
        assertThat(state.getDecodeCount()).isEqualTo(5);
    }

    @Test
    public void emptyDeepPass_keepsLabels() {
        DecodeCycleState state = new DecodeCycleState();
        ArrayList<Ft8Message> kept = listOf(3);
        state.labelsAfterPass(kept, false);

        state.labelsAfterPass(new ArrayList<>(), true);
        assertThat(state.getMessages()).isSameInstanceAs(kept);
    }

    @Test
    public void emptyNormalPass_clearsLabels() {
        DecodeCycleState state = new DecodeCycleState();
        state.labelsAfterPass(listOf(3), false);

        state.labelsAfterPass(new ArrayList<>(), false);
        assertThat(state.getMessages()).isEmpty();
    }

    @Test
    public void clearAndReset() {
        DecodeCycleState state = new DecodeCycleState();
        state.labelsAfterPass(listOf(3), false);
        state.countAfterPass(3, false);

        state.clearMessages();
        state.resetCount();
        assertThat(state.getMessages()).isNull();
        assertThat(state.getDecodeCount()).isEqualTo(0);
    }

    @Test
    public void concurrentDeepCounts_neverLoseAnIncrement() throws InterruptedException {
        // The issue's failure mode: currentDecodeCount += size as an unsynchronized
        // RMW loses increments when two passes overlap. With the monitor, every
        // increment must land.
        DecodeCycleState state = new DecodeCycleState();
        final int threads = 4;
        final int perThread = 500;
        // Two-latch start: every worker signals it has reached the line before
        // all are released together — otherwise late-starting threads run mostly
        // sequentially and the test loses the overlap it exists to create.
        CountDownLatch allReady = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        ArrayList<Thread> pool = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            Thread thread = new Thread(() -> {
                allReady.countDown();
                try {
                    go.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                for (int i = 0; i < perThread; i++) {
                    state.countAfterPass(1, true);
                }
            });
            thread.start();
            pool.add(thread);
        }
        assertThat(allReady.await(5, TimeUnit.SECONDS)).isTrue();
        go.countDown();
        for (Thread thread : pool) {
            thread.join(10_000);
            assertThat(thread.isAlive()).isFalse();
        }
        assertThat(state.getDecodeCount()).isEqualTo(threads * perThread);
    }

    @Test
    public void concurrentDeepLabelMerges_neverLoseAPass() throws InterruptedException {
        // currentMessages = afterPass(currentMessages, ...) as an unsynchronized RMW
        // lets one pass's assignment clobber the other's merge. With the monitor,
        // every deep pass's messages must survive into the final overlay.
        DecodeCycleState state = new DecodeCycleState();
        final int threads = 4;
        final int perThread = 100;
        // Same two-latch start as the count test, for the same overlap reason.
        CountDownLatch allReady = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        ArrayList<Thread> pool = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            Thread thread = new Thread(() -> {
                allReady.countDown();
                try {
                    go.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                for (int i = 0; i < perThread; i++) {
                    state.labelsAfterPass(listOf(1), true);
                }
            });
            thread.start();
            pool.add(thread);
        }
        assertThat(allReady.await(5, TimeUnit.SECONDS)).isTrue();
        go.countDown();
        for (Thread thread : pool) {
            thread.join(10_000);
            assertThat(thread.isAlive()).isFalse();
        }
        assertThat(state.getMessages()).hasSize(threads * perThread);
    }
}
