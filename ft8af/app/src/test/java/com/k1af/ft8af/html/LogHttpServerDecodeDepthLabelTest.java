package com.k1af.ft8af.html;

import static com.google.common.truth.Truth.assertThat;

import com.k1af.ft8af.R;
import com.k1af.ft8af.ft8listener.DecodeDepth;

import org.junit.Test;

/** Coverage for {@link LogHttpServer#decodeDepthLabel}, the debug page's "Decode mode" row. */
public class LogHttpServerDecodeDepthLabelTest {

    @Test
    public void fast_labelsAsFastMode() {
        assertThat(LogHttpServer.decodeDepthLabel(DecodeDepth.FAST)).isEqualTo(R.string.fast_mode);
    }

    @Test
    public void balanced_labelsAsBalancedMode() {
        assertThat(LogHttpServer.decodeDepthLabel(DecodeDepth.BALANCED))
                .isEqualTo(R.string.balanced_mode);
    }

    @Test
    public void deep_labelsAsDeepMode() {
        assertThat(LogHttpServer.decodeDepthLabel(DecodeDepth.DEEP)).isEqualTo(R.string.deep_mode);
    }

    @Test
    public void outOfRangeValue_fallsBackToDeepModeLabel() {
        // Matches DecodeDepth.clamp's own "garbled value degrades to DEEP" contract.
        assertThat(LogHttpServer.decodeDepthLabel(99)).isEqualTo(R.string.deep_mode);
    }
}
