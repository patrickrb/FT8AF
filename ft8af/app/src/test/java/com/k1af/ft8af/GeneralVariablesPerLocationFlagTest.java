package com.k1af.ft8af;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

/**
 * Guards the dark-launch invariant for the per-location Wavelog station feature
 * (issue #437): the {@code perLocationStationEnabled} flag must default to false so
 * this increment ships fully disabled and cannot alter live QSO upload behavior.
 * Robolectric because {@link GeneralVariables} carries Android LiveData statics.
 */
@RunWith(RobolectricTestRunner.class)
public class GeneralVariablesPerLocationFlagTest {

    @Test
    public void perLocationStationEnabled_defaultsFalse() {
        assertThat(GeneralVariables.perLocationStationEnabled).isFalse();
    }
}
