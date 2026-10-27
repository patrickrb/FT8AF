package com.k1af.ft8af.ft8listener;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;

/**
 * Coverage for the FAST/BALANCED/DEEP decode-depth decisions that replaced
 * the old fast/deep boolean toggle -- see {@link DecodeDepth}'s class
 * javadoc for what each level actually runs.
 */
public class DecodeDepthTest {

    @Test
    public void fast_runsNeitherDeepPassNorSubtractLoop() {
        assertThat(DecodeDepth.runsDeepPass(DecodeDepth.FAST)).isFalse();
        assertThat(DecodeDepth.runsSubtractRedecodeLoop(DecodeDepth.FAST)).isFalse();
    }

    @Test
    public void balanced_runsDeepPassButNotSubtractLoop() {
        assertThat(DecodeDepth.runsDeepPass(DecodeDepth.BALANCED)).isTrue();
        assertThat(DecodeDepth.runsSubtractRedecodeLoop(DecodeDepth.BALANCED)).isFalse();
    }

    @Test
    public void deep_runsBothDeepPassAndSubtractLoop() {
        assertThat(DecodeDepth.runsDeepPass(DecodeDepth.DEEP)).isTrue();
        assertThat(DecodeDepth.runsSubtractRedecodeLoop(DecodeDepth.DEEP)).isTrue();
    }

    @Test
    public void clamp_passesThroughValidValues() {
        assertThat(DecodeDepth.clamp(DecodeDepth.FAST)).isEqualTo(DecodeDepth.FAST);
        assertThat(DecodeDepth.clamp(DecodeDepth.BALANCED)).isEqualTo(DecodeDepth.BALANCED);
        assertThat(DecodeDepth.clamp(DecodeDepth.DEEP)).isEqualTo(DecodeDepth.DEEP);
    }

    @Test
    public void clamp_outOfRangeValueBecomesDeep() {
        // Degrades to "most thorough" on a garbled/unrecognized value, never
        // silently to "least" -- matching the pre-existing deepDecodeMode=true
        // default this field replaces.
        assertThat(DecodeDepth.clamp(-1)).isEqualTo(DecodeDepth.DEEP);
        assertThat(DecodeDepth.clamp(99)).isEqualTo(DecodeDepth.DEEP);
    }
}
