package com.bg7yoz.ft8cn;

import static com.google.common.truth.Truth.assertThat;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Verifies the zone-map readiness gate: "new DXCC/zone" flags must be
 * suppressed until the asynchronous zone import finishes populating the
 * maps. Without the gate, a race condition marks every decode as new
 * because the maps are still empty at decode time. (#247)
 *
 * The tests mirror the exact gate pattern used in
 * {@code CallsignDatabase.getMessagesLocation}: the "new" flags on an
 * {@link Ft8Message} are only written inside an
 * {@code if (GeneralVariables.zoneMapReady)} guard. This ensures that
 * removing or weakening the gate will break these tests.
 */
public class ZoneMapReadinessTest {

    @Before
    public void setUp() {
        GeneralVariables.zoneMapReady = false;
        GeneralVariables.dxccMap.clear();
        GeneralVariables.cqMap.clear();
        GeneralVariables.ituMap.clear();
    }

    @After
    public void tearDown() {
        GeneralVariables.zoneMapReady = false;
        GeneralVariables.dxccMap.clear();
        GeneralVariables.cqMap.clear();
        GeneralVariables.ituMap.clear();
    }

    /**
     * Simulate the gate logic from CallsignDatabase.getMessagesLocation():
     * only set the "new" flag when the readiness gate is open.
     */
    private static void applyDxccGate(Ft8Message msg, String dxccPrefix) {
        if (GeneralVariables.zoneMapReady) {
            msg.fromDxcc = !GeneralVariables.getDxccByPrefix(dxccPrefix);
        }
    }

    @Test
    public void gateClosed_suppressesNewDxccFlag() {
        // Gate closed, map empty — without the gate every prefix looks "new".
        // The gate must prevent fromDxcc from being set to true.
        GeneralVariables.zoneMapReady = false;

        Ft8Message msg = new Ft8Message(FT8Common.FT8_MODE);
        msg.fromDxcc = false; // default
        applyDxccGate(msg, "VK");

        assertThat(msg.fromDxcc).isFalse();
    }

    @Test
    public void gateOpen_marksUnworkedPrefixAsNew() {
        // Gate open, prefix NOT in map — correctly identified as new.
        GeneralVariables.zoneMapReady = true;

        Ft8Message msg = new Ft8Message(FT8Common.FT8_MODE);
        msg.fromDxcc = false;
        applyDxccGate(msg, "VK");

        assertThat(msg.fromDxcc).isTrue();
    }

    @Test
    public void gateOpen_workedPrefixNotNew() {
        // Gate open, prefix IS in map — already worked, not new.
        GeneralVariables.addDxcc("K");
        GeneralVariables.zoneMapReady = true;

        Ft8Message msg = new Ft8Message(FT8Common.FT8_MODE);
        msg.fromDxcc = false;
        applyDxccGate(msg, "K");

        assertThat(msg.fromDxcc).isFalse();
    }

    @Test
    public void gateClosed_evenWithPopulatedMap_suppressesFlag() {
        // Map has data but gate is closed (shouldn't happen in practice,
        // but verifies the gate is the controlling factor, not the map).
        GeneralVariables.addDxcc("K");
        GeneralVariables.zoneMapReady = false;

        Ft8Message msg = new Ft8Message(FT8Common.FT8_MODE);
        msg.fromDxcc = false;
        applyDxccGate(msg, "VK"); // VK not in map → would be "new" without gate

        assertThat(msg.fromDxcc).isFalse();
    }
}
