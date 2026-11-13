package com.k1af.ft8af.count;

import static com.google.common.truth.Truth.assertThat;

import com.k1af.ft8af.maidenhead.MaidenheadGrid;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.Arrays;
import java.util.Collections;

/**
 * Exercise {@link CountDbOpr#computeDistanceStats} — the distance-statistics
 * min/max scan behind the "Distance statistics" report.
 *
 * The regression under test: {@link MaidenheadGrid#getDist(String, String)}
 * returns 0 for any gridsquare that does not parse as a Maidenhead locator
 * (wrong length, or an end-of-QSO token such as "RR73" that operators commonly
 * leak into the GRIDSQUARE field of an imported log). The old scan compared
 * that spurious 0 with {@code <}, so a single contaminated log row collapsed the
 * "Nearest distance" statistic to 0 km attributed to the junk grid. The fix
 * skips unparseable grids while still retaining a genuine same-grid 0 km contact.
 *
 * Robolectric is required because the production math builds Play Services
 * {@code LatLng} objects.
 */
@RunWith(RobolectricTestRunner.class)
public class CountDbOprDistanceStatsTest {

    private static final String MY_GRID = "IO91"; // London-ish
    private static final double TOL = 1e-6;

    @Test
    public void invalidGrid_isSkipped_notCountedAsZeroKm() {
        double fn42 = MaidenheadGrid.getDist("FN42", MY_GRID);
        double jn47 = MaidenheadGrid.getDist("JN47", MY_GRID);
        double expectedMin = Math.min(fn42, jn47);
        double expectedMax = Math.max(fn42, jn47);

        // "RR73" is a valid-length string that gridToLatLng rejects -> getDist == 0.
        CountDbOpr.DistanceStats stats =
                CountDbOpr.computeDistanceStats(Arrays.asList("FN42", "RR73", "JN47"), MY_GRID);

        assertThat(stats.hasData()).isTrue();
        // The nearest distance must be a real contact, never the spurious 0 from RR73.
        assertThat(stats.minKm).isGreaterThan(0.0);
        assertThat(stats.minKm).isWithin(TOL).of(expectedMin);
        assertThat(stats.maxKm).isWithin(TOL).of(expectedMax);
        assertThat(stats.minGrid).isNotEqualTo("RR73");
        assertThat(stats.maxGrid).isNotEqualTo("RR73");
    }

    @Test
    public void wrongLengthGrid_isSkipped() {
        double fn42 = MaidenheadGrid.getDist("FN42", MY_GRID);

        // "ABC" (length 3) is not a valid locator length -> skipped.
        CountDbOpr.DistanceStats stats =
                CountDbOpr.computeDistanceStats(Arrays.asList("ABC", "FN42"), MY_GRID);

        assertThat(stats.hasData()).isTrue();
        assertThat(stats.minGrid).isEqualTo("FN42");
        assertThat(stats.maxGrid).isEqualTo("FN42");
        assertThat(stats.minKm).isWithin(TOL).of(fn42);
    }

    @Test
    public void sameGridContact_isRetainedAsZeroKm() {
        // A genuine same-grid contact is 0 km and must remain the minimum, not
        // be over-eagerly discarded along with the invalid grids.
        CountDbOpr.DistanceStats stats =
                CountDbOpr.computeDistanceStats(Collections.singletonList(MY_GRID), MY_GRID);

        assertThat(stats.hasData()).isTrue();
        assertThat(stats.minKm).isWithin(TOL).of(0.0);
        assertThat(stats.minGrid).isEqualTo(MY_GRID);
    }

    @Test
    public void myGridInvalid_returnsEmptyStats() {
        CountDbOpr.DistanceStats stats =
                CountDbOpr.computeDistanceStats(Arrays.asList("FN42", "JN47"), "");

        assertThat(stats.hasData()).isFalse();
        assertThat(stats.minGrid).isEmpty();
        assertThat(stats.maxGrid).isEmpty();
    }

    @Test
    public void allGridsInvalid_returnsEmptyStats() {
        CountDbOpr.DistanceStats stats =
                CountDbOpr.computeDistanceStats(Arrays.asList("RR73", "RR", "ABCDE"), MY_GRID);

        assertThat(stats.hasData()).isFalse();
    }

    @Test
    public void merge_keepsGlobalExtremesAcrossBands() {
        CountDbOpr.DistanceStats bandA =
                CountDbOpr.computeDistanceStats(Collections.singletonList("FN42"), MY_GRID);
        CountDbOpr.DistanceStats bandB =
                CountDbOpr.computeDistanceStats(Collections.singletonList("JN47"), MY_GRID);

        CountDbOpr.DistanceStats global = new CountDbOpr.DistanceStats();
        global.merge(bandA);
        global.merge(bandB);

        double fn42 = MaidenheadGrid.getDist("FN42", MY_GRID);
        double jn47 = MaidenheadGrid.getDist("JN47", MY_GRID);
        assertThat(global.maxKm).isWithin(TOL).of(Math.max(fn42, jn47));
        assertThat(global.minKm).isWithin(TOL).of(Math.min(fn42, jn47));
    }
}
