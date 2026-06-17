package com.k1af.ft8af;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;

/**
 * Unit tests for {@link MainViewModel#decodingMarkerAfterPass(int, int)}.
 *
 * <p>beforeListen() turns the spectrum-display "decoding" marker on at the start of
 * every cycle, so afterDecode() must turn it back off once a decode pass finishes —
 * regardless of how the pass exits. Previously the silent-slot path (zero raw
 * decodes) returned early without clearing the flag, leaving the marker stuck on
 * until a later non-empty cycle. These tests lock the invariant that every pass
 * outcome ends the decoding state.
 */
public class DecodingMarkerTest {

    @Test
    public void silentSlot_clearsMarker() {
        // zero raw decodes — the path that previously left the marker stuck on
        assertThat(MainViewModel.decodingMarkerAfterPass(0, 0)).isFalse();
    }

    @Test
    public void allOwnEchoes_clearsMarker() {
        // decodes arrived but all were filtered out as own-TX loopback echoes
        assertThat(MainViewModel.decodingMarkerAfterPass(3, 0)).isFalse();
    }

    @Test
    public void normalSlot_clearsMarker() {
        // decodes arrived and some survived filtering
        assertThat(MainViewModel.decodingMarkerAfterPass(3, 2)).isFalse();
    }
}
