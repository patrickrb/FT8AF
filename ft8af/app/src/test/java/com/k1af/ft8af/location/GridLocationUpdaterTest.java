package com.k1af.ft8af.location;

import static com.google.common.truth.Truth.assertThat;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import com.k1af.ft8af.GeneralVariables;
import com.k1af.ft8af.maidenhead.MaidenheadGrid;
import com.google.android.gms.maps.model.LatLng;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

/**
 * Covers {@link GridLocationUpdater#gridUpdateFor} — the GPS-grid update
 * decision extracted from {@code applyGridFromLatLng} (issue #59) — plus the
 * toggle/permission gating the class wires into {@link LocationSubscriber}
 * (issue #380). Robolectric is required because the method reaches
 * Play-Services {@link LatLng} via {@link MaidenheadGrid#getGridSquare}.
 */
@RunWith(RobolectricTestRunner.class)
public class GridLocationUpdaterTest {

    // Boston, MA. MaidenheadGridTest cross-checks that this maps to FN42.
    private static final double BOSTON_LAT = 42.5;
    private static final double BOSTON_LON = -71.0;

    @Test
    public void gridUpdateFor_returnsNewGrid_whenDifferentFromCurrent() {
        // Driving into FN42 while the stored grid is something else => update to FN42.
        String result = GridLocationUpdater.gridUpdateFor(BOSTON_LAT, BOSTON_LON, "AA00");
        assertThat(result).isEqualTo("FN42");
    }

    @Test
    public void gridUpdateFor_returnsNull_whenGridUnchanged() {
        // Already in FN42 => no write, so a fresh fix in the same square is a no-op.
        String result = GridLocationUpdater.gridUpdateFor(BOSTON_LAT, BOSTON_LON, "FN42");
        assertThat(result).isNull();
    }

    @Test
    public void gridUpdateFor_returnsNull_whenCurrentMatchesComputed() {
        // The decision must match exactly what getGridSquare would produce for the
        // fix — guards against the "stored 6-char vs computed 4-char" mismatch.
        String computed = MaidenheadGrid.getGridSquare(new LatLng(BOSTON_LAT, BOSTON_LON));
        assertThat(GridLocationUpdater.gridUpdateFor(BOSTON_LAT, BOSTON_LON, computed)).isNull();
    }

    @Test
    public void gridUpdateFor_detectsGridBoundaryCrossing() {
        // Simulate a vehicle crossing from one grid square into a far-away one:
        // the transmitted grid must follow GPS. Boston (FN42) -> central Europe.
        double euLat = 48.5;
        double euLon = 9.0;
        String startGrid = MaidenheadGrid.getGridSquare(new LatLng(BOSTON_LAT, BOSTON_LON));
        String endGrid = MaidenheadGrid.getGridSquare(new LatLng(euLat, euLon));

        // Sanity: the two locations really are in different grids.
        assertThat(endGrid).isNotEqualTo(startGrid);

        // Standing still in the start square => no change.
        assertThat(GridLocationUpdater.gridUpdateFor(BOSTON_LAT, BOSTON_LON, startGrid)).isNull();
        // After the boundary crossing => update to the new square.
        assertThat(GridLocationUpdater.gridUpdateFor(euLat, euLon, startGrid)).isEqualTo(endGrid);
    }

    // ---- LocationSubscriber wiring (issue #380): toggle + permission gates ----

    @Test
    public void refresh_toggleOff_leavesUpdaterStopped() {
        Context ctx = ApplicationProvider.getApplicationContext();
        GeneralVariables.autoUpdateGridFromGPS = false;
        GridLocationUpdater.refresh(ctx, null);
        assertThat(GridLocationUpdater.getInstance(ctx).isRunning()).isFalse();
    }

    @Test
    public void refresh_toggleOn_withoutLocationPermission_doesNotStart() {
        // Robolectric grants no runtime permissions by default, so the dual
        // FINE/COARSE check must veto the start.
        Context ctx = ApplicationProvider.getApplicationContext();
        GeneralVariables.autoUpdateGridFromGPS = true;
        try {
            GridLocationUpdater.refresh(ctx, null);
            assertThat(GridLocationUpdater.getInstance(ctx).isRunning()).isFalse();
        } finally {
            GeneralVariables.autoUpdateGridFromGPS = false;
        }
    }
}
