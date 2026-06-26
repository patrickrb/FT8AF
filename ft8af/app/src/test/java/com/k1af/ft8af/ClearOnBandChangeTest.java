package com.k1af.ft8af;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;

/**
 * Unit tests for {@link MainViewModel#shouldClearOnBandChange} — the gate that
 * decides whether a band change wipes the decode list and resets the TX target.
 * It must fire only when the auto-clear option is on AND the meter (wavelength)
 * band actually changed, so a no-op band re-select or a small in-band rig dial
 * report (which can move the band-list index without changing the band) doesn't
 * clear.
 */
public class ClearOnBandChangeTest {

    @Test
    public void enabledAndWavelengthChanged_clears() {
        // A real band hop (e.g. 20m -> 40m) -> wipe stale decodes.
        assertThat(MainViewModel.shouldClearOnBandChange(true, "20m", "40m")).isTrue();
    }

    @Test
    public void enabledButSameWavelength_doesNotClear() {
        // Re-selecting the current band must not clear.
        assertThat(MainViewModel.shouldClearOnBandChange(true, "20m", "20m")).isFalse();
    }

    @Test
    public void enabledSameWavelengthDifferentFreq_doesNotClear() {
        // A small in-band dial move keeps the same wavelength even though the
        // band-list index can change (getIndexByFreq appends unseen frequencies).
        // This is the case the index-based check got wrong: it must NOT clear.
        assertThat(MainViewModel.shouldClearOnBandChange(true, "20m", "20m")).isFalse();
    }

    @Test
    public void disabled_neverClears() {
        // Option off: keep the decodes even across a real band change.
        assertThat(MainViewModel.shouldClearOnBandChange(false, "20m", "40m")).isFalse();
        assertThat(MainViewModel.shouldClearOnBandChange(false, "20m", "20m")).isFalse();
    }

    @Test
    public void nullWavelengths_treatedAsUnchanged() {
        // Out-of-band frequencies can map to a null/empty wavelength; two such
        // reads compare equal (Objects.equals), so they don't spuriously clear.
        assertThat(MainViewModel.shouldClearOnBandChange(true, null, null)).isFalse();
    }
}
