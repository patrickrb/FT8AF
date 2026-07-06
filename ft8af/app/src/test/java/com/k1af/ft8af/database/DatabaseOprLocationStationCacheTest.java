package com.k1af.ft8af.database;

import static com.google.common.truth.Truth.assertThat;

import androidx.test.core.app.ApplicationProvider;

import com.k1af.ft8af.log.LocationStationCacheEntry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.List;

/**
 * Round-trip tests for the per-location Wavelog station cache DAO (issue #437):
 * {@code putStationForSignature} / {@code getStationForSignature} /
 * {@code getAllStationSignatures} / {@code clearLocationStationCache}. Uses a
 * Robolectric in-memory database (name=null) so no on-disk state is touched.
 */
@RunWith(RobolectricTestRunner.class)
public class DatabaseOprLocationStationCacheTest {

    private DatabaseOpr opr;

    @Before
    public void setUp() {
        opr = new DatabaseOpr(ApplicationProvider.getApplicationContext(), null, null, 19);
    }

    @After
    public void tearDown() {
        opr.close();
    }

    @Test
    public void putThenGet_roundTrips() {
        opr.putStationForSignature("GRID=FN42", "7");
        assertThat(opr.getStationForSignature("GRID=FN42")).isEqualTo("7");
    }

    @Test
    public void get_returnsNullForUnknownSignature() {
        assertThat(opr.getStationForSignature("GRID=EN61")).isNull();
    }

    @Test
    public void put_upsertsExistingSignatureInsteadOfDuplicating() {
        opr.putStationForSignature("GRID=FN42", "7");
        opr.putStationForSignature("GRID=FN42", "8");
        assertThat(opr.getStationForSignature("GRID=FN42")).isEqualTo("8");
        assertThat(opr.getAllStationSignatures()).hasSize(1);
    }

    @Test
    public void getAll_returnsAllEntriesSortedBySignature() {
        opr.putStationForSignature("GRID=ZZ99", "3");
        opr.putStationForSignature("GRID=AA00", "1");
        List<LocationStationCacheEntry> all = opr.getAllStationSignatures();
        assertThat(all).hasSize(2);
        assertThat(all.get(0).signature).isEqualTo("GRID=AA00");
        assertThat(all.get(0).stationProfileId).isEqualTo("1");
        assertThat(all.get(1).signature).isEqualTo("GRID=ZZ99");
    }

    @Test
    public void clear_removesEverything() {
        opr.putStationForSignature("GRID=FN42", "7");
        opr.clearLocationStationCache();
        assertThat(opr.getStationForSignature("GRID=FN42")).isNull();
        assertThat(opr.getAllStationSignatures()).isEmpty();
    }

    @Test
    public void put_ignoresNullArguments() {
        opr.putStationForSignature(null, "7");
        opr.putStationForSignature("GRID=FN42", null);
        assertThat(opr.getAllStationSignatures()).isEmpty();
    }

    @Test
    public void emptySignatureKey_roundTrips() {
        // The all-disabled/no-POTA signature is the empty string; it must still persist.
        opr.putStationForSignature("", "5");
        assertThat(opr.getStationForSignature("")).isEqualTo("5");
    }
}
