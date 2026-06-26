package com.k1af.ft8af;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;

/**
 * Unit tests for {@link MainViewModel#shouldClearOnBandChange} — the gate that
 * decides whether a band change wipes the decode list and resets the TX target.
 * It must fire only when the auto-clear option is on AND the band actually changed,
 * so a no-op band re-select or a small in-band rig dial report doesn't clear.
 */
public class ClearOnBandChangeTest {

    @Test
    public void enabledAndBandChanged_clears() {
        // The reported request: change band -> wipe stale decodes.
        assertThat(MainViewModel.shouldClearOnBandChange(true, 2, 5)).isTrue();
    }

    @Test
    public void enabledButSameBand_doesNotClear() {
        // Re-selecting the current band (or a sub-band dial report) must not clear.
        assertThat(MainViewModel.shouldClearOnBandChange(true, 3, 3)).isFalse();
    }

    @Test
    public void disabled_neverClears() {
        // Option off: keep the decodes even across a real band change.
        assertThat(MainViewModel.shouldClearOnBandChange(false, 2, 5)).isFalse();
        assertThat(MainViewModel.shouldClearOnBandChange(false, 3, 3)).isFalse();
    }
}
