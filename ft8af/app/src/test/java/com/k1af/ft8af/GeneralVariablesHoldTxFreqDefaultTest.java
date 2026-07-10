package com.k1af.ft8af;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

/**
 * Guards the default for the "Hold Tx freq" transmission setting (issue #498):
 * it must default to enabled so a fresh install (no stored {@code holdTxFreq}
 * config row) keeps the operator's TX offset instead of following the answered
 * station. The Settings > Transmission toggle and {@link
 * com.k1af.ft8af.ft8transmit.FT8TransmitSignal#shouldFollowTargetFreq} both read
 * this static, so its initial value is what "enabled by default" means.
 * Robolectric because {@link GeneralVariables} carries Android LiveData statics.
 */
@RunWith(RobolectricTestRunner.class)
public class GeneralVariablesHoldTxFreqDefaultTest {

    @Test
    public void holdTxFreq_defaultsTrue() {
        assertThat(GeneralVariables.holdTxFreq).isTrue();
    }
}
