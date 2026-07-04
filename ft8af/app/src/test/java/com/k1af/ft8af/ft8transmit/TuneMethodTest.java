package com.k1af.ft8af.ft8transmit;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;

/**
 * Coverage for {@link TuneMethod} — the decision behind the TUNE chip
 * (issue #425): rig internal ATU vs. low-power carrier tone.
 */
public class TuneMethodTest {

    @Test
    public void automatic_prefersAtuWhenAvailable() {
        assertThat(TuneMethod.decide(TuneMethod.AUTOMATIC, true))
                .isEqualTo(TuneMethod.ACTION_RIG_ATU);
    }

    @Test
    public void automatic_fallsBackToCarrierWhenNoAtu() {
        assertThat(TuneMethod.decide(TuneMethod.AUTOMATIC, false))
                .isEqualTo(TuneMethod.ACTION_CARRIER);
    }

    @Test
    public void internal_usesAtuWhenAvailable() {
        assertThat(TuneMethod.decide(TuneMethod.INTERNAL, true))
                .isEqualTo(TuneMethod.ACTION_RIG_ATU);
    }

    @Test
    public void internal_neverFallsBackToCarrier() {
        //The operator explicitly chose the rig's tuner; keying a surprise
        //carrier instead would be the one thing they asked us not to do.
        assertThat(TuneMethod.decide(TuneMethod.INTERNAL, false))
                .isEqualTo(TuneMethod.ACTION_UNAVAILABLE);
    }

    @Test
    public void tone_alwaysUsesCarrier() {
        assertThat(TuneMethod.decide(TuneMethod.TONE, true))
                .isEqualTo(TuneMethod.ACTION_CARRIER);
        assertThat(TuneMethod.decide(TuneMethod.TONE, false))
                .isEqualTo(TuneMethod.ACTION_CARRIER);
    }

    @Test
    public void clamp_keepsKnownMethods() {
        assertThat(TuneMethod.clamp(TuneMethod.AUTOMATIC)).isEqualTo(TuneMethod.AUTOMATIC);
        assertThat(TuneMethod.clamp(TuneMethod.INTERNAL)).isEqualTo(TuneMethod.INTERNAL);
        assertThat(TuneMethod.clamp(TuneMethod.TONE)).isEqualTo(TuneMethod.TONE);
    }

    @Test
    public void clamp_unknownValuesMeanAutomatic() {
        //Settings import (#382) can feed anything into the persisted value.
        assertThat(TuneMethod.clamp(-1)).isEqualTo(TuneMethod.AUTOMATIC);
        assertThat(TuneMethod.clamp(3)).isEqualTo(TuneMethod.AUTOMATIC);
        assertThat(TuneMethod.clamp(99)).isEqualTo(TuneMethod.AUTOMATIC);
    }

    @Test
    public void decide_unknownMethodBehavesLikeAutomatic() {
        assertThat(TuneMethod.decide(99, true)).isEqualTo(TuneMethod.ACTION_RIG_ATU);
        assertThat(TuneMethod.decide(99, false)).isEqualTo(TuneMethod.ACTION_CARRIER);
    }
}
