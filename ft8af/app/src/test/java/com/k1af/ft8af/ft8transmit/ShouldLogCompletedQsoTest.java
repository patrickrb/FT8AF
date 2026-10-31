package com.k1af.ft8af.ft8transmit;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;

/**
 * Unit tests for {@link FT8TransmitSignal#shouldLogCompletedQso} — the guard the TX
 * worker uses to decide whether a just-finished over completed the QSO. It must log
 * only when our RR73/RRR (order 4) or 73 (order 5) was actually transmitted and the
 * over was not a free-text macro.
 */
public class ShouldLogCompletedQsoTest {

    @Test
    public void transmittedRr73_logs() {
        assertThat(FT8TransmitSignal.shouldLogCompletedQso(true, false, 4)).isTrue();
    }

    @Test
    public void transmitted73_logs() {
        assertThat(FT8TransmitSignal.shouldLogCompletedQso(true, false, 5)).isTrue();
    }

    @Test
    public void notTransmittedRr73_doesNotLog() {
        // Waveform generation failed (buffer == null) or the send was skipped — the
        // RR73 never went on the air, so no QSO should be logged.
        assertThat(FT8TransmitSignal.shouldLogCompletedQso(false, false, 4)).isFalse();
    }

    @Test
    public void notTransmitted73_doesNotLog() {
        assertThat(FT8TransmitSignal.shouldLogCompletedQso(false, false, 5)).isFalse();
    }

    @Test
    public void freeTextWhileParkedAtRr73_doesNotLog() {
        // Operator sits at order 4 (RR73 pending) and fires a free-text macro: the free
        // text goes out, not an RR73, so the QSO must not be logged as complete.
        assertThat(FT8TransmitSignal.shouldLogCompletedQso(true, true, 4)).isFalse();
    }

    @Test
    public void freeTextWhileParkedAt73_doesNotLog() {
        assertThat(FT8TransmitSignal.shouldLogCompletedQso(true, true, 5)).isFalse();
    }

    @Test
    public void reportExchangeOrder_doesNotLog() {
        // Order 3 is a real QSO in progress but our final ack has not gone out yet.
        assertThat(FT8TransmitSignal.shouldLogCompletedQso(true, false, 3)).isFalse();
    }

    @Test
    public void cqBaseline_doesNotLog() {
        // Order 6 is the CQ/idle baseline — not a contact.
        assertThat(FT8TransmitSignal.shouldLogCompletedQso(true, false, 6)).isFalse();
    }

    @Test
    public void earlyQsoOrders_doNotLog() {
        assertThat(FT8TransmitSignal.shouldLogCompletedQso(true, false, 1)).isFalse();
        assertThat(FT8TransmitSignal.shouldLogCompletedQso(true, false, 2)).isFalse();
    }
}
