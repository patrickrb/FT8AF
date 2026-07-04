package com.k1af.ft8af.ft8transmit;

import static com.google.common.truth.Truth.assertThat;

import com.k1af.ft8af.Ft8Message;

import org.junit.Test;

import java.util.ArrayList;

/**
 * Tests {@link FT8TransmitSignal#snapshotForUi} — the defensive copy behind
 * every {@code mutableFunctions.postValue}. The live {@code functionList} is
 * rebuilt in place (clear() + add()) by the auto-sequencer on background
 * threads; publishing the live instance crashed recomposition with
 * ConcurrentModificationException in TxSelector's iteration (field crash,
 * 2026-07-04). The UI must only ever receive copies that later rebuilds cannot
 * touch, and taking the copy must itself be safe against a concurrent rebuild.
 */
public class FunctionListSnapshotTest {

    private static FunctionOfTransmit fun(int order) {
        return new FunctionOfTransmit(order, new Ft8Message("CQ", "K1ABC", "FN42"), false);
    }

    @Test
    public void snapshot_isDistinctInstanceWithSameContent() {
        ArrayList<FunctionOfTransmit> live = new ArrayList<>();
        live.add(fun(1));
        live.add(fun(2));

        ArrayList<FunctionOfTransmit> snap = FT8TransmitSignal.snapshotForUi(live);

        assertThat(snap).isNotSameInstanceAs(live);
        assertThat(snap).containsExactlyElementsIn(live).inOrder();
    }

    @Test
    public void snapshot_isIsolatedFromLaterRebuild() {
        // The regression: the sequencer clear()+add()s the live list after the
        // UI observed it. The observed value must not change underneath the UI.
        ArrayList<FunctionOfTransmit> live = new ArrayList<>();
        FunctionOfTransmit original = fun(6);
        live.add(original);

        ArrayList<FunctionOfTransmit> snap = FT8TransmitSignal.snapshotForUi(live);
        live.clear();
        live.add(fun(1));
        live.add(fun(2));

        assertThat(snap).containsExactly(original);
    }

    @Test
    public void snapshot_ofEmptyList_isEmpty() {
        assertThat(FT8TransmitSignal.snapshotForUi(new ArrayList<>())).isEmpty();
    }

    @Test
    public void snapshot_isSafeAgainstConcurrentRebuild() throws Exception {
        // Rebuilds hold the list's own monitor (generateFun does the same), so a
        // snapshot taken mid-rebuild must neither throw nor observe a torn list:
        // every snapshot sees a whole number of complete rebuilds — 3 elements.
        final ArrayList<FunctionOfTransmit> live = new ArrayList<>();
        for (int i = 1; i <= 3; i++) {
            live.add(fun(i));
        }
        final int rebuilds = 2000;
        Thread sequencer = new Thread(() -> {
            for (int n = 0; n < rebuilds; n++) {
                synchronized (live) {
                    live.clear();
                    for (int i = 1; i <= 3; i++) {
                        live.add(fun(i));
                    }
                }
            }
        });
        sequencer.start();
        try {
            for (int n = 0; n < rebuilds; n++) {
                ArrayList<FunctionOfTransmit> snap = FT8TransmitSignal.snapshotForUi(live);
                assertThat(snap).hasSize(3);
            }
        } finally {
            sequencer.join(10000);
        }
        assertThat(sequencer.isAlive()).isFalse();
    }
}
