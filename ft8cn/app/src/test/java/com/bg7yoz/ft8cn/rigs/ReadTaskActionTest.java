package com.bg7yoz.ft8cn.rigs;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;

/**
 * Verifies the timer-tick decision logic extracted into {@link ReadTaskAction}.
 * Pure logic — no Android or Robolectric required.
 */
public class ReadTaskActionTest {

    @Test
    public void disconnected_returnsSkip() {
        assertThat(ReadTaskAction.decide(false, false))
                .isEqualTo(ReadTaskAction.Action.SKIP);
        // PTT state is irrelevant when disconnected
        assertThat(ReadTaskAction.decide(false, true))
                .isEqualTo(ReadTaskAction.Action.SKIP);
    }

    @Test
    public void connected_pttOn_returnsReadMeters() {
        assertThat(ReadTaskAction.decide(true, true))
                .isEqualTo(ReadTaskAction.Action.READ_METERS);
    }

    @Test
    public void connected_pttOff_returnsReadFreq() {
        assertThat(ReadTaskAction.decide(true, false))
                .isEqualTo(ReadTaskAction.Action.READ_FREQ);
    }
}
