package com.k1af.ft8af;

import static com.google.common.truth.Truth.assertThat;

import com.k1af.ft8af.rigs.HamlibRig;

import org.junit.Test;

/**
 * Tests {@link MainViewModel#shouldDefaultQmxTxChannelToLeft} -- the decision
 * behind defaulting a QMX's TX audio to the left channel on first connect
 * (real-hardware finding: FT8AF's historical {@code BOTH} default
 * underdrives this radio's modulator; WSJT-X and QFT8 both need an explicit
 * mono/left selection for full drive). Must fire only for the QMX, and only
 * when the operator has never touched the TX channel setting themselves.
 */
public class MainViewModelQmxTxChannelDefaultTest {

    @Test
    public void qmxWithNoPriorChoice_defaults() {
        assertThat(MainViewModel.shouldDefaultQmxTxChannelToLeft(
                HamlibRig.QRPLABS_QMX_MODEL_ID, false)).isTrue();
    }

    @Test
    public void qmxWithAnExistingChoice_neverOverridden() {
        // Even if the existing choice happens to be the same as the historical
        // default (BOTH) -- the row's mere existence means the operator (or an
        // earlier run of this same default) already decided, and that decision
        // must stick.
        assertThat(MainViewModel.shouldDefaultQmxTxChannelToLeft(
                HamlibRig.QRPLABS_QMX_MODEL_ID, true)).isFalse();
    }

    @Test
    public void otherHamlibRig_neverDefaulted() {
        // 1036 = Yaesu FT-891 (Hamlib) -- no evidence this applies to any rig
        // but the QMX specifically.
        assertThat(MainViewModel.shouldDefaultQmxTxChannelToLeft(1036, false)).isFalse();
    }

    @Test
    public void nonHamlibModelId_neverDefaulted() {
        assertThat(MainViewModel.shouldDefaultQmxTxChannelToLeft(0, false)).isFalse();
    }
}
