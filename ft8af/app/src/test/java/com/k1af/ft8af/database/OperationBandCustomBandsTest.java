package com.k1af.ft8af.database;

import static com.google.common.truth.Truth.assertThat;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import com.k1af.ft8af.FT8Common;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.List;

/**
 * Persistence coverage for custom dial frequencies (issue #470). Uses Robolectric
 * because {@link OperationBand#addCustomBand}, {@link OperationBand#loadCustomBands}
 * and {@link OperationBand#removeCustomBand} reach SharedPreferences and the app's
 * bands.txt asset — Android types that need a real {@link Context}. The pure
 * parse/validate/merge helpers are covered without a runner in
 * {@link OperationBandTest}.
 */
@RunWith(RobolectricTestRunner.class)
public class OperationBandCustomBandsTest {

    private Context context;
    private OperationBand ob;

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        // Start from a clean slate: no persisted custom bands, empty band list.
        context.getSharedPreferences("ft8af_custom_bands", Context.MODE_PRIVATE)
                .edit().clear().commit();
        OperationBand.bandList.clear();
        ob = new OperationBand(context);
    }

    private static boolean containsFreq(List<OperationBand.Band> bands, long freq) {
        for (OperationBand.Band b : bands) {
            if (b.band == freq) return true;
        }
        return false;
    }

    @Test
    public void addCustomBand_persistsAndMergesIntoBandList() {
        String err = ob.addCustomBand("14090000", "3B9 DX", FT8Common.FT8_MODE);
        assertThat(err).isNull();

        List<OperationBand.Band> customs = ob.loadCustomBands();
        assertThat(customs).hasSize(1);
        assertThat(customs.get(0).band).isEqualTo(14_090_000L);
        assertThat(customs.get(0).waveLength).isEqualTo("3B9 DX");
        assertThat(customs.get(0).custom).isTrue();

        // Merged into the shared bandList the pickers render.
        assertThat(containsFreq(OperationBand.bandList, 14_090_000L)).isTrue();
    }

    @Test
    public void customBand_survivesRestart() {
        ob.addCustomBand("14090000", "3B9 DX", FT8Common.FT8_MODE);

        // Simulate an app restart: a brand-new instance reloads from prefs.
        OperationBand.bandList.clear();
        OperationBand fresh = new OperationBand(context);
        assertThat(fresh.loadCustomBands()).hasSize(1);
        assertThat(containsFreq(OperationBand.bandList, 14_090_000L)).isTrue();
    }

    @Test
    public void addCustomBand_blankLabel_fallsBackToBandName() {
        ob.addCustomBand("14090000", "", FT8Common.FT8_MODE);
        assertThat(ob.loadCustomBands().get(0).waveLength).isEqualTo("20m");
    }

    @Test
    public void addCustomBand_sameFreqAndMode_relabelsInPlace() {
        ob.addCustomBand("14090000", "DX", FT8Common.FT8_MODE);
        ob.addCustomBand("14090000", "3B9 DX", FT8Common.FT8_MODE);
        List<OperationBand.Band> customs = ob.loadCustomBands();
        assertThat(customs).hasSize(1);
        assertThat(customs.get(0).waveLength).isEqualTo("3B9 DX");
    }

    @Test
    public void addCustomBand_nonNumeric_isRejectedAndNotPersisted() {
        String err = ob.addCustomBand("not-a-number", "x", FT8Common.FT8_MODE);
        assertThat(err).isNotNull();
        assertThat(ob.loadCustomBands()).isEmpty();
    }

    @Test
    public void addCustomBand_outOfRange_isRejected() {
        String err = ob.addCustomBand("500", "x", FT8Common.FT8_MODE); // below MIN_CUSTOM_FREQ
        assertThat(err).isNotNull();
        assertThat(ob.loadCustomBands()).isEmpty();
    }

    @Test
    public void removeCustomBand_deletesFromStoreAndBandList() {
        // Use an off-plan dial so the "absent after removal" check isn't masked by a
        // same-frequency built-in entry from bands.txt.
        ob.addCustomBand("14085500", "DX", FT8Common.FT8_MODE);
        assertThat(ob.removeCustomBand(14_085_500L, FT8Common.FT8_MODE)).isTrue();
        assertThat(ob.loadCustomBands()).isEmpty();
        assertThat(containsFreq(OperationBand.bandList, 14_085_500L)).isFalse();
    }

    @Test
    public void removeCustomBand_missing_returnsFalse() {
        assertThat(ob.removeCustomBand(99_999_999L, FT8Common.FT8_MODE)).isFalse();
    }

    @Test
    public void editCustomBand_outOfRange_keepsOriginalEntry() {
        ob.addCustomBand("14090000", "3B9 DX", FT8Common.FT8_MODE);
        // Edit to an out-of-range frequency: must fail *and* leave the original intact.
        String err = ob.editCustomBand(14_090_000L, FT8Common.FT8_MODE, "500", "3B9 DX",
                FT8Common.FT8_MODE);
        assertThat(err).isNotNull();
        List<OperationBand.Band> customs = ob.loadCustomBands();
        assertThat(customs).hasSize(1);
        assertThat(customs.get(0).band).isEqualTo(14_090_000L);
        assertThat(containsFreq(OperationBand.bandList, 14_090_000L)).isTrue();
    }

    @Test
    public void editCustomBand_validFreqChange_movesEntry() {
        // Off-plan dials so neither the old nor the new freq collides with a built-in.
        ob.addCustomBand("14085500", "3B9 DX", FT8Common.FT8_MODE);
        String err = ob.editCustomBand(14_085_500L, FT8Common.FT8_MODE, "14086500", "3B9 DX",
                FT8Common.FT8_MODE);
        assertThat(err).isNull();
        List<OperationBand.Band> customs = ob.loadCustomBands();
        assertThat(customs).hasSize(1);
        assertThat(customs.get(0).band).isEqualTo(14_086_500L);
        assertThat(containsFreq(OperationBand.bandList, 14_085_500L)).isFalse();
        assertThat(containsFreq(OperationBand.bandList, 14_086_500L)).isTrue();
    }

    @Test
    public void editCustomBand_labelOnly_relabelsInPlace() {
        ob.addCustomBand("14090000", "old", FT8Common.FT8_MODE);
        String err = ob.editCustomBand(14_090_000L, FT8Common.FT8_MODE, "14090000", "new",
                FT8Common.FT8_MODE);
        assertThat(err).isNull();
        List<OperationBand.Band> customs = ob.loadCustomBands();
        assertThat(customs).hasSize(1);
        assertThat(customs.get(0).waveLength).isEqualTo("new");
    }

    @Test
    public void addCustomBand_labelWithNewline_isSanitizedAndRoundTripsAsOneLine() {
        // A pasted multi-line label must not split the stored blob into extra lines.
        String err = ob.addCustomBand("14090000", "foo\nbar\r:baz", FT8Common.FT8_MODE);
        assertThat(err).isNull();
        List<OperationBand.Band> customs = ob.loadCustomBands();
        assertThat(customs).hasSize(1);
        String label = customs.get(0).waveLength;
        assertThat(label).doesNotContain("\n");
        assertThat(label).doesNotContain("\r");
        assertThat(label).doesNotContain(":");
    }
}
