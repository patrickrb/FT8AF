package com.k1af.ft8af.grid_tracker;

import static com.google.common.truth.Truth.assertThat;

import com.k1af.ft8af.log.QSLRecordStr;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

/**
 * Guards {@link GridOsmMapView#upgradeGridInfo} against a NullPointerException on the
 * UI thread when the underlying overlay cannot be built.
 *
 * <p>{@link GridOsmMapView#addGridPolygon} deliberately returns {@code null} when the
 * map view is not ready, or when the {@code GridPolygon} constructor throws and is
 * swallowed by its own guard ("when log volume is too large, a crash can occur").
 * Both {@code upgradeGridInfo} overloads used to dereference that null immediately
 * ({@code gridPolygon.setSnippet(...)}). They are invoked from {@code drawMessage} on
 * the Activity main thread via a LiveData observer with no surrounding try/catch, so a
 * null overlay turned the swallowed crash into a hard, whole-app crash — exactly the
 * heavy-log condition the {@code addGridPolygon} catch was added to survive.
 *
 * <p>A {@code GridOsmMapView} built with a {@code null} map view makes {@code
 * addGridPolygon} take its early {@code gridMapView == null} return, so this exercises
 * the null-overlay path without instantiating osmdroid. Robolectric because the class
 * pulls in Android/Play-Services types on its classpath.
 */
@RunWith(RobolectricTestRunner.class)
public class GridOsmMapViewUpgradeGridInfoTest {

    @Test
    public void messageOverload_nullOverlay_doesNotThrow_returnsNull() {
        // null map view -> addGridPolygon returns null -> must not deref it.
        GridOsmMapView view = new GridOsmMapView(null, null, null);
        assertThat(view.upgradeGridInfo("AA00", "message", "sub detail")).isNull();
    }

    @Test
    public void recordOverload_nullOverlay_doesNotThrow_returnsNull() {
        GridOsmMapView view = new GridOsmMapView(null, null, null);

        QSLRecordStr record = new QSLRecordStr();
        record.setGridsquare("AA00");
        record.isQSL = true;
        assertThat(view.upgradeGridInfo(record)).isNull();

        record.isQSL = false;
        assertThat(view.upgradeGridInfo(record)).isNull();
    }
}
