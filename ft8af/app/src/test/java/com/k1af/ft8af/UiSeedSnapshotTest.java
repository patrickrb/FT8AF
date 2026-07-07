package com.k1af.ft8af;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;

import java.util.ArrayList;

/**
 * Tests {@link MainViewModel#uiSeedSnapshot} — the copy that seeds
 * {@code mutableFt8MessageList}. Seeding with the live {@code ft8Messages}
 * instance pins the Compose UI to it permanently: observeAsState compares
 * structurally, and every snapshot later published by
 * {@code publishFt8MessageList()} is content-equal to the live list it was
 * copied from, so the state write is skipped and the old (live) reference is
 * kept. Compose then iterates the list the decode thread mutates —
 * the ConcurrentModificationException in buildQsoLog seen in the field
 * (2026-07-04). The seed must be a copy the decode thread can never touch.
 */
public class UiSeedSnapshotTest {

    @Test
    public void seed_isDistinctInstanceWithSameContent() {
        ArrayList<Ft8Message> live = new ArrayList<>();
        live.add(new Ft8Message("CQ", "K1ABC", "FN42"));

        ArrayList<Ft8Message> seed = MainViewModel.uiSeedSnapshot(live);

        assertThat(seed).isNotSameInstanceAs(live);
        assertThat(seed).containsExactlyElementsIn(live).inOrder();
    }

    @Test
    public void seed_isIsolatedFromDecodeThreadMutation() {
        ArrayList<Ft8Message> live = new ArrayList<>();
        ArrayList<Ft8Message> seed = MainViewModel.uiSeedSnapshot(live);

        live.add(new Ft8Message("CQ", "VE3XY", "EN85"));

        assertThat(seed).isEmpty();
    }
}
