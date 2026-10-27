package com.k1af.ft8af.ft8transmit;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;

/** {@link QmxCatTones}: QMX TA command format and FT8/FT4 tone math. */
public class QmxCatTonesTest {

    @Test
    public void taCommand_matchesTheManualsExample() {
        assertThat(QmxCatTones.taCommand(1502.34)).isEqualTo("TA1502.34;");
    }

    @Test
    public void taCommand_alwaysUsesADotAndTwoDecimals() {
        assertThat(QmxCatTones.taCommand(1500)).isEqualTo("TA1500.00;");
        assertThat(QmxCatTones.taCommand(1506.255)).startsWith("TA1506.2");
    }

    @Test
    public void keyUp_isBelowTheTenHertzThreshold() {
        assertThat(QmxCatTones.KEY_UP).isEqualTo("TA0;");
    }

    @Test
    public void ft8Tones_areSpacedByTheSymbolRate() {
        assertThat(QmxCatTones.toneHz(1431.0, 0, 0.160f)).isWithin(1e-6).of(1431.0);
        assertThat(QmxCatTones.toneHz(1431.0, 7, 0.160f)).isWithin(1e-3).of(1431.0 + 43.75);
    }

    @Test
    public void ft4Tones_areSpacedByTheSymbolRate() {
        assertThat(QmxCatTones.toneHz(1000.0, 3, 0.048f)).isWithin(1e-2).of(1000.0 + 62.5);
    }

    @Test
    public void lateStart_skipsWholeSymbolsOnly() {
        assertThat(QmxCatTones.symbolsToSkip(0, 0.160f, 79)).isEqualTo(0);
        assertThat(QmxCatTones.symbolsToSkip(-50, 0.160f, 79)).isEqualTo(0);
        assertThat(QmxCatTones.symbolsToSkip(159, 0.160f, 79)).isEqualTo(0);
        assertThat(QmxCatTones.symbolsToSkip(480, 0.160f, 79)).isEqualTo(3);
        assertThat(QmxCatTones.symbolsToSkip(60000, 0.160f, 79)).isEqualTo(79);
    }

    @Test
    public void keyUp_afterANormalEndOrAStop() {
        assertThat(QmxCatTones.keyUpAfterTones(false, true)).isTrue();  // over finished
        assertThat(QmxCatTones.keyUpAfterTones(false, false)).isTrue(); // stopped
        // A swap queued and then a STOP: isTransmitting is cleared, so key up.
        assertThat(QmxCatTones.keyUpAfterTones(true, false)).isTrue();
    }

    @Test
    public void noKeyUp_acrossAMidOverMessageSwap() {
        // The replay's first TA must find the radio still transmitting; it is
        // not promised to re-key without a fresh TX;.
        assertThat(QmxCatTones.keyUpAfterTones(true, true)).isFalse();
    }
}
