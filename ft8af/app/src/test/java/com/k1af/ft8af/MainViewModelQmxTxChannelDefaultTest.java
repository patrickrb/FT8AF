package com.k1af.ft8af;

import static com.google.common.truth.Truth.assertThat;

import com.k1af.ft8af.wave.AudioChannelSelect;

import org.junit.Test;

/**
 * Tests {@link MainViewModel#defaultTxChannelForRig} -- the TX audio channel
 * used for the rig being connected (real-hardware finding: FT8AF's historical
 * {@code BOTH} default underdrives the QMX's modulator; WSJT-X and QFT8 both
 * need an explicit mono/left selection for full drive). The QMX gets
 * {@code LEFT} only while it is the connected rig and only when the operator
 * has never saved a TX channel choice themselves.
 */
public class MainViewModelQmxTxChannelDefaultTest {

    @Test
    public void qmxWithNoSavedChoice_usesLeft() {
        assertThat(MainViewModel.defaultTxChannelForRig(
                true, false, AudioChannelSelect.BOTH)).isEqualTo(AudioChannelSelect.LEFT);
    }

    @Test
    public void savedChoice_neverOverridden() {
        // Even a saved BOTH -- the row's existence means the operator decided.
        assertThat(MainViewModel.defaultTxChannelForRig(
                true, true, AudioChannelSelect.BOTH)).isEqualTo(AudioChannelSelect.BOTH);
        assertThat(MainViewModel.defaultTxChannelForRig(
                false, true, AudioChannelSelect.RIGHT)).isEqualTo(AudioChannelSelect.RIGHT);
    }

    @Test
    public void switchingFromQmxToAnotherRig_goesBackToBoth() {
        // The QMX default was applied in memory only (LEFT); the next rig
        // connected with no saved choice must not inherit left-only TX.
        assertThat(MainViewModel.defaultTxChannelForRig(
                false, false, AudioChannelSelect.LEFT)).isEqualTo(AudioChannelSelect.BOTH);
    }

    @Test
    public void otherRigWithNoSavedChoice_usesBoth() {
        assertThat(MainViewModel.defaultTxChannelForRig(
                false, false, AudioChannelSelect.BOTH)).isEqualTo(AudioChannelSelect.BOTH);
    }
}
