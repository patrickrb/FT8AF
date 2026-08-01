package com.k1af.ft8af.ft8listener;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;

/**
 * Tests for {@link FastDecodeGate}, the handshake that lets the transmitter wait for the
 * fast decode of the slot that just ended.
 *
 * <p>Why it exists, measured across one morning's activation: on a busy band (14.2 decodes
 * per cycle) 35 fast deliveries landed after key-up and their callers were answered a cycle
 * late; once the band quietened (6.3 per cycle) that count was zero. The failure scales
 * with band activity, which is backwards — a pileup is when auto-answer matters most.
 *
 * <p>The bound that keeps the wait safe lives in {@code FT8TransmitSignalKeyUpHoldTest}
 * (package-private to ft8transmit).
 */
public class FastDecodeGateTest {

    @Test
    public void idleGateReturnsImmediately() {
        // The quiet-band case, and most cycles: key-up timing must be untouched.
        FastDecodeGate gate = new FastDecodeGate();
        assertThat(gate.inFlight()).isFalse();
        long before = System.currentTimeMillis();
        assertThat(gate.awaitIdle(before + 5_000, before)).isTrue();
        assertThat(System.currentTimeMillis() - before).isLessThan(500L);
    }

    @Test
    public void beginMarksInFlight() {
        FastDecodeGate gate = new FastDecodeGate();
        gate.begin();
        assertThat(gate.inFlight()).isTrue();
        gate.end();
        assertThat(gate.inFlight()).isFalse();
    }

    @Test
    public void awaitReturnsWhenTheDecodeFinishes() throws Exception {
        FastDecodeGate gate = new FastDecodeGate();
        gate.begin();
        Thread decode = new Thread(() -> {
            try {
                Thread.sleep(80);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            gate.end();
        });
        decode.start();

        long before = System.currentTimeMillis();
        boolean landed = gate.awaitIdle(before + 5_000, before);
        long waited = System.currentTimeMillis() - before;
        decode.join();

        assertThat(landed).isTrue();
        assertThat(gate.inFlight()).isFalse();
        // Released by the decode finishing, not by burning the whole deadline.
        assertThat(waited).isLessThan(4_000L);
    }

    @Test
    public void awaitGivesUpAtTheDeadlineWhenTheDecodeNeverFinishes() {
        // A hung decode must delay key-up by the bound, never indefinitely.
        FastDecodeGate gate = new FastDecodeGate();
        gate.begin();
        long before = System.currentTimeMillis();
        boolean landed = gate.awaitIdle(before + 120, before);
        long waited = System.currentTimeMillis() - before;

        assertThat(landed).isFalse();
        assertThat(waited).isAtLeast(100L);
        assertThat(waited).isLessThan(3_000L);
    }

    @Test
    public void aDeadlineAlreadyPastDoesNotWait() {
        FastDecodeGate gate = new FastDecodeGate();
        gate.begin();
        long now = System.currentTimeMillis();
        long before = System.currentTimeMillis();
        assertThat(gate.awaitIdle(now - 1_000, now)).isFalse();
        assertThat(System.currentTimeMillis() - before).isLessThan(500L);
    }

    @Test
    public void endIsIdempotentAndUnblocksEvenIfCalledTwice() {
        FastDecodeGate gate = new FastDecodeGate();
        gate.begin();
        gate.end();
        gate.end();
        long now = System.currentTimeMillis();
        assertThat(gate.awaitIdle(now + 5_000, now)).isTrue();
    }
}
