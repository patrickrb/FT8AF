package com.k1af.ft8af.car;

import static com.google.common.truth.Truth.assertThat;

import com.k1af.ft8af.Ft8Message;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.ArrayList;
import java.util.List;

/**
 * Tests for {@link CarRowProjector}. Robolectric because constructing {@link Ft8Message}
 * loads a class that references Play-Services types (LatLng fields), per the repo's testing
 * convention.
 */
@RunWith(RobolectricTestRunner.class)
public class CarRowProjectorTest {

    private static Ft8Message msg(String from, String to, int snr, String grid) {
        Ft8Message m = new Ft8Message(0);
        m.callsignFrom = from;
        m.callsignTo = to;
        m.snr = snr;
        m.maidenGrid = grid;
        return m;
    }

    @Test
    public void project_nullOrEmpty_returnsEmpty() {
        assertThat(CarRowProjector.project(null, 6)).isEmpty();
        assertThat(CarRowProjector.project(new ArrayList<>(), 6)).isEmpty();
    }

    @Test
    public void project_capsToMaxRows_newestFirst() {
        List<Ft8Message> msgs = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            msgs.add(msg("CALL" + i, "CQ", -10, null));
        }
        List<CarRowProjector.CarRow> rows = CarRowProjector.project(msgs, CarRowProjector.MAX_ROWS);
        assertThat(rows).hasSize(6);
        // newest (last appended, CALL7) first
        assertThat(rows.get(0).title).isEqualTo("CQ CALL7");
        assertThat(rows.get(5).title).isEqualTo("CQ CALL2");
    }

    @Test
    public void project_skipsNullElements() {
        List<Ft8Message> msgs = new ArrayList<>();
        msgs.add(msg("K1AF", "CQ", -5, null));
        msgs.add(null);
        assertThat(CarRowProjector.project(msgs, 6)).hasSize(1);
    }

    @Test
    public void rowTitle_cqBroadcast() {
        assertThat(CarRowProjector.rowTitle(msg("W5XYZ", "CQ", -3, "EM10"))).isEqualTo("CQ W5XYZ");
    }

    @Test
    public void rowTitle_directedExchange() {
        assertThat(CarRowProjector.rowTitle(msg("W5XYZ", "K1AF", -3, null)))
                .isEqualTo("W5XYZ → K1AF");
    }

    @Test
    public void rowTitle_neverEmpty() {
        assertThat(CarRowProjector.rowTitle(msg(null, null, -3, null))).isEqualTo("—");
    }

    @Test
    public void snrText_unknownRendersDash() {
        assertThat(CarRowProjector.snrText(msg("A", "CQ", Ft8Message.SNR_UNKNOWN, null)))
                .isEqualTo("—");
        assertThat(CarRowProjector.snrText(msg("A", "CQ", -12, null))).isEqualTo("-12");
    }

    @Test
    public void rowSubtitle_includesGridWhenPresent() {
        assertThat(CarRowProjector.rowSubtitle(msg("A", "CQ", -7, "FN20"))).isEqualTo("SNR -7  FN20");
        assertThat(CarRowProjector.rowSubtitle(msg("A", "CQ", -7, null))).isEqualTo("SNR -7");
    }

    @Test
    public void headerTitle_reflectsTxRxState() {
        assertThat(CarRowProjector.headerTitle(false, "K1AF")).isEqualTo("Receiving");
        assertThat(CarRowProjector.headerTitle(true, "K1AF")).isEqualTo("TX → K1AF");
        assertThat(CarRowProjector.headerTitle(true, null)).isEqualTo("TX");
    }
}
