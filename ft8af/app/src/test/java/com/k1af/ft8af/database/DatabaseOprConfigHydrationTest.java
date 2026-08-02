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

    private int origAudioSampleRate;
    private int origSerialDataBits;
    private int origSerialStopBits;
    private int origSerialParity;

    private int origCivAddress;
    private int origBaudRate;
    private long origBand;
    private int origBandListIndex;
    private int origIcomUdpPort;
    private float origVolumePercent;
    private int origAlcTargetLow;
    private boolean origDeepDecodeMode;
    private boolean origKeepScreenOn;
    private boolean origAutoSyncClockFromDecodes;

    @Before
    public void setUp() {
        origAudioSampleRate = GeneralVariables.audioSampleRate;
        origSerialDataBits = GeneralVariables.serialDataBits;
        origSerialStopBits = GeneralVariables.serialStopBits;
        origSerialParity = GeneralVariables.serialParity;

        origCivAddress = GeneralVariables.civAddress;
        origBaudRate = GeneralVariables.baudRate;
        origBand = GeneralVariables.band;
        origBandListIndex = GeneralVariables.bandListIndex;
        origIcomUdpPort = GeneralVariables.icomUdpPort;
        origVolumePercent = GeneralVariables.volumePercent;
        origAlcTargetLow = GeneralVariables.alcTargetLow;
        origDeepDecodeMode = GeneralVariables.deepDecodeMode;
        origKeepScreenOn = GeneralVariables.keepScreenOn;
        origAutoSyncClockFromDecodes = GeneralVariables.autoSyncClockFromDecodes;

        opr = new DatabaseOpr(ApplicationProvider.getApplicationContext(), null, null, 18);
    }

    @After
    public void tearDown() {
        try {
            opr.close();
        } finally {
            GeneralVariables.audioSampleRate = origAudioSampleRate;
            GeneralVariables.serialDataBits = origSerialDataBits;
            GeneralVariables.serialStopBits = origSerialStopBits;
            GeneralVariables.serialParity = origSerialParity;

            GeneralVariables.civAddress = origCivAddress;
            GeneralVariables.baudRate = origBaudRate;
            GeneralVariables.band = origBand;
            GeneralVariables.bandListIndex = origBandListIndex;
            GeneralVariables.icomUdpPort = origIcomUdpPort;
            GeneralVariables.volumePercent = origVolumePercent;
            GeneralVariables.alcTargetLow = origAlcTargetLow;
            GeneralVariables.deepDecodeMode = origDeepDecodeMode;
            GeneralVariables.keepScreenOn = origKeepScreenOn;
            GeneralVariables.autoSyncClockFromDecodes = origAutoSyncClockFromDecodes;
        }
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

    /**
     * The remaining hydration keys were {@code result.equals("") ? DEFAULT :
     * parse(result)} — empty-guarded, so they survived an empty value like their
     * fixed siblings, but a non-empty <em>non-numeric</em> value (which an
     * imported or hand-edited backup can carry) still threw out of hydration and
     * bricked the app. This covers a representative slice across every numeric
     * type used: hex int (civ), decimal int (baudRate, icomPort, alcTargetLow),
     * long (bandFreq) and float (volumeValue).
     */
    @Test
    public void malformedRemainingNumericKeys_doNotCrashAndResetToDefaults() {
        GeneralVariables.civAddress = 0x11;
        GeneralVariables.baudRate = 1;
        GeneralVariables.band = 1;
        GeneralVariables.icomUdpPort = 1;
        GeneralVariables.volumePercent = 9f;
        GeneralVariables.alcTargetLow = 1;

        Map<String, String> config = new LinkedHashMap<>();
        config.put("civ", "zz");            // non-hex
        config.put("baudRate", "fast");     // non-numeric
        config.put("bandFreq", "lots");     // non-numeric
        config.put("icomPort", "port");     // non-numeric
        config.put("volumeValue", "loud");  // non-numeric
        config.put("alcTargetLow", "lo");   // non-numeric
        opr.writeConfigSync(config);

        hydrate(); // must not throw

        assertThat(GeneralVariables.civAddress).isEqualTo(0xa4);
        assertThat(GeneralVariables.baudRate).isEqualTo(19200);
        assertThat(GeneralVariables.band).isEqualTo(14074000L);
        assertThat(GeneralVariables.icomUdpPort).isEqualTo(50001);
        assertThat(GeneralVariables.volumePercent).isEqualTo(1.0f);
        assertThat(GeneralVariables.alcTargetLow).isEqualTo(60);
    }

    @Test
    public void validRemainingNumericKeys_areStillHonored() {
        Map<String, String> config = new LinkedHashMap<>();
        config.put("civ", "5e");            // hex -> 0x5e
        config.put("baudRate", "9600");
        config.put("bandFreq", "7074000");  // long
        config.put("icomPort", "50002");
        config.put("volumeValue", "50");    // -> 0.5f (scaled /100)
        config.put("alcTargetLow", "70");
        opr.writeConfigSync(config);

        hydrate();

        assertThat(GeneralVariables.civAddress).isEqualTo(0x5e);
        assertThat(GeneralVariables.baudRate).isEqualTo(9600);
        assertThat(GeneralVariables.band).isEqualTo(7074000L);
        assertThat(GeneralVariables.icomUdpPort).isEqualTo(50002);
        assertThat(GeneralVariables.volumePercent).isEqualTo(0.5f);
        assertThat(GeneralVariables.alcTargetLow).isEqualTo(70);
    }

    /**
     * A raw SQL NULL for {@code udp_port} makes {@code cursor.getString(...)} return null.
     * Its sibling {@code udp_host} already null-checks, but {@code udp_port} used to call
     * {@code result.trim()} unguarded — an NPE that bricked config hydration for any backup
     * carrying a null value. The guard leaves the current value untouched instead.
     */
    @Test
    public void nullUdpPort_doesNotCrashHydration() {
        int orig = GeneralVariables.udpPort;
        try {
            GeneralVariables.udpPort = 2237;
            // writeConfigSync coerces null -> "", so insert a genuine SQL NULL directly.
            opr.getWritableDatabase().execSQL("DELETE FROM config WHERE KeyName='udp_port'");
            opr.getWritableDatabase()
                    .execSQL("INSERT INTO config (KeyName,Value) VALUES ('udp_port', NULL)");

            hydrate(); // pre-fix: NullPointerException on result.trim()

            assertThat(GeneralVariables.udpPort).isEqualTo(2237); // unchanged, no crash
        } finally {
            GeneralVariables.udpPort = orig;
        }
    }

    // ---- power & heat toggles ------------------------------------------------
    // Both are surfaced in the Compose settings for the first time. deepMode
    // reuses the key the retired legacy fragment wrote, so an existing preference
    // must still hydrate; keepScreenOn is new and must default to the previous
    // hard-coded behaviour (on) when absent.

    @Test
    public void powerToggles_hydrateFromStoredValues() {
        GeneralVariables.deepDecodeMode = true;
        GeneralVariables.keepScreenOn = true;

        Map<String, String> config = new LinkedHashMap<>();
        config.put("deepMode", "0");
        config.put("keepScreenOn", "0");
        opr.writeConfigSync(config);

        hydrate();

        assertThat(GeneralVariables.deepDecodeMode).isFalse();
        assertThat(GeneralVariables.keepScreenOn).isFalse();
    }

    @Test
    public void powerToggles_hydrateBackOn() {
        GeneralVariables.deepDecodeMode = false;
        GeneralVariables.keepScreenOn = false;

        Map<String, String> config = new LinkedHashMap<>();
        config.put("deepMode", "1");
        config.put("keepScreenOn", "1");
        opr.writeConfigSync(config);

        hydrate();

        assertThat(GeneralVariables.deepDecodeMode).isTrue();
        assertThat(GeneralVariables.keepScreenOn).isTrue();
    }

    @Test
    public void keepScreenOn_absentFromConfigKeepsThePreviousBehaviour() {
        // An install that predates the setting has no row; the screen must keep
        // behaving as it did when the flag was hard-coded on.
        GeneralVariables.keepScreenOn = true;

        Map<String, String> config = new LinkedHashMap<>();
        config.put("deepMode", "1");
        opr.writeConfigSync(config);

        hydrate();

        assertThat(GeneralVariables.keepScreenOn).isTrue();
    }

    // ---- self-syncing clock (ClockSelfSync) -----------------------------------
    // New "autoSyncClockFromDecodes" key: the classic bug this guards against is
    // adding the field + writeConfig on toggle but forgetting the hydration arm,
    // which silently reverts the setting to off on every relaunch.

    @Test
    public void autoSyncClockFromDecodes_hydratesOnAndOff() {
        GeneralVariables.autoSyncClockFromDecodes = false;
        Map<String, String> config = new LinkedHashMap<>();
        config.put("autoSyncClockFromDecodes", "1");
        opr.writeConfigSync(config);
        hydrate();
        assertThat(GeneralVariables.autoSyncClockFromDecodes).isTrue();

        GeneralVariables.autoSyncClockFromDecodes = true;
        config.put("autoSyncClockFromDecodes", "0");
        opr.writeConfigSync(config);
        hydrate();
        assertThat(GeneralVariables.autoSyncClockFromDecodes).isFalse();
    }

    @Test
    public void autoSyncClockFromDecodes_absentRowLeavesDefaultOff() {
        GeneralVariables.autoSyncClockFromDecodes = false;
        // No row written for the key at all (fresh install / pre-feature backup).
        hydrate();
        assertThat(GeneralVariables.autoSyncClockFromDecodes).isFalse();
    }

    @Test
    public void powerToggles_nonBooleanValueReadsAsOff() {
        // Hydration compares against "1", so anything else is off rather than a
        // parse crash (the failure mode this test class exists for).
        GeneralVariables.deepDecodeMode = true;
        GeneralVariables.keepScreenOn = true;

        Map<String, String> config = new LinkedHashMap<>();
        config.put("deepMode", "");
        config.put("keepScreenOn", "yes");
        opr.writeConfigSync(config);

        hydrate(); // must not throw

        assertThat(GeneralVariables.deepDecodeMode).isFalse();
        assertThat(GeneralVariables.keepScreenOn).isFalse();
    }
}
