package com.k1af.ft8af.ft8transmit;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;

/**
 * Tests {@link FT8TransmitSignal#buildWriteAudioResultLog(boolean)} — the
 * debug-log line emitted after a USB-direct TX write.
 *
 * <p>A failed write means the rig was keyed but no tones went out (dead air for
 * the whole 15 s slot). The rig keys normally, so the operator can't tell from
 * the radio that the transmission was lost — the log line for the failure case
 * must therefore say plainly that the cycle was DROPPED, not just "FAILED".
 */
public class TxResultLogTest {

    @Test
    public void success_isPlainOk() {
        assertThat(FT8TransmitSignal.buildWriteAudioResultLog(true))
                .isEqualTo("playViaUsbAudio: writeAudio returned OK");
    }

    @Test
    public void failure_spellsOutDroppedTx() {
        String log = FT8TransmitSignal.buildWriteAudioResultLog(false);
        assertThat(log).contains("FAILED");
        assertThat(log).contains("TX DROPPED");
        // Must convey that nothing was actually transmitted this cycle.
        assertThat(log).contains("no audio sent this cycle");
    }
}
