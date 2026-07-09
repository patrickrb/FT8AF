package com.k1af.ft8af.database;

import static com.google.common.truth.Truth.assertThat;

import androidx.test.core.app.ApplicationProvider;

import com.k1af.ft8af.GeneralVariables;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * End-to-end hydration test for the four config keys that were parsed with a
 * raw, unguarded {@code Integer.parseInt(result)}: {@code audioRate},
 * {@code dataBits}, {@code stopBits} and {@code parityBits}.
 *
 * <p>The settings backup/restore feature (issue #357 / PR #382) writes every
 * imported config value into the {@code config} table verbatim, then re-runs
 * config hydration ({@link DatabaseOpr.GetAllConfigParameter}) both on import
 * and on every subsequent app launch. A hand-edited or cross-version backup can
 * therefore carry an empty or non-numeric value for one of these keys, and
 * before the fix that threw {@link NumberFormatException} out of
 * {@code doInBackground} on the AsyncTask worker thread — an uncaught crash that
 * recurred on every relaunch (a persistent brick) until the app's data was
 * cleared. After the fix each key falls back to its documented default.
 *
 * <p>The hydration logic lives in an AsyncTask; we drive its
 * {@code doInBackground} synchronously (same package) so the assertions are
 * deterministic and any thrown exception fails the test directly rather than
 * being swallowed by the executor.
 */
@RunWith(RobolectricTestRunner.class)
public class DatabaseOprConfigHydrationTest {

    private DatabaseOpr opr;

    @Before
    public void setUp() {
        opr = new DatabaseOpr(ApplicationProvider.getApplicationContext(), null, null, 18);
    }

    @After
    public void tearDown() {
        opr.close();
    }

    private void hydrate() {
        // Invoke the real hydration path synchronously on the test thread.
        new DatabaseOpr.GetAllConfigParameter(opr.getWritableDatabase(), null)
                .doInBackground();
    }

    @Test
    public void malformedSerialAndAudioRate_doNotCrashAndResetToDefaults() {
        // Pre-set the fields to non-default sentinels so we can prove the bad
        // value forces a reset to the documented default (not just "unchanged").
        GeneralVariables.audioSampleRate = 99999;
        GeneralVariables.serialDataBits = 99;
        GeneralVariables.serialStopBits = 99;
        GeneralVariables.serialParity = 99;

        Map<String, String> config = new LinkedHashMap<>();
        config.put("audioRate", "");      // empty (the case its guarded siblings tolerated)
        config.put("dataBits", "eight");  // non-numeric garbage
        config.put("stopBits", "");       // empty
        config.put("parityBits", "none"); // non-numeric garbage
        opr.writeConfigSync(config);

        hydrate(); // must not throw

        assertThat(GeneralVariables.audioSampleRate).isEqualTo(12000);
        assertThat(GeneralVariables.serialDataBits).isEqualTo(8);
        assertThat(GeneralVariables.serialStopBits).isEqualTo(1);
        assertThat(GeneralVariables.serialParity).isEqualTo(0);
    }

    @Test
    public void validSerialAndAudioRate_areStillHonored() {
        GeneralVariables.audioSampleRate = 0;
        GeneralVariables.serialDataBits = 0;
        GeneralVariables.serialStopBits = 0;
        GeneralVariables.serialParity = 0;

        Map<String, String> config = new LinkedHashMap<>();
        config.put("audioRate", "48000");
        config.put("dataBits", "7");
        config.put("stopBits", "2");
        config.put("parityBits", "1");
        opr.writeConfigSync(config);

        hydrate();

        assertThat(GeneralVariables.audioSampleRate).isEqualTo(48000);
        assertThat(GeneralVariables.serialDataBits).isEqualTo(7);
        assertThat(GeneralVariables.serialStopBits).isEqualTo(2);
        assertThat(GeneralVariables.serialParity).isEqualTo(1);
    }
}
