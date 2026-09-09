package com.k1af.ft8af;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

/**
 * {@link GeneralVariables#operatorChoseDial(long)} — the single entry point every
 * band-selection path funnels through.
 *
 * <p>Robolectric because GeneralVariables reaches Android types at class-load.
 */
@RunWith(RobolectricTestRunner.class)
public class GeneralVariablesOperatorDialTest {

    private static final long M30 = 10_136_000L;

    @Before
    public void resetState() {
        GeneralVariables.commandedBandHz = 0L;
        GeneralVariables.operatorDialAssertedAtMs = 0L;
        GeneralVariables.operatorDialDeliveredAtMs = 0L;
    }

    @Test
    public void recordsTheChoiceAndStampsTheAssert() {
        GeneralVariables.operatorChoseDial(M30);
        assertThat(GeneralVariables.commandedBandHz).isEqualTo(M30);
        assertThat(GeneralVariables.operatorDialAssertedAtMs).isGreaterThan(0L);
    }

    @Test
    public void aNewChoiceResetsTheDeliveryStamp() {
        // A stale stamp from an older selection must not make the new one look
        // delivered. deliveredAt < assertedAt usually covers it, but this app's
        // clock is GPS-disciplined and can step backwards, which could leave an
        // old deliveredAt at or beyond the new assertedAt. Zero is unambiguous.
        GeneralVariables.operatorDialDeliveredAtMs = Long.MAX_VALUE;
        GeneralVariables.operatorChoseDial(M30);
        assertThat(GeneralVariables.operatorDialDeliveredAtMs).isEqualTo(0L);
    }
}
