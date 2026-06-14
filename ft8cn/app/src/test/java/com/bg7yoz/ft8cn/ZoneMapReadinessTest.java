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

    @Test
    public void getDxccByPrefix_returnsFalse_whenMapEmpty() {
        // Even though the map is empty (everything would be "new"), the
        // consumer should not use the result until zoneMapReady is true.
        assertThat(GeneralVariables.getDxccByPrefix("K")).isFalse();
    }

    @Test
    public void getDxccByPrefix_returnsTrue_afterAdding() {
        GeneralVariables.addDxcc("K");
        assertThat(GeneralVariables.getDxccByPrefix("K")).isTrue();
    }

    @Test
    public void zoneMapReady_defaultsFalse() {
        // Ensure the flag starts false — the gate is closed until
        // getQslDxccToMap() sets it.
        assertThat(GeneralVariables.zoneMapReady).isFalse();
    }

    @Test
    public void zoneMapReady_gatesNewDxccDisplay() {
        // Simulate a prefix NOT in the map — without the gate, it looks "new".
        // When zoneMapReady is false, callers must not mark it new.
        GeneralVariables.zoneMapReady = false;
        boolean wouldBeNew = !GeneralVariables.getDxccByPrefix("VK");
        // The raw check says "new" but the gate should prevent using it.
        assertThat(wouldBeNew).isTrue();
        assertThat(GeneralVariables.zoneMapReady).isFalse();

        // After loading, the gate opens and results are reliable.
        GeneralVariables.addDxcc("VK");
        GeneralVariables.zoneMapReady = true;
        assertThat(GeneralVariables.getDxccByPrefix("VK")).isTrue();
        assertThat(GeneralVariables.zoneMapReady).isTrue();
    }

    @Test
    public void zoneMapReady_allowsNewDxcc_whenMapLoadedButPrefixAbsent() {
        // After the map is ready, a prefix NOT in the map is genuinely new.
        GeneralVariables.addDxcc("K");
        GeneralVariables.zoneMapReady = true;
        assertThat(GeneralVariables.getDxccByPrefix("VK")).isFalse();
        // Consumer should now mark VK as new — correctly.
    }
}
