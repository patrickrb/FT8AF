package com.k1af.ft8af.database;

import static com.google.common.truth.Truth.assertThat;

import androidx.test.core.app.ApplicationProvider;

import com.k1af.ft8af.GeneralVariables;
import com.k1af.ft8af.ft8listener.DecodeDepth;

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
    private String origCivAddressStored;
    private boolean origCivAddressFormatKnown;
    private int origBaudRate;
    private long origBand;
    private int origBandListIndex;
    private int origIcomUdpPort;
    private float origVolumePercent;
    private int origAlcTargetLow;
    private int origDecodeDepth;
    private boolean origKeepScreenOn;
    private boolean origAutoSyncClockFromDecodes;

    @Before
    public void setUp() {
        origAudioSampleRate = GeneralVariables.audioSampleRate;
        origSerialDataBits = GeneralVariables.serialDataBits;
        origSerialStopBits = GeneralVariables.serialStopBits;
        origSerialParity = GeneralVariables.serialParity;

        origCivAddress = GeneralVariables.civAddress;
        origCivAddressStored = GeneralVariables.civAddressStored;
        origCivAddressFormatKnown = GeneralVariables.civAddressFormatKnown;
        origBaudRate = GeneralVariables.baudRate;
        origBand = GeneralVariables.band;
        origBandListIndex = GeneralVariables.bandListIndex;
        origIcomUdpPort = GeneralVariables.icomUdpPort;
        origVolumePercent = GeneralVariables.volumePercent;
        origAlcTargetLow = GeneralVariables.alcTargetLow;
        origDecodeDepth = GeneralVariables.decodeDepth;
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
            GeneralVariables.civAddressStored = origCivAddressStored;
            GeneralVariables.civAddressFormatKnown = origCivAddressFormatKnown;
            GeneralVariables.baudRate = origBaudRate;
            GeneralVariables.band = origBand;
            GeneralVariables.bandListIndex = origBandListIndex;
            GeneralVariables.icomUdpPort = origIcomUdpPort;
            GeneralVariables.volumePercent = origVolumePercent;
            GeneralVariables.alcTargetLow = origAlcTargetLow;
            GeneralVariables.decodeDepth = origDecodeDepth;
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
    // keepScreenOn must default to the previous hard-coded behaviour (on) when
    // absent. deepMode is the RETIRED fast/deep boolean key, now a one-time
    // migration into decodeDepth (3-way: FAST/BALANCED/DEEP) — these two tests
    // exercise exactly that migration (no "decodeDepth" row is written).

    @Test
    public void powerToggles_hydrateFromStoredValues() {
        GeneralVariables.decodeDepth = DecodeDepth.DEEP;
        GeneralVariables.keepScreenOn = true;

        Map<String, String> config = new LinkedHashMap<>();
        config.put("deepMode", "0");
        config.put("keepScreenOn", "0");
        opr.writeConfigSync(config);

        hydrate();

        assertThat(GeneralVariables.decodeDepth).isEqualTo(DecodeDepth.FAST);
        assertThat(GeneralVariables.keepScreenOn).isFalse();
    }

    @Test
    public void powerToggles_hydrateBackOn() {
        GeneralVariables.decodeDepth = DecodeDepth.FAST;
        GeneralVariables.keepScreenOn = false;

        Map<String, String> config = new LinkedHashMap<>();
        config.put("deepMode", "1");
        config.put("keepScreenOn", "1");
        opr.writeConfigSync(config);

        hydrate();

        assertThat(GeneralVariables.decodeDepth).isEqualTo(DecodeDepth.DEEP);
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

    // ---- voice-command toggle + its observable mirror -------------------------
    // The main-screen mic button observes mutableVoiceCommandsEnabled (a plain
    // static read there is never recomposed), so hydration must update BOTH the
    // static and the LiveData mirror or a persisted "on" renders no button until
    // the setting is touched.

    @Test
    public void voiceCommandsEnabled_hydrationUpdatesStaticAndLiveDataMirror() {
        boolean origEnabled = GeneralVariables.voiceCommandsEnabled;
        Boolean origMirror = GeneralVariables.mutableVoiceCommandsEnabled.getValue();
        try {
            GeneralVariables.voiceCommandsEnabled = false;
            GeneralVariables.mutableVoiceCommandsEnabled.setValue(false);

            Map<String, String> config = new LinkedHashMap<>();
            config.put("voiceCommandsEnabled", "1");
            opr.writeConfigSync(config);

            hydrate();
            // postValue lands via the main looper; run it so getValue() sees it.
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();

            assertThat(GeneralVariables.voiceCommandsEnabled).isTrue();
            assertThat(GeneralVariables.mutableVoiceCommandsEnabled.getValue()).isTrue();
        } finally {
            GeneralVariables.voiceCommandsEnabled = origEnabled;
            GeneralVariables.mutableVoiceCommandsEnabled.setValue(origMirror);
        }
    }

    @Test
    public void voiceCommandsEnabled_hydrationTurnsMirrorBackOff() {
        boolean origEnabled = GeneralVariables.voiceCommandsEnabled;
        Boolean origMirror = GeneralVariables.mutableVoiceCommandsEnabled.getValue();
        try {
            GeneralVariables.voiceCommandsEnabled = true;
            GeneralVariables.mutableVoiceCommandsEnabled.setValue(true);

            Map<String, String> config = new LinkedHashMap<>();
            config.put("voiceCommandsEnabled", "0");
            opr.writeConfigSync(config);

            hydrate();
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();

            assertThat(GeneralVariables.voiceCommandsEnabled).isFalse();
            assertThat(GeneralVariables.mutableVoiceCommandsEnabled.getValue()).isFalse();
        } finally {
            GeneralVariables.voiceCommandsEnabled = origEnabled;
            GeneralVariables.mutableVoiceCommandsEnabled.setValue(origMirror);
        }
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
    public void autoSyncClockFromDecodes_absentRowLeavesDefaultOn() {
        // The feature is on by default (PR #758). No row written for the key at
        // all (fresh install / pre-feature backup) must keep it on — hydration
        // only acts on rows that exist, never writes a default back as "0".
        GeneralVariables.autoSyncClockFromDecodes = true;
        hydrate();
        assertThat(GeneralVariables.autoSyncClockFromDecodes).isTrue();
    }

    @Test
    public void powerToggles_nonBooleanValueReadsAsOff() {
        // The legacy deepMode migration compares against "1", so anything else
        // is FAST rather than a parse crash (the failure mode this test class
        // exists for).
        GeneralVariables.decodeDepth = DecodeDepth.DEEP;
        GeneralVariables.keepScreenOn = true;

        Map<String, String> config = new LinkedHashMap<>();
        config.put("deepMode", "");
        config.put("keepScreenOn", "yes");
        opr.writeConfigSync(config);

        hydrate(); // must not throw

        assertThat(GeneralVariables.decodeDepth).isEqualTo(DecodeDepth.FAST);
        assertThat(GeneralVariables.keepScreenOn).isFalse();
    }

    @Test
    public void decodeDepth_hydratesDirectlyWhenRowExists() {
        GeneralVariables.decodeDepth = DecodeDepth.FAST;

        Map<String, String> config = new LinkedHashMap<>();
        config.put("decodeDepth", String.valueOf(DecodeDepth.BALANCED));
        opr.writeConfigSync(config);

        hydrate();

        assertThat(GeneralVariables.decodeDepth).isEqualTo(DecodeDepth.BALANCED);
    }

    @Test
    public void decodeDepth_rowTakesPrecedenceOverLegacyDeepModeRow() {
        // An install that has already picked a depth under the new key must not
        // have it clobbered by a stale "deepMode" row that disagrees (e.g. the
        // operator picked BALANCED, which "deepMode" can't even express).
        GeneralVariables.decodeDepth = DecodeDepth.FAST;

        Map<String, String> config = new LinkedHashMap<>();
        config.put("decodeDepth", String.valueOf(DecodeDepth.BALANCED));
        config.put("deepMode", "1"); // would migrate to DEEP if wrongly applied
        opr.writeConfigSync(config);

        hydrate();

        assertThat(GeneralVariables.decodeDepth).isEqualTo(DecodeDepth.BALANCED);
    }

    @Test
    public void decodeDepth_outOfRangeValueClampsToDeep() {
        // A garbled or future-version value must degrade to the most thorough
        // mode, not silently to the least (DecodeDepth.clamp's contract).
        GeneralVariables.decodeDepth = DecodeDepth.FAST;

        Map<String, String> config = new LinkedHashMap<>();
        config.put("decodeDepth", "99");
        opr.writeConfigSync(config);

        hydrate();

        assertThat(GeneralVariables.decodeDepth).isEqualTo(DecodeDepth.DEEP);
    }

    /**
     * Issue #753: the Compose rig picker wrote {@code civ} in DECIMAL for three months
     * ("164" for an IC-705's 0xA4) while hydration reads HEX, so the address came back as
     * 0x164 -> truncated 0x64 and CAT frequency control silently died. Hydration must now
     * recognise the decimal form and land on the real address.
     */
    @Test
    public void decimalCivWrittenByOldPicker_isRepairedOnLoad() {
        GeneralVariables.civAddress = 0x11;
        Map<String, String> config = new LinkedHashMap<>();
        config.put("civ", "164");           // IC-705 0xA4 written as decimal
        opr.writeConfigSync(config);

        hydrate();

        assertThat(GeneralVariables.civAddress).isEqualTo(0xa4);
    }

    @Test
    public void hexCivWithPrefix_isHonored() {
        Map<String, String> config = new LinkedHashMap<>();
        config.put("civ", "0x94");          // IC-7300
        opr.writeConfigSync(config);

        hydrate();

        assertThat(GeneralVariables.civAddress).isEqualTo(0x94);
    }

    /**
     * The #753 repair in {@code MainViewModel.connectRig()} needs the raw stored text (to
     * know whether the on-disk form is canonical) and the {@code civFormat} provenance
     * marker (to know whether to trust it verbatim). Both must come out of hydration.
     */
    @Test
    public void civRawTextAndFormatMarker_areHydrated() {
        GeneralVariables.civAddressStored = "stale";
        GeneralVariables.civAddressFormatKnown = false;
        Map<String, String> config = new LinkedHashMap<>();
        config.put("civ", "164");
        config.put("civFormat", "hex");
        opr.writeConfigSync(config);

        hydrate();

        assertThat(GeneralVariables.civAddressStored).isEqualTo("164");
        assertThat(GeneralVariables.civAddressFormatKnown).isTrue();
    }

    @Test
    public void civFormatMarker_absentOrForeign_isNotTrusted() {
        GeneralVariables.civAddressFormatKnown = true;
        Map<String, String> config = new LinkedHashMap<>();
        config.put("civ", "a4");
        config.put("civFormat", "dec");     // anything but "hex" is not a hex marker
        opr.writeConfigSync(config);

        hydrate();

        assertThat(GeneralVariables.civAddressFormatKnown).isFalse();
    }

    /** "Download QSOs from Wavelog when syncing" (issue #965) survives a restart. */
    @Test
    public void cloudlogDownloadQsos_isHydrated() {
        boolean saved = GeneralVariables.cloudlogDownloadQsos;
        try {
            GeneralVariables.cloudlogDownloadQsos = false;
            Map<String, String> config = new LinkedHashMap<>();
            config.put("cloudlogDownloadQsos", "1");
            opr.writeConfigSync(config);
            hydrate();
            assertThat(GeneralVariables.cloudlogDownloadQsos).isTrue();

            config.put("cloudlogDownloadQsos", "0");
            opr.writeConfigSync(config);
            hydrate();
            assertThat(GeneralVariables.cloudlogDownloadQsos).isFalse();
        } finally {
            GeneralVariables.cloudlogDownloadQsos = saved;
        }
    }
}
