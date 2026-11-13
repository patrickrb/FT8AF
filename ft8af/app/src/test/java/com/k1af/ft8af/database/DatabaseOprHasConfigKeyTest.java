package com.k1af.ft8af.database;

import static com.google.common.truth.Truth.assertThat;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@link DatabaseOpr#hasConfigKey} distinguishes "the operator explicitly set
 * this" from "this still holds its hardcoded default, nobody's touched it" --
 * a plain read of the hydrated {@code GeneralVariables} field can't tell the
 * two apart, since an explicit choice that happens to match the default looks
 * identical to never having chosen at all. Used to decide whether it's safe
 * to apply a rig-specific default (e.g. the QMX's TX audio channel) without
 * overriding a choice the operator already made themselves.
 */
@RunWith(RobolectricTestRunner.class)
public class DatabaseOprHasConfigKeyTest {

    @Test
    public void absentKey_returnsFalse() {
        DatabaseOpr opr = new DatabaseOpr(ApplicationProvider.getApplicationContext(), null, null,
                DatabaseOpr.SCHEMA_VERSION);
        assertThat(opr.hasConfigKey("neverWrittenKey")).isFalse();
    }

    @Test
    public void writtenKey_returnsTrue_evenIfTheValueMatchesTheDefault() {
        DatabaseOpr opr = new DatabaseOpr(ApplicationProvider.getApplicationContext(), null, null,
                DatabaseOpr.SCHEMA_VERSION);
        Map<String, String> config = new LinkedHashMap<>();
        // "0" is AudioChannelSelect.BOTH, the same value the field already holds
        // before anyone writes anything -- the row's mere existence must still
        // count as an explicit choice, not be indistinguishable from absence.
        config.put("txAudioChannel", "0");
        opr.writeConfigSync(config);

        assertThat(opr.hasConfigKey("txAudioChannel")).isTrue();
    }
}
